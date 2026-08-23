package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ProjectUploadServiceTest {

    @TempDir
    Path tempDir;

    private final ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
    private final ProjectStorageProperties properties = new ProjectStorageProperties();
    private InMemoryObjectStorage objectStorage;
    private ProjectUploadService service;

    @BeforeEach
    void setUp() {
        objectStorage = new InMemoryObjectStorage();
        service = new ProjectUploadService(projects, objectStorage, properties, new ObjectMapper());
        when(projects.saveAndFlush(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uploadCreatesAnIndependentMinioSnapshotAndAppliesSafetyRules() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source-notes.txt"), "original source");
        MockMultipartFile notes = new MockMultipartFile("files", "study/docs/notes.txt", "text/plain",
                Files.readAllBytes(source));
        MockMultipartFile secret = new MockMultipartFile("files", "study/.env", "text/plain",
                "SECRET=hidden".getBytes());
        MockMultipartFile ignored = new MockMultipartFile("files", "study/target/output.txt", "text/plain",
                "ignored".getBytes());

        Project project = service.upload(7L, "Study", List.of("**"), List.of("target/**"),
                List.of(notes, secret, ignored));

        assertThat(project.getRootType()).isEqualTo(ProjectRootType.MINIO_OBJECTS);
        assertThat(project.getRootPath()).startsWith("projects/7/");
        assertThat(project.getCanonicalRootPath()).isEqualTo(".");
        assertThat(objectStorage.readFile(project.getRootPath(), "docs/notes.txt", 1024))
                .isEqualTo("original source".getBytes());
        assertThat(objectStorage.readManifest(project.getRootPath()))
                .extracting(ProjectObjectEntry::path).containsExactly("docs/notes.txt");

        Files.writeString(source, "changed locally");
        assertThat(objectStorage.readFile(project.getRootPath(), "docs/notes.txt", 1024))
                .isEqualTo("original source".getBytes());
    }

    @Test
    void uploadPreservesOnlyValidatedSupportedBinaryAssets()
            throws Exception {
        byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(
                StandardCharsets.US_ASCII);
        byte[] doc = legacyDocContainer();
        byte[] docx = ooxml("word/document.xml");
        byte[] xlsx = ooxml("xl/workbook.xml");
        MockMultipartFile notes = new MockMultipartFile(
                "files", "study/notes.txt", "text/plain",
                "safe".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile paper = new MockMultipartFile(
                "files", "study/paper.pdf", "application/pdf", pdf);
        MockMultipartFile document = new MockMultipartFile(
                "files", "study/paper.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx);
        MockMultipartFile legacyDocument = new MockMultipartFile(
                "files", "study/legacy.doc", "application/msword", doc);
        MockMultipartFile workbook = new MockMultipartFile(
                "files", "study/results.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx);
        MockMultipartFile fakePdf = new MockMultipartFile(
                "files", "study/fake.pdf", "application/pdf",
                "not-pdf".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile unsupportedBinary = new MockMultipartFile(
                "files", "study/image.png", "image/png",
                new byte[] {'P', 'N', 'G', 0, 1});

        Project project = service.upload(7L, "Study", List.of("**"),
                List.of(), List.of(notes, paper, legacyDocument, document,
                        workbook, fakePdf, unsupportedBinary));

        assertThat(objectStorage.readManifest(project.getRootPath()))
                .extracting(ProjectObjectEntry::path)
                .containsExactly("legacy.doc", "notes.txt", "paper.docx",
                        "paper.pdf", "results.xlsx");
        assertThat(objectStorage.readFile(
                project.getRootPath(), "legacy.doc", 4096))
                .isEqualTo(doc);
        assertThat(objectStorage.readFile(
                project.getRootPath(), "paper.pdf", 1024))
                .isEqualTo(pdf);
        assertThat(objectStorage.readFile(
                project.getRootPath(), "paper.docx", 4096))
                .isEqualTo(docx);
        assertThat(objectStorage.readFile(
                project.getRootPath(), "results.xlsx", 4096))
                .isEqualTo(xlsx);
    }

    private static byte[] legacyDocContainer() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (POIFSFileSystem filesystem = new POIFSFileSystem()) {
            filesystem.getRoot().createDocument("WordDocument",
                    new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
            filesystem.writeFilesystem(output);
        }
        return output.toByteArray();
    }

    @Test
    void uploadRejectsTraversalAndCleansUpIncompleteObjects() {
        MockMultipartFile traversal = new MockMultipartFile("files", "study/../escape.txt", "text/plain",
                "escape".getBytes());

        assertThatThrownBy(() -> service.upload(7L, "Study", List.of("**"), List.of(),
                List.of(traversal))).isInstanceOf(InvalidProjectPathException.class);

        assertThat(objectStorage.deletedPrefixes).containsExactly(objectStorage.prefix);
        assertThat(objectStorage.objects).isEmpty();
    }

    @Test
    void uploadEnforcesPerFileAndTotalBudgets() {
        properties.setMaxFileBytes(4);
        MockMultipartFile oversized = new MockMultipartFile("files", "study/notes.txt", "text/plain",
                "12345".getBytes());

        assertThatThrownBy(() -> service.upload(7L, "Study", List.of("**"), List.of(),
                List.of(oversized))).isInstanceOf(ProjectTraversalLimitException.class);
    }

    @Test
    void uploadPreservesUnicodeParenthesesAndNestedRelativePaths() {
        MockMultipartFile source = new MockMultipartFile("files",
                "P-FDA-MIMO_v3(agent_test)/中文目录/波形(最终版).m", "text/plain",
                "disp('ok')".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Project project = service.upload(7L, "极化滤波器", List.of("**"), List.of(), List.of(source));

        assertThat(objectStorage.readManifest(project.getRootPath()))
                .extracting(ProjectObjectEntry::path)
                .containsExactly("中文目录/波形(最终版).m");
        assertThat(objectStorage.readFile(project.getRootPath(), "中文目录/波形(最终版).m", 1024))
                .isEqualTo("disp('ok')".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] ooxml(String part) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : List.of("[Content_Types].xml", part)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("content".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static final class InMemoryObjectStorage implements ProjectObjectStorage {
        private final String prefix = "projects/7/11111111-1111-1111-1111-111111111111";
        private final Map<String, byte[]> objects = new HashMap<>();
        private final java.util.ArrayList<String> deletedPrefixes = new java.util.ArrayList<>();
        private List<ProjectObjectEntry> manifest = List.of();

        @Override public String createPrefix(Long userId) { return prefix; }

        @Override
        public ProjectObjectEntry storeFile(String prefix, String relativePath, MultipartFile file) {
            try {
                byte[] bytes = file.getBytes();
                objects.put(relativePath, bytes);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                return new ProjectObjectEntry(relativePath, bytes.length, Instant.parse("2026-07-15T00:00:00Z"), hash);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override public void writeManifest(String prefix, List<ProjectObjectEntry> files) { manifest = List.copyOf(files); }
        @Override public List<ProjectObjectEntry> readManifest(String prefix) { return manifest; }
        @Override public byte[] readFile(String prefix, String relativePath, long maxBytes) { return objects.get(relativePath); }
        @Override public void deletePrefix(String prefix) { deletedPrefixes.add(prefix); objects.clear(); manifest = List.of(); }
    }
}
