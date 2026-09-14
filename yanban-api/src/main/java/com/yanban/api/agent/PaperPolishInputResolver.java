package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.api.project.ProjectService;
import com.yanban.knowledge.config.KnowledgeStorageProperties;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.service.PaperStorageService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Resolves source authority, never retrieval snippets or model-generated storage locations. */
@Component
public class PaperPolishInputResolver {
    static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("sourceTaskId", "documentId", "projectPath", "expectedSha256",
            "expectedProjectVersion", "latexText", "bibDocumentId", "bibProjectPath", "bibSha256", "bibText", "targetLanguage");
    private final PaperTaskRepository tasks;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperStorageService paperStorage;
    private final KbDocumentRepository documents;
    private final ObjectProvider<MinioClient> minio;
    private final KnowledgeStorageProperties storage;
    private final ProjectService projects;

    public PaperPolishInputResolver(PaperTaskRepository tasks, PaperTaskArtifactRepository artifacts,
            PaperStorageService paperStorage, KbDocumentRepository documents, ObjectProvider<MinioClient> minio,
            KnowledgeStorageProperties storage, ProjectService projects) {
        this.tasks = tasks; this.artifacts = artifacts; this.paperStorage = paperStorage;
        this.documents = documents; this.minio = minio; this.storage = storage; this.projects = projects;
    }

