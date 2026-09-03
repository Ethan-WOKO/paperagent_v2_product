package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.FileReadRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.SandboxInput;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceWriteRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.DocxBlock;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDocxCreateRequest;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDiffEntry;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnProjectVersionSourceFactory;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

class AgentEngineWorkspaceGatewayTest {
    private static final String TASK = "task." + "1".repeat(64);
    private static final String VERSION = "3".repeat(64);

    @TempDir
    Path temporary;

    @Test
    void listsAndReadsExactFrozenUtf8BytesWithHashAttestation() {
        byte[] content = "class Sort {}\n".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        AgentEngineWorkspaceGateway gateway = gateway(content, hash);
        EngineTaskAuthority authority = authority();

        var list = gateway.list(authority);
        assertThat(list.projectVersion()).isEqualTo(VERSION);
        assertThat(list.files()).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo("Sort.java");
            assertThat(file.sha256()).isEqualTo(hash);
            assertThat(file.mediaType()).isEqualTo("text/x-java-source");
        });
        var read = gateway.read(authority, new FileReadRequest("1.0", "Sort.java", hash));
        assertThat(read.content()).isEqualTo("class Sort {}\n");
        assertThat(read.truncated()).isFalse();

        assertThatThrownBy(() -> gateway.read(authority,
                new FileReadRequest("1.0", "Sort.java", "9".repeat(64))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_FILE_HASH_CONFLICT"));
    }

    @Test
    void rejectsTraversalBeforeReadingWorkspace() {
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        AgentEngineWorkspaceGateway gateway = gateway(content, sha256(content));

        assertThatThrownBy(() -> gateway.read(authority(),
                new FileReadRequest("1.0", "../secret", "9".repeat(64))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_PATH_INVALID"));
    }

    @Test
    void automaticallyExtractsPdfDocxAndXlsxThroughTheSingleReadGateway()
            throws Exception {
        assertStructuredRead("notes.pdf", pdf("PDF project notes"),
                "project.document.extract", "PDF project notes");
        assertStructuredRead("notes.docx", docx("DOCX project notes"),
                "project.document.extract", "DOCX project notes");
        assertStructuredRead("metrics.xlsx", xlsx("accuracy", "0.95"),
                "project.spreadsheet.inspect", "accuracy");
    }

    @Test
    void continuesStructuredDocumentReadsWithAnOpaqueCursor()
            throws Exception {
        byte[] content = pdf("first page", "second page");
        String hash = sha256(content);
        AgentEngineWorkspaceGateway gateway = gateway(
                "notes.pdf", content, hash);
        var first = gateway.read(authority(), new FileReadRequest(
                "1.0", "notes.pdf", hash, null, 1));
        var firstJson = new ObjectMapper().readTree(first.content());
        String cursor = firstJson.path("summary")
                .path("nextCursor").asText();

        var second = gateway.read(authority(), new FileReadRequest(
                "1.0", "notes.pdf", hash, cursor, 1));
        var secondJson = new ObjectMapper().readTree(second.content());

        assertThat(firstJson.path("locations").toString())
                .contains("first page").doesNotContain("second page");
        assertThat(secondJson.path("locations").toString())
                .contains("second page").doesNotContain("first page");
        assertThat(secondJson.path("summary").path("hasMore").asBoolean())
                .isFalse();
    }

    @Test
    void replacesAndAddsOnlyWithExactHashesAndReportsWorkspaceDiff() {
        byte[] content = "class Sort {}\n".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        AgentEngineWorkspaceGateway gateway = gateway(content, hash);
        ObjectMapper json = new ObjectMapper();

        String replacement = "class Sort { int value; }\n";
        WorkspaceWriteRequest modify = writeRequest(json, "call." + "a".repeat(40),
                "MODIFY", "Sort.java", hash, replacement);
        var changed = gateway.write(writableAuthority(), modify);
        assertThat(changed.replayed()).isFalse();
        assertThat(changed.afterSha256()).isEqualTo(sha256(replacement.getBytes(StandardCharsets.UTF_8)));
        assertThat(gateway.write(writableAuthority(), modify).replayed()).isTrue();

        WorkspaceWriteRequest add = writeRequest(json, "call." + "b".repeat(40),
                "ADD", "Added.java", null, "class Added {}\n");
        gateway.write(writableAuthority(), add);
        assertThat(gateway.diff(writableAuthority()).entries())
                .extracting(value -> value.operation() + ":" + value.path())
                .containsExactly("ADD:Added.java", "MODIFY:Sort.java");

        assertThatThrownBy(() -> gateway.write(writableAuthority(), writeRequest(json,
                "call." + "c".repeat(40), "MODIFY", "Sort.java", hash, "class Wrong {}")))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_FILE_HASH_CONFLICT"));
    }

    @Test
    void reattestsExactWorkspaceBytesForPublication() {
        byte[] content = "class Sort {}\n".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(content);
        AgentEngineWorkspaceGateway gateway = gateway(content, hash);
        String replacement = "class Sort { int value; }\n";
        gateway.write(writableAuthority(), writeRequest(new ObjectMapper(),
                "call." + "a".repeat(40), "MODIFY", "Sort.java", hash, replacement));

        var diff = gateway.diff(writableAuthority());
        var changes = gateway.publicationChanges(writableAuthority(), diff.entries());

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.operation()).isEqualTo("MODIFY");
            assertThat(change.path()).isEqualTo("Sort.java");
            assertThat(change.beforeSha256()).isEqualTo(hash);
            assertThat(change.afterSha256()).isEqualTo(
                    sha256(replacement.getBytes(StandardCharsets.UTF_8)));
            assertThat(change.content()).isEqualTo(replacement);
        });
        assertThatThrownBy(() -> gateway.publicationChanges(writableAuthority(), List.of(
                new WorkspaceDiffEntry("MODIFY", "Sort.java", hash, "9".repeat(64)))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("WORKSPACE_PUBLICATION_DIFF_CONFLICT"));
    }

    @Test
    void createsOnlyNewVerifiedDocxAndCarriesBinaryPublicationAttestation() {
        byte[] content = "base\n".getBytes(StandardCharsets.UTF_8);
        AgentEngineWorkspaceGateway gateway = gateway(content, sha256(content));
        ObjectMapper json = new ObjectMapper();
        List<DocxBlock> blocks = List.of(new DocxBlock(
                "PARAGRAPH", "生成的正文", null, null, null, null,
                true, null, null));
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("mode", "CREATE");
        semantics.put("path", "生成结果.docx");
        semantics.put("title", "结果");
        semantics.put("author", "研伴");
        semantics.put("styleProfile", "CHINESE_ACADEMIC");
        semantics.put("blocks", blocks);
        WorkspaceDocxCreateRequest request = new WorkspaceDocxCreateRequest(
                "1.0", "call." + "d".repeat(40),
                ReactPlanCanonicalJson.digest(json, semantics),
                "CREATE", "生成结果.docx", "结果", "研伴", "CHINESE_ACADEMIC", blocks);

        var written = gateway.createDocx(writableAuthority(), request);
        var changes = gateway.publicationChanges(
                writableAuthority(), gateway.diff(writableAuthority()).entries());

        assertThat(written.operation()).isEqualTo("ADD");
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.serverGeneratedDocx()).isTrue();
            assertThat(change.content()).isNull();
            assertThat(change.bytes()).startsWith((byte) 0x50, (byte) 0x4b);
            assertThat(change.afterSha256()).isEqualTo(written.afterSha256());
        });
        assertThat(gateway.createDocx(writableAuthority(), request).replayed())
                .isTrue();
    }

    @Test
    void genericUtf8WriterCannotCreateBinaryDocumentExtensions() {
        AgentEngineWorkspaceGateway gateway = gateway(
                "base".getBytes(StandardCharsets.UTF_8),
                sha256("base".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> gateway.write(writableAuthority(),
                writeRequest(new ObjectMapper(), "call." + "e".repeat(40),
                        "ADD", "unsafe.docx", null, "not a docx")))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("WORKSPACE_BINARY_WRITE_REJECTED"));
    }

    @Test
    void expandsOnlyMavenRunsToTheBoundedTrustedTextWorkspace() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pom.xml", "<project/>\n".getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/Sort.java",
                "class Sort {}\n".getBytes(StandardCharsets.UTF_8));
        files.put("src/main/java/Helper.java",
                "class Helper {}\n".getBytes(StandardCharsets.UTF_8));
        files.put("docs/guide.pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        AgentEngineWorkspaceGateway gateway = gateway(files, ignored -> { });
        String sortHash = sha256(files.get("src/main/java/Sort.java"));

        var maven = gateway.resolveInputs(authority(),
                List.of("mvn", "-o", "test"),
                List.of(new SandboxInput("src/main/java/Sort.java", sortHash)));
        var runner = gateway.resolveInputs(authority(),
                List.of("yanban-runner", "java", "src/main/java/Sort.java"),
                List.of(new SandboxInput("src/main/java/Sort.java", sortHash)));

        assertThat(maven.files()).containsOnlyKeys(
                "pom.xml", "src/main/java/Sort.java", "src/main/java/Helper.java");
        assertThat(maven.inputs()).extracting(value -> value.path())
                .containsExactly("pom.xml", "src/main/java/Helper.java",
                        "src/main/java/Sort.java");
        assertThat(runner.files()).containsOnlyKeys("src/main/java/Sort.java");
        assertThat(runner.inputs()).extracting(value -> value.path())
                .containsExactly("src/main/java/Sort.java");
    }

    @Test
    void rejectsMavenBeforeDispatchWithoutARootPomOrExactChangedAnchor() {
        byte[] sort = "class Sort {}\n".getBytes(StandardCharsets.UTF_8);
        AgentEngineWorkspaceGateway noRootPom = gateway(
                Map.of("module/pom.xml", "<project/>\n".getBytes(StandardCharsets.UTF_8),
                        "Sort.java", sort), ignored -> { });

        assertThatThrownBy(() -> noRootPom.resolveInputs(authority(),
                List.of("mvn", "-o", "test"),
                List.of(new SandboxInput("Sort.java", sha256(sort)))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MAVEN_ROOT_POM_REQUIRED"));

        Map<String, byte[]> project = Map.of(
                "pom.xml", "<project/>\n".getBytes(StandardCharsets.UTF_8),
                "Sort.java", sort);
        AgentEngineWorkspaceGateway changed = gateway(project, ignored -> { });
        String replacement = "class Sort { int value; }\n";
        changed.write(writableAuthority(), writeRequest(new ObjectMapper(),
                "call." + "m".repeat(40), "MODIFY", "Sort.java",
                sha256(sort), replacement));

        assertThatThrownBy(() -> changed.resolveInputs(writableAuthority(),
                List.of("mvn", "-o", "test"),
                List.of(new SandboxInput("pom.xml",
                        sha256(project.get("pom.xml"))))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MAVEN_CHANGED_INPUT_MISSING"));
    }

    @Test
    void rejectsUnsupportedOrOversizedMavenContextBeforeExecution() {
        Map<String, byte[]> binaryProject = Map.of(
                "pom.xml", "<project/>\n".getBytes(StandardCharsets.UTF_8),
                "src/test/resources/sample.pdf",
                "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
        AgentEngineWorkspaceGateway binary = gateway(binaryProject, ignored -> { });

        assertThatThrownBy(() -> binary.resolveInputs(authority(),
                List.of("mvn", "-o", "test"),
                List.of(new SandboxInput("pom.xml",
                        sha256(binaryProject.get("pom.xml"))))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MAVEN_BINARY_RESOURCE_UNSUPPORTED"));

        Map<String, byte[]> largeProject = Map.of(
                "pom.xml", "<project/>\n".getBytes(StandardCharsets.UTF_8),
                "Sort.java", "class Sort {}\n".getBytes(StandardCharsets.UTF_8));
        AgentEngineWorkspaceGateway limited = gateway(largeProject,
                properties -> properties.setMaxSandboxContextFiles(1));

        assertThatThrownBy(() -> limited.resolveInputs(authority(),
                List.of("mvn", "-o", "test"),
                List.of(new SandboxInput("pom.xml",
                        sha256(largeProject.get("pom.xml"))))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MAVEN_CONTEXT_LIMIT_EXCEEDED"));

        AgentEngineWorkspaceGateway perFileLimited = gateway(largeProject,
                properties -> properties.setMaxSandboxContextFileBytes(8));

        assertThatThrownBy(() -> perFileLimited.resolveInputs(authority(),
                List.of("mvn", "-o", "test"),
                List.of(new SandboxInput("pom.xml",
                        sha256(largeProject.get("pom.xml"))))))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("MAVEN_CONTEXT_LIMIT_EXCEEDED"));
    }

    @Test
    void accumulatesLongDocxInDraftWithoutWorkspaceChangesUntilFinalize() {
        byte[] base = "base".getBytes(StandardCharsets.UTF_8);
        AgentEngineWorkspaceGateway gateway = gateway(base, sha256(base));
        ObjectMapper json = new ObjectMapper();
        var first = List.of(new DocxBlock("HEADING", "第一章", 1,
                null, null, null, null, null, null));
        var second = List.of(new DocxBlock("PARAGRAPH", "第二段正文", null,
                null, null, null, true, null, null));

        var started = gateway.createDocx(writableAuthority(), docxRequest(json,
                "call." + "f".repeat(40), "START", "long.docx",
                "长文档", "研伴", "CHINESE_ACADEMIC", first));
        var appended = gateway.createDocx(writableAuthority(), docxRequest(json,
                "call." + "g".repeat(40), "APPEND", "long.docx",
                null, null, null, second));

        assertThat(started.state()).isEqualTo("DRAFTING");
        assertThat(appended.totalBlocks()).isEqualTo(2);
        assertThat(gateway.diff(writableAuthority()).changed()).isFalse();

        var completed = gateway.createDocx(writableAuthority(), docxRequest(json,
                "call." + "h".repeat(40), "FINALIZE", "long.docx",
                null, null, null, List.of()));
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.totalBlocks()).isEqualTo(2);
        assertThat(gateway.diff(writableAuthority()).entries())
                .extracting(WorkspaceDiffEntry::path).containsExactly("long.docx");
    }

    @Test
    void acceptsEngineCanonicalDigestWhenOptionalBlockFieldsAreNull() {
        byte[] base = "base".getBytes(StandardCharsets.UTF_8);
        AgentEngineWorkspaceGateway gateway = gateway(base, sha256(base));
        var request = new WorkspaceDocxCreateRequest(
                "1.0", "call." + "i".repeat(40),
                "ace37716ee33dcda265b7d9b240f4a5ed6026c644b652256218628dd1513aa5c",
                "CREATE", "test_doc.docx", "test", null,
                "CHINESE_ACADEMIC", List.of(new DocxBlock(
                "PARAGRAPH", "test", null, null, null, null,
                null, null, null)));

        assertThat(gateway.createDocx(writableAuthority(), request).state())
                .isEqualTo("COMPLETED");
    }

    private AgentEngineWorkspaceGateway gateway(byte[] content, String hash) {
        return gateway("Sort.java", content, hash);
    }

    private AgentEngineWorkspaceGateway gateway(
            String path, byte[] content, String hash) {
        return gatewaySnapshots(
                Map.of(path, new SnapshotFile(content, hash)), ignored -> { });
    }

    private AgentEngineWorkspaceGateway gateway(
            Map<String, byte[]> contents,
            Consumer<EngineGatewayProperties> configure) {
        Map<String, SnapshotFile> snapshots = new LinkedHashMap<>();
        contents.forEach((path, content) -> snapshots.put(
                path, new SnapshotFile(content, sha256(content))));
        return gatewaySnapshots(snapshots, configure);
    }

    private AgentEngineWorkspaceGateway gatewaySnapshots(
            Map<String, SnapshotFile> snapshots,
            Consumer<EngineGatewayProperties> configure) {
        AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "12", 11L, 13L, 14L),
                Optional.of(VERSION));
        when(contexts.resolve(11L, 12L)).thenReturn(context);
        when(sources.create(11L, 12L)).thenReturn(version -> {
            assertThat(version).isEqualTo(new ProjectVersionRef("14", VERSION));
            List<ProjectFileSnapshot> files = snapshots.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new ProjectFileSnapshot(
                            new ProjectPath(entry.getKey()), entry.getValue().content(),
                            new ContentHash("sha256", entry.getValue().hash()), Map.of()))
                    .toList();
            return new ProjectVersionSnapshot(version, files, Map.of());
        });
        EngineGatewayProperties properties = new EngineGatewayProperties();
        properties.setWorkspaceRoot(temporary.toString());
        configure.accept(properties);
        return new AgentEngineWorkspaceGateway(
                contexts, sources, new ProjectStorageProperties(), properties, new ObjectMapper());
    }

    private void assertStructuredRead(
            String path, byte[] content, String tool, String expectedText) {
        String hash = sha256(content);
        var result = gateway(path, content, hash).read(
                authority(), new FileReadRequest("1.0", path, hash));
        assertThat(result.content()).contains("\"tool\":\"" + tool + "\"")
                .contains(expectedText);
        assertThat(result.sha256()).isEqualTo(hash);
    }

    private static byte[] pdf(String... texts) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : texts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content =
                        new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx(String header, String value) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Summary");
            sheet.createRow(0).createCell(0).setCellValue(header);
            sheet.createRow(1).createCell(0).setCellValue(value);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static EngineTaskAuthority authority() {
        return new EngineTaskAuthority(TASK, "2".repeat(64),
                11, 12, 13, 14, VERSION, true, true,
                Instant.parse("2026-08-16T11:00:00Z"));
    }

    private static EngineTaskAuthority writableAuthority() {
        return new EngineTaskAuthority(TASK, "2".repeat(64),
                11, 12, 13, 14, VERSION, true, true, true,
                Instant.parse("2026-08-16T11:00:00Z"));
    }

    private static WorkspaceWriteRequest writeRequest(
            ObjectMapper json, String callId, String operation, String path,
            String baseSha256, String content) {
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("operation", operation);
        semantics.put("path", path);
        semantics.put("baseSha256", baseSha256);
        semantics.put("content", content);
        return new WorkspaceWriteRequest("1.0", callId,
                ReactPlanCanonicalJson.digest(json, semantics), operation, path,
                baseSha256, content);
    }

    private static WorkspaceDocxCreateRequest docxRequest(
            ObjectMapper json, String callId, String mode, String path,
            String title, String author, String styleProfile,
            List<DocxBlock> blocks) {
        Map<String, Object> semantics = new LinkedHashMap<>();
        semantics.put("mode", mode);
        semantics.put("path", path);
        semantics.put("title", title);
        semantics.put("author", author);
        semantics.put("styleProfile", styleProfile);
        semantics.put("blocks", blocks);
        return new WorkspaceDocxCreateRequest("1.0", callId,
                ReactPlanCanonicalJson.digest(json, semantics), mode, path,
                title, author, styleProfile, blocks);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record SnapshotFile(byte[] content, String hash) { }
}