    Request validate(JsonNode args) {
        if (args == null || !args.isObject()) throw bad("请上传 .tex 论文（可附 .bib），选择项目中的 .tex 文件，或提供已有论文任务 ID。");
        args.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) throw bad("论文工具参数不支持: " + name); });
        Request r = new Request(id(args, "sourceTaskId"), id(args, "documentId"), text(args, "projectPath"),
                text(args, "expectedSha256"), text(args, "expectedProjectVersion"), text(args, "latexText"),
                id(args, "bibDocumentId"), text(args, "bibProjectPath"), text(args, "bibSha256"),
                text(args, "bibText"), text(args, "targetLanguage"));
        int sources = (r.sourceTaskId != null ? 1 : 0) + (r.documentId != null ? 1 : 0)
                + (r.projectPath != null ? 1 : 0) + (r.latexText != null ? 1 : 0);
        if (sources != 1) throw bad("必须且只能选择一个论文输入：sourceTaskId、documentId、projectPath 或 latexText。支持 .tex 及可选 .bib。");
        if (!"zh".equals(r.targetLanguage) && !"en".equals(r.targetLanguage)) throw bad("targetLanguage 必须为 zh 或 en。");
        if ((r.bibDocumentId != null && r.documentId == null)
                || (r.bibText != null && r.latexText == null)
                || (r.bibProjectPath != null && r.projectPath == null)
                || (r.expectedSha256 != null && r.projectPath == null)
                || (r.bibSha256 != null && r.bibProjectPath == null)) throw bad("参考文献与哈希参数必须对应同一类型的论文输入。");
        if (r.projectPath != null) {
            path(r.projectPath, ".tex"); hash(r.expectedSha256);
            if (r.expectedProjectVersion == null || r.expectedProjectVersion.length() > 255) throw bad("缺少服务器冻结的 ProjectVersion。");
            if (r.bibProjectPath != null) { path(r.bibProjectPath, ".bib"); hash(r.bibSha256); }
        }
        return r;
    }

    /** Current owner checks on both new calls and replay, without loading source bytes. */
    void authorize(Long userId, Long projectId, Request r) {
        if (projectId != null) projects.manifest(userId, projectId);
        if (r.sourceTaskId != null) task(userId, r.sourceTaskId);
        if (r.documentId != null) document(userId, projectId, r.documentId, ".tex");
        if (r.bibDocumentId != null) document(userId, projectId, r.bibDocumentId, ".bib");
        if (r.projectPath != null && projectId == null) throw bad("项目文件只能在其项目对话中发起润色。");
    }

    Source resolve(Long userId, Long projectId, Request r) {
        authorize(userId, projectId, r);
        byte[] tex;
        byte[] bib = null;
        String filename;
        String bibFilename = "references.bib";
        if (r.sourceTaskId != null) {
            PaperTask task = task(userId, r.sourceTaskId);
            filename = filename(task.getSourceFilename(), ".tex");
            tex = paperStorage.read(task.getObjectKey(), MAX_BYTES);
            var sourceBib = artifacts.findFirstByTaskIdAndTypeOrderByVersionDesc(task.getId(), "source_bib");
            if (sourceBib.isPresent()) bib = paperStorage.read(sourceBib.orElseThrow().getObjectKey(), MAX_BYTES);
        } else if (r.documentId != null) {
            KbDocument document = document(userId, projectId, r.documentId, ".tex");
            filename = filename(document.getFilename(), ".tex");
            tex = read(document);
            if (r.bibDocumentId != null) {
                KbDocument bibDocument = document(userId, projectId, r.bibDocumentId, ".bib");
                bibFilename = filename(bibDocument.getFilename(), ".bib");
                bib = read(bibDocument);
            }
        } else if (r.projectPath != null) {
            Set<String> paths = new LinkedHashSet<>(); paths.add(r.projectPath);
            if (r.bibProjectPath != null) paths.add(r.bibProjectPath);
            var cut = projects.materializeVersionBytes(userId, projectId, paths);
            if (!r.expectedProjectVersion.equals(cut.snapshot().workspace().projectVersion().value())) {
                throw conflict("项目版本已改变，请重新读取论文文件后发起新的请求。");
            }
            tex = cut.files().get(r.projectPath);
            verifyHash(tex, r.expectedSha256);
            filename = basename(r.projectPath);
            if (r.bibProjectPath != null) {
                bib = cut.files().get(r.bibProjectPath); verifyHash(bib, r.bibSha256);
                bibFilename = basename(r.bibProjectPath);
            }
        } else {
            tex = r.latexText.getBytes(StandardCharsets.UTF_8); filename = "paper.tex";
            if (r.bibText != null) bib = r.bibText.getBytes(StandardCharsets.UTF_8);
        }
        validateBytes(tex, true);
        if (bib != null) validateBytes(bib, false);
        return new Source(new SourceFile(filename, tex), bib == null ? null : new SourceFile(bibFilename, bib),
                PaperPolishStartService.digest(tex));
    }

    private PaperTask task(Long userId, Long taskId) {
        PaperTask task = tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> inaccessible("论文任务"));
        filename(task.getSourceFilename(), ".tex");
        if (task.getObjectKey() == null || task.getObjectKey().isBlank()) throw inaccessible("论文原文件");
        return task;
    }

    private KbDocument document(Long userId, Long projectId, Long documentId, String extension) {
        KbDocument document = documents.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> inaccessible("上传文档"));
        if (document.getDeletedAt() != null || "DELETED".equals(document.getVersionStatus())
                || !"USER_UPLOAD".equals(document.getSourceType()) || document.getObjectKey() == null
                || document.getObjectKey().isBlank()) throw inaccessible("上传原文件");
        if (document.getProjectId() != null) {
            if (projectId != null && !projectId.equals(document.getProjectId())) throw inaccessible("当前项目的上传文档");
            projects.manifest(userId, document.getProjectId());
        }
        filename(document.getFilename(), extension);
        if (document.getFileSize() != null && document.getFileSize() > MAX_BYTES) throw bad("论文或参考文献原文件不得超过 1 MiB。");
        return document;
    }

    private byte[] read(KbDocument document) {
        MinioClient client = minio.getIfAvailable();
        if (client == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "论文附件存储暂不可用。");
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(storage.getBucket()).object(document.getObjectKey()).build())) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw bad("论文或参考文献原文件不得超过 1 MiB。");
            if (document.getFileSize() != null && document.getFileSize() != bytes.length) throw conflict("附件大小已改变，请重新上传。");
            if (document.getFileDigest() != null && !document.getFileDigest().isBlank()) verifyHash(bytes, document.getFileDigest());
            return bytes;
        } catch (ResponseStatusException failure) { throw failure; }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "论文附件读取失败，请稍后重试。"); }
    }

    private static void validateBytes(byte[] bytes, boolean tex) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw bad("论文或参考文献原文件必须非空且不超过 1 MiB。");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0 || text.isBlank()) throw bad("论文原文件必须是 UTF-8 文本。");
            if (tex && (!text.contains("\\documentclass") || !text.contains("\\begin{document}") || !text.contains("\\end{document}"))) {
                throw bad("需要完整 LaTeX 文档（含 documentclass 和 document 环境），不能使用 PDF、Word、摘要或局部段落代替。");
            }
        } catch (CharacterCodingException failure) { throw bad("论文原文件必须使用 UTF-8 编码。"); }
    }
    private static void verifyHash(byte[] bytes, String hash) {
        if (bytes == null || !PaperPolishStartService.digest(bytes).equals(hash)) throw conflict("论文来源内容哈希不匹配，请重新读取原文件。");
    }
    private static String basename(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private static String filename(String value, String extension) {
        if (value == null || value.length() > 255 || value.contains("/") || value.contains("\\")
                || !value.toLowerCase(Locale.ROOT).endsWith(extension)) throw bad("此输入必须为 " + extension + " 原文件；PDF/Word 请从论文页面使用支持的 LaTeX 输入。");
        return value;
    }
    private static void path(String value, String extension) {
        if (value.length() > 1024 || value.startsWith("/") || value.contains("\\") || value.contains(":")
                || value.contains("\0") || java.util.Arrays.stream(value.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) throw bad("需要精确的项目相对路径。");
        filename(basename(value), extension);
    }
    private static void hash(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw bad("需要文件的精确 SHA-256，不能猜测。"); }
    private static Long id(JsonNode args, String name) {
        JsonNode value = args.get(name); if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw bad(name + " 必须为正整数。");
        return value.longValue();
    }
    private static String text(JsonNode args, String name) {
        JsonNode value = args.get(name); if (value == null) return null;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > MAX_BYTES) throw bad(name + " 必须为有界非空字符串。");
        return value.textValue();
    }
    private static ResponseStatusException bad(String message) { return PaperPolishStartService.bad(message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException inaccessible(String noun) { return new ResponseStatusException(HttpStatus.NOT_FOUND, noun + "不存在或不可访问。"); }

    record Request(Long sourceTaskId, Long documentId, String projectPath, String expectedSha256,
                   String expectedProjectVersion, String latexText, Long bibDocumentId, String bibProjectPath,
                   String bibSha256, String bibText, String targetLanguage) {}
    record Source(MultipartFile tex, MultipartFile bib, String sha256) {}

    private static final class SourceFile implements MultipartFile {
        private final String filename;
        private final byte[] bytes;
        SourceFile(String filename, byte[] bytes) { this.filename = filename; this.bytes = bytes.clone(); }
        public String getName() { return "file"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return "text/plain; charset=UTF-8"; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
