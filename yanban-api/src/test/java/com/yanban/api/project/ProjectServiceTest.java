package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

class ProjectServiceTest {

    @TempDir
    Path tempDir;

    private final ProjectStorageProperties properties = new ProjectStorageProperties();
    private final ProjectRepository projects = org.mockito.Mockito.mock(ProjectRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Path> fixtureLinks = new ArrayList<>();

    @Test
    void renameUpdatesOnlyAnOwnedProjectsDisplayName() {
        Project project = new Project(7L, "Old name", ".", ".", "[]", "[]");
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(projects.saveAndFlush(project)).thenReturn(project);
        ProjectService service = new ProjectService(projects, List.of(), properties, objectMapper);

        ProjectSummaryResponse renamed = service.rename(7L, 42L, "  New name  ");

        assertThat(renamed.name()).isEqualTo("New name");
        assertThat(project.getName()).isEqualTo("New name");
        verify(projects).saveAndFlush(project);
    }

    @AfterEach
    void clearProperties() throws IOException {
        properties.setLocalServerRoot(null);
        properties.setAllowLocalAbsoluteProjectFolders(false);
        for (Path link : fixtureLinks) {
            deleteFixtureLink(link);
        }
        fixtureLinks.clear();
    }

    @Test
    void manifestReadAndSearchExposeOnlyRelativePathsAndAuditableHashes() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.md"), "alpha\nneedle result\n");
        Files.writeString(projectRoot.resolve(".env"), "SECRET=not-visible");
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        ProjectManifestResponse manifest = service.manifest(7L, 42L);

        assertThat(manifest.files()).extracting(ProjectFileEntry::path).containsExactly("notes.md");
        assertThat(manifest.files().get(0).sha256()).hasSize(64);
        assertThat(manifest.version()).hasSize(64);
        ProjectFileResponse file = service.readFile(7L, 42L, "notes.md");
        assertThat(file.path()).isEqualTo("notes.md");
        assertThat(file.content()).contains("needle result");
        assertThat(service.search(7L, 42L, "needle", 10))
                .containsExactly(new ProjectSearchHit("notes.md", 2, "needle result", file.sha256()));
    }

    @Test
    void localSandboxMaterializationUsesOneManifestAndExposesNoRootPath() throws Exception {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.md"), "selected\n");
        Files.writeString(projectRoot.resolve("other.md"), "not materialized\n");
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        ProjectService.SandboxWorkspaceMaterialization materialized =
                service.materializeSandbox(7L, 42L, Set.of("notes.md"));

        assertThat(materialized.snapshot().files()).extracting(file -> file.relativePath().value())
                .containsExactly("notes.md", "other.md");
        assertThat(materialized.textFiles()).containsExactly(Map.entry("notes.md", "selected\n"));
        String serialized = objectMapper.writeValueAsString(materialized);
        assertThat(serialized).doesNotContain(projectRoot.toString(), serverRoot.toString(), "canonicalPath");
    }

    @Test
    void localManifestAndV2SourcePreserveValidatedBinaryAssetsAsExactBytes()
            throws Exception {
        Path serverRoot = Files.createDirectories(
                tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(
                serverRoot.resolve("study"));
        byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(
                StandardCharsets.US_ASCII);
        byte[] docx = ooxml("word/document.xml");
        byte[] xlsx = ooxml("xl/workbook.xml");
        Files.writeString(projectRoot.resolve("notes.md"),
                "needle text\n");
        Files.write(projectRoot.resolve("paper.pdf"), pdf);
        Files.write(projectRoot.resolve("paper.docx"), docx);
        Files.write(projectRoot.resolve("results.xlsx"), xlsx);
        Files.writeString(projectRoot.resolve("fake.pdf"), "not-pdf");
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(
                projectRoot, "[\"**\"]", "[]");

        ProjectManifestResponse manifest = service.manifest(7L, 42L);
        assertThat(manifest.files()).extracting(ProjectFileEntry::path)
                .containsExactly("notes.md", "paper.docx", "paper.pdf",
                        "results.xlsx");
        assertThat(service.search(7L, 42L, "needle", 10))
                .extracting(ProjectSearchHit::path)
                .containsExactly("notes.md");
        assertThatThrownBy(() -> service.readFile(
                7L, 42L, "paper.pdf"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error)
                        .getStatusCode().value())
                .isEqualTo(404);

        Set<String> allPaths = Set.of(
                "notes.md", "paper.docx", "paper.pdf", "results.xlsx");
        ProjectService.SandboxWorkspaceMaterialization sandbox =
                service.materializeSandbox(7L, 42L, allPaths);
        assertThat(sandbox.snapshot().files()).hasSize(4);
        assertThat(sandbox.textFiles()).containsOnlyKeys("notes.md");

        ProjectService.ProjectVersionByteMaterialization source =
                service.materializeVersionBytes(7L, 42L, allPaths);
        assertThat(source.snapshot().files()).hasSize(4);
        assertThat(source.files()).containsOnlyKeys(allPaths);
        assertThat(source.files().get("paper.pdf")).isEqualTo(pdf);
        assertThat(source.files().get("paper.docx")).isEqualTo(docx);
        assertThat(source.files().get("results.xlsx")).isEqualTo(xlsx);
        byte[] callerCopy = source.files().get("paper.pdf");
        callerCopy[0] = 0;
        assertThat(source.files().get("paper.pdf")).isEqualTo(pdf);

        Files.write(projectRoot.resolve("paper.pdf"),
                "%PDF-2.0\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertThat(service.manifest(7L, 42L).version())
                .isNotEqualTo(manifest.version());
    }

    @Test
    void previewsStructuredDocumentsWithoutChangingTheTextOnlyReadContract()
            throws Exception {
        Path serverRoot = Files.createDirectories(tempDir.resolve("preview-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Preview paragraph");
            document.createParagraph().createRun().setText("Later paragraph");
            document.write(output);
            documentBytes = output.toByteArray();
        }
        Files.write(projectRoot.resolve("notes.docx"), documentBytes);
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        assertThatThrownBy(() -> service.readFile(7L, 42L, "notes.docx"))
                .isInstanceOf(ResponseStatusException.class);
        String firstPage = service.previewFile(
                7L, 42L, "notes.docx", null, 1).content();
        String cursor = objectMapper.readTree(firstPage).path("summary")
                .path("nextCursor").asText();
        String secondPage = service.previewFile(
                7L, 42L, "notes.docx", cursor, 1).content();
        assertThat(firstPage)
                .contains("project.document.extract", "Preview paragraph")
                .doesNotContain("Later paragraph");
        assertThat(secondPage)
                .contains("Later paragraph")
                .doesNotContain("Preview paragraph");
        assertThat(service.readRawFile(7L, 42L, "notes.docx").content())
                .isEqualTo(documentBytes);
    }

    @Test
    void managedUploadSandboxMaterializationRemainsReadOnlyAndRootFree() throws Exception {
        Path storageRoot = Files.createDirectories(tempDir.resolve("managed-projects"));
        Path snapshot = Files.createDirectories(storageRoot.resolve("snapshot-1"));
        Path file = Files.writeString(snapshot.resolve("paper.tex"), "immutable server copy\n");
        properties.setManagedStorageRoot(storageRoot.toString());
        Project project = Project.managedUpload(7L, "Uploaded", snapshot.toRealPath().toString(),
                "[\"**\"]", "[]");
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        ProjectService service = new ProjectService(projects,
                List.of(new ManagedUploadProjectRootProvider(properties)), properties, objectMapper);

        ProjectService.SandboxWorkspaceMaterialization materialized =
                service.materializeSandbox(7L, 42L, Set.of("paper.tex"));

        assertThat(materialized.textFiles()).containsEntry("paper.tex", "immutable server copy\n");
        assertThat(file).hasContent("immutable server copy\n");
        assertThat(objectMapper.writeValueAsString(materialized)).doesNotContain(snapshot.toString(), storageRoot.toString());
    }

    @Test
    void minioSandboxMaterializationRechecksManifestAndReadsOnlyRequestedObject() throws Exception {
        byte[] selected = "selected object\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] other = "other object\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String selectedHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(selected));
        String otherHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(other));
        String prefix = "projects/7/11111111-1111-1111-1111-111111111111";
        Project project = Project.minioUpload(7L, "MinIO Study", prefix, "[\"**\"]", "[]");
        ProjectObjectEntry selectedEntry = new ProjectObjectEntry("docs/selected.md", selected.length,
                java.time.Instant.EPOCH, selectedHash);
        ProjectObjectEntry otherEntry = new ProjectObjectEntry("docs/other.md", other.length,
                java.time.Instant.EPOCH, otherHash);
        ProjectObjectStorage objectStorage = org.mockito.Mockito.mock(ProjectObjectStorage.class);
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(objectStorage.readManifest(prefix)).thenReturn(List.of(selectedEntry, otherEntry));
        when(objectStorage.readFile(prefix, "docs/selected.md", properties.getMaxFileBytes()))
                .thenReturn(selected);
        ProjectService service = new ProjectService(projects, List.of(), properties, objectMapper, objectStorage);

        ProjectService.SandboxWorkspaceMaterialization materialized =
                service.materializeSandbox(7L, 42L, Set.of("docs/selected.md"));

        assertThat(materialized.snapshot().files()).hasSize(2);
        assertThat(materialized.textFiles()).containsExactly(
                Map.entry("docs/selected.md", "selected object\n"));
        verify(objectStorage, times(2)).readManifest(prefix);
        verify(objectStorage).readFile(prefix, "docs/selected.md", properties.getMaxFileBytes());
        verify(objectStorage, never()).readFile(prefix, "docs/other.md", properties.getMaxFileBytes());
        assertThat(objectMapper.writeValueAsString(materialized)).doesNotContain(prefix);
    }

    @Test
    void minioManifestAndV2SourceValidateBinaryObjectContent()
            throws Exception {
        byte[] notes = "text\n".getBytes(StandardCharsets.UTF_8);
        byte[] pdf = "%PDF-1.7\n%%EOF".getBytes(
                StandardCharsets.US_ASCII);
        byte[] fakeDocx = "not-docx".getBytes(StandardCharsets.UTF_8);
        String prefix = "projects/7/11111111-1111-1111-1111-111111111111";
        Project project = Project.minioUpload(
                7L, "MinIO Study", prefix, "[\"**\"]", "[]");
        ProjectObjectEntry notesEntry = objectEntry("notes.md", notes);
        ProjectObjectEntry pdfEntry = objectEntry("paper.pdf", pdf);
        ProjectObjectEntry fakeEntry = objectEntry("fake.docx", fakeDocx);
        ProjectObjectStorage objectStorage =
                org.mockito.Mockito.mock(ProjectObjectStorage.class);
        when(projects.findByIdAndUserId(42L, 7L))
                .thenReturn(Optional.of(project));
        when(objectStorage.readManifest(prefix))
                .thenReturn(List.of(notesEntry, pdfEntry, fakeEntry));
        when(objectStorage.readFile(prefix, "notes.md",
                properties.getMaxFileBytes())).thenReturn(notes);
        when(objectStorage.readFile(prefix, "paper.pdf",
                properties.getMaxFileBytes())).thenReturn(pdf);
        when(objectStorage.readFile(prefix, "fake.docx",
                properties.getMaxFileBytes())).thenReturn(fakeDocx);
        ProjectService service = new ProjectService(
                projects, List.of(), properties, objectMapper, objectStorage);

        assertThat(service.manifest(7L, 42L).files())
                .extracting(ProjectFileEntry::path)
                .containsExactly("notes.md", "paper.pdf");
        ProjectService.ProjectVersionByteMaterialization source =
                service.materializeVersionBytes(
                        7L, 42L, Set.of("notes.md", "paper.pdf"));
        assertThat(source.files().get("notes.md")).isEqualTo(notes);
        assertThat(source.files().get("paper.pdf")).isEqualTo(pdf);
        assertThat(source.snapshot().files()).hasSize(2);
    }

    @Test
    void minioSandboxMaterializationRejectsUnrequestedFileChangingBetweenManifests() throws Exception {
        byte[] selected = "selected object\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] other = "other object\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String selectedHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(selected));
        String otherHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(other));
        String changedHash = "f".repeat(64);
        String prefix = "projects/7/11111111-1111-1111-1111-111111111111";
        Project project = Project.minioUpload(7L, "MinIO Study", prefix, "[\"**\"]", "[]");
        ProjectObjectEntry selectedEntry = new ProjectObjectEntry("docs/selected.md", selected.length,
                java.time.Instant.EPOCH, selectedHash);
        ProjectObjectEntry otherEntry = new ProjectObjectEntry("docs/other.md", other.length,
                java.time.Instant.EPOCH, otherHash);
        ProjectObjectEntry changedOtherEntry = new ProjectObjectEntry("docs/other.md", other.length,
                java.time.Instant.EPOCH.plusSeconds(1), changedHash);
        ProjectObjectStorage objectStorage = org.mockito.Mockito.mock(ProjectObjectStorage.class);
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(objectStorage.readManifest(prefix))
                .thenReturn(List.of(selectedEntry, otherEntry), List.of(selectedEntry, changedOtherEntry));
        when(objectStorage.readFile(prefix, "docs/selected.md", properties.getMaxFileBytes()))
                .thenReturn(selected);
        ProjectService service = new ProjectService(projects, List.of(), properties, objectMapper, objectStorage);

        assertThatThrownBy(() -> service.materializeSandbox(7L, 42L, Set.of("docs/selected.md")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode().value()).isEqualTo(409);
                    assertThat(response.getReason()).doesNotContain(prefix);
                });
        verify(objectStorage, times(2)).readManifest(prefix);
        verify(objectStorage).readFile(prefix, "docs/selected.md", properties.getMaxFileBytes());
        verify(objectStorage, never()).readFile(prefix, "docs/other.md", properties.getMaxFileBytes());
    }

    @Test
    void rejectsAbsoluteTraversalAndNormalizedEscapePaths() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.md"), "safe");
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        for (String path : List.of(projectRoot.resolve("notes.md").toString(), "../notes.md", "folder/../notes.md")) {
            assertThatThrownBy(() -> service.readFile(7L, 42L, path))
                    .isInstanceOf(InvalidProjectPathException.class);
        }
    }

    @Test
    void rejectsSymbolicLinkEscape() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Path symlink = createDirectoryLink(projectRoot.resolve("escape"), outside);
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        assertThat(service.manifest(7L, 42L).files()).isEmpty();
        assertThatThrownBy(() -> service.readFile(7L, 42L, "escape/private.txt"))
                .isInstanceOf(InvalidProjectPathException.class);
    }

    @Test
    void unauthorizedProjectAndEmptyIncludeRulesFailClosed() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.md"), "safe");
        properties.setLocalServerRoot(serverRoot.toString());
        ProjectService service = serviceFor(projectRoot, "[]", "[]");

        assertThat(service.manifest(7L, 42L).files()).isEmpty();
        assertThatThrownBy(() -> service.readFile(7L, 42L, "notes.md"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
        assertThatThrownBy(() -> service.manifest(8L, 42L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void deletesOwnedBindingEvenWhenPersistedRootIsMissingWithoutResolvingIt() {
        Path missingRoot = tempDir.resolve("missing-project-root");
        Project project = new Project(7L, "Missing", ".", missingRoot.toString(), "[\"**\"]", "[]");
        ProjectRootProvider rootProvider = org.mockito.Mockito.mock(ProjectRootProvider.class);
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        ProjectService service = new ProjectService(projects, List.of(rootProvider), properties, objectMapper);

        service.delete(7L, 42L);

        verify(projects).delete(project);
        verifyNoInteractions(rootProvider);
        assertThat(missingRoot).doesNotExist();
    }

    @Test
    void deletingBindingLeavesTheBoundDirectoryAndFilesUntouched() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("bound-project"));
        Path sentinel = Files.writeString(projectRoot.resolve("sentinel.txt"), "must remain unchanged");
        Project project = new Project(7L, "Bound", ".", projectRoot.toRealPath().toString(), "[\"**\"]", "[]");
        ProjectRootProvider rootProvider = org.mockito.Mockito.mock(ProjectRootProvider.class);
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        ProjectService service = new ProjectService(projects, List.of(rootProvider), properties, objectMapper);

        service.delete(7L, 42L);

        verify(projects).delete(project);
        verifyNoInteractions(rootProvider);
        assertThat(projectRoot).isDirectory();
        assertThat(sentinel).hasContent("must remain unchanged");
    }

    @Test
    void deletingUploadedProjectRemovesOnlyItsManagedSnapshot() throws IOException {
        Path storageRoot = Files.createDirectories(tempDir.resolve("managed-projects"));
        Path snapshot = Files.createDirectories(storageRoot.resolve("snapshot-1"));
        Files.writeString(snapshot.resolve("notes.txt"), "server copy");
        properties.setManagedStorageRoot(storageRoot.toString());
        Project project = Project.managedUpload(7L, "Uploaded", snapshot.toRealPath().toString(), "[\"**\"]", "[]");
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        ProjectService service = new ProjectService(projects,
                List.of(new ManagedUploadProjectRootProvider(properties)), properties, objectMapper);

        service.delete(7L, 42L);

        verify(projects).delete(project);
        assertThat(snapshot).doesNotExist();
        assertThat(storageRoot).isDirectory();
    }

    @Test
    void minioProjectManifestReadSearchAndDeleteUseObjectsWithoutLocalSnapshot() throws Exception {
        byte[] content = "alpha\nneedle result\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        String prefix = "projects/7/11111111-1111-1111-1111-111111111111";
        Project project = Project.minioUpload(7L, "MinIO Study", prefix, "[\"**\"]", "[]");
        ProjectObjectEntry entry = new ProjectObjectEntry("docs/notes.md", content.length,
                java.time.Instant.parse("2026-07-15T00:00:00Z"), hash);
        ProjectObjectStorage objectStorage = org.mockito.Mockito.mock(ProjectObjectStorage.class);
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(objectStorage.readManifest(prefix)).thenReturn(List.of(entry));
        when(objectStorage.readFile(prefix, "docs/notes.md", properties.getMaxFileBytes())).thenReturn(content);
        ProjectService service = new ProjectService(projects, List.of(), properties, objectMapper, objectStorage);

        assertThat(service.manifest(7L, 42L).files()).containsExactly(entry.toFileEntry());
        assertThat(service.readFile(7L, 42L, "docs/notes.md").content()).contains("needle result");
        assertThat(service.search(7L, 42L, "needle", 10))
                .containsExactly(new ProjectSearchHit("docs/notes.md", 2, "needle result", hash));

        service.delete(7L, 42L);
        verify(projects).delete(project);
        verify(objectStorage).deletePrefix(prefix);
        assertThat(Path.of(project.getCanonicalRootPath())).isEqualTo(Path.of("."));
    }

    @Test
    void deletingMissingOrNonOwnedBindingReturnsNotFoundWithoutDeletingAnything() {
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.empty());
        ProjectService service = new ProjectService(projects, List.of(), properties, objectMapper);

        assertThatThrownBy(() -> service.delete(7L, 42L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
        org.mockito.Mockito.verify(projects, org.mockito.Mockito.never()).delete(any(Project.class));
    }

    @Test
    void invalidOrCorruptPersistedRulesDenyAllRatherThanDroppingIgnoreRules() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.txt"), "safe");
        properties.setLocalServerRoot(serverRoot.toString());

        assertThat(serviceFor(projectRoot, "[\"[\"]", "[]").manifest(7L, 42L).files()).isEmpty();
        assertThat(serviceFor(projectRoot, "[\"**\"]", "[\"[\"]").manifest(7L, 42L).files()).isEmpty();
        assertThat(serviceFor(projectRoot, "{not-json", "[]").manifest(7L, 42L).files()).isEmpty();
        assertThat(serviceFor(projectRoot, "{\"not\":\"an-array\"}", "[]").manifest(7L, 42L).files()).isEmpty();
        assertThatThrownBy(() -> serviceFor(projectRoot, "[\"**\"]", "[\"[\"]")
                .readFile(7L, 42L, "notes.txt"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void validButMissingRelativeFileReturnsNotFound() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        properties.setLocalServerRoot(serverRoot.toString());

        assertThatThrownBy(() -> serviceFor(projectRoot, "[\"**\"]", "[]").readFile(7L, 42L, "missing.txt"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void projectCreationOnlyAuthorizesRelativeRootsBelowConfiguredRoot() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Files.createDirectories(serverRoot.resolve("study"));
        Path nestedProjectRoot = Files.createDirectories(serverRoot.resolve("team-a").resolve("study"));
        properties.setLocalServerRoot(serverRoot.toString());
        LocalServerProjectRootProvider provider = new LocalServerProjectRootProvider(properties);

        assertThat(provider.authorizeRelativeRoot("study")).isEqualTo(serverRoot.resolve("study").toRealPath());
        assertThat(provider.authorizeRelativeRoot("team-a/study")).isEqualTo(nestedProjectRoot.toRealPath());
        assertThatThrownBy(() -> provider.authorizeRelativeRoot("../outside"))
                .isInstanceOf(InvalidProjectPathException.class);
        assertThatThrownBy(() -> provider.authorizeRelativeRoot(serverRoot.toString()))
                .isInstanceOf(InvalidProjectPathException.class);
    }

    @Test
    void oversizedFilesAreUnavailableFromManifestSearchAndRead() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("large.txt"), "x".repeat(33));
        properties.setLocalServerRoot(serverRoot.toString());
        properties.setMaxFileBytes(32);
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        assertThat(service.manifest(7L, 42L).files()).isEmpty();
        assertThat(service.search(7L, 42L, "x", 10)).isEmpty();
        assertThatThrownBy(() -> service.readFile(7L, 42L, "large.txt"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void traversalBudgetsRejectIncompleteManifestAndSkipBlockedDirectoriesEarly() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Files.writeString(projectRoot.resolve("notes.txt"), "safe");
        Path git = Files.createDirectories(projectRoot.resolve(".git"));
        Files.writeString(git.resolve("one.txt"), "ignored");
        Files.writeString(git.resolve("two.txt"), "ignored");
        properties.setLocalServerRoot(serverRoot.toString());
        properties.setMaxFiles(1);
        ProjectService service = serviceFor(projectRoot, "[\"**\"]", "[]");

        assertThat(service.manifest(7L, 42L).files()).extracting(ProjectFileEntry::path).containsExactly("notes.txt");

        Files.writeString(projectRoot.resolve("second.txt"), "second");
        assertThatThrownBy(() -> service.manifest(7L, 42L))
                .isInstanceOf(ProjectTraversalLimitException.class);
        assertThatThrownBy(() -> service.search(7L, 42L, "safe", 10))
                .isInstanceOf(ProjectTraversalLimitException.class);
    }

    @Test
    void internalProvisioningBindsOnlyTrustedAbsoluteProjectFoldersWhenLocalModeIsEnabled() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        properties.setLocalServerRoot(serverRoot.toString());
        properties.setAllowLocalAbsoluteProjectFolders(true);
        LocalServerProjectRootProvider provider = new LocalServerProjectRootProvider(properties);
        ProjectProvisioningService provisioning = new ProjectProvisioningService(projects, provider, objectMapper);
        when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Project project = provisioning.provision(new ProjectProvisioningRequest(7L, "Study", projectRoot.toString(), List.of("**"), List.of()));
        assertThat(project.getRootPath()).isEqualTo(".");
        assertThat(project.getCanonicalRootPath()).isEqualTo(projectRoot.toRealPath().toString());
        assertThatThrownBy(() -> provisioning.provision(new ProjectProvisioningRequest(7L, "Study", "study", List.of("**"), List.of())))
                .isInstanceOf(InvalidProjectPathException.class);
        assertThatThrownBy(() -> provisioning.provision(new ProjectProvisioningRequest(7L, "Study", tempDir.resolve("missing").toString(), List.of("**"), List.of())))
                .isInstanceOf(InvalidProjectPathException.class);
        assertThatThrownBy(() -> provisioning.provision(new ProjectProvisioningRequest(7L, "Study", projectRoot.toString(), List.of(), List.of())))
                .isInstanceOf(InvalidProjectPathException.class);
        assertThatThrownBy(() -> provisioning.provision(new ProjectProvisioningRequest(7L, "Study", projectRoot.toString(), List.of("["), List.of())))
                .isInstanceOf(InvalidProjectPathException.class);
        assertThatThrownBy(() -> provisioning.provision(new ProjectProvisioningRequest(7L, "Study", projectRoot.toString(), List.of("**"), List.of("["))))
                .isInstanceOf(InvalidProjectPathException.class);
    }

    @Test
    void absoluteProjectFoldersFailClosedUntilControlledLocalModeIsExplicitlyEnabled() throws IOException {
        Path projectRoot = Files.createDirectories(tempDir.resolve("standalone-study"));
        LocalServerProjectRootProvider provider = new LocalServerProjectRootProvider(properties);

        assertThatThrownBy(() -> provider.authorizeAbsoluteProjectFolder(projectRoot.toString()))
                .isInstanceOf(InvalidProjectPathException.class);

        properties.setAllowLocalAbsoluteProjectFolders(true);
        assertThat(provider.authorizeAbsoluteProjectFolder(projectRoot.toString())).isEqualTo(projectRoot.toRealPath());
        assertThatThrownBy(() -> provider.authorizeAbsoluteProjectFolder("standalone-study"))
                .isInstanceOf(InvalidProjectPathException.class);
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertThatThrownBy(() -> provider.authorizeAbsoluteProjectFolder("\\\\server\\share\\study"))
                    .isInstanceOf(InvalidProjectPathException.class);
        }
        Path plainFile = Files.writeString(tempDir.resolve("not-a-project.txt"), "not a directory");
        assertThatThrownBy(() -> provider.authorizeAbsoluteProjectFolder(plainFile.toString()))
                .isInstanceOf(InvalidProjectPathException.class);
    }

    @Test
    void internalProvisioningRejectsSymbolicLinkRoot() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        properties.setLocalServerRoot(serverRoot.toString());
        properties.setAllowLocalAbsoluteProjectFolders(true);
        LocalServerProjectRootProvider provider = new LocalServerProjectRootProvider(properties);
        ProjectProvisioningService provisioning = new ProjectProvisioningService(projects, provider, objectMapper);
        Path linkedRoot = serverRoot.resolve("linked-study");
        createDirectoryLink(linkedRoot, projectRoot);
        assertThatThrownBy(() -> provisioning.provision(new ProjectProvisioningRequest(7L, "Study", linkedRoot.toString(), List.of("**"), List.of())))
                .isInstanceOf(InvalidProjectPathException.class);
    }

    @Test
    void rejectsConfiguredOrPersistedProjectRootAliases() throws IOException {
        Path serverRoot = Files.createDirectories(tempDir.resolve("server-root"));
        Path projectRoot = Files.createDirectories(serverRoot.resolve("study"));
        Path configuredRootAlias = createDirectoryLink(tempDir.resolve("configured-root-alias"), serverRoot);
        properties.setLocalServerRoot(configuredRootAlias.toString());
        LocalServerProjectRootProvider provider = new LocalServerProjectRootProvider(properties);

        assertThatThrownBy(() -> provider.authorizeRelativeRoot("study"))
                .isInstanceOf(InvalidProjectPathException.class);

        properties.setLocalServerRoot(serverRoot.toString());
        Path persistedRootAlias = createDirectoryLink(serverRoot.resolve("persisted-study"), projectRoot);
        Project project = new Project(7L, "Study", "study", persistedRootAlias.toString(), "[\"**\"]", "[]");
        assertThatThrownBy(() -> provider.resolve(project))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(403);
    }

    private Path createDirectoryLink(Path link, Path target) throws IOException {
        assertFixturePath(link);
        assertFixturePath(target);
        IOException symbolicLinkFailure;
        try {
            Files.createSymbolicLink(link, target);
            verifyDirectoryLink(link, target);
            fixtureLinks.add(link);
            return link;
        } catch (UnsupportedOperationException ex) {
            symbolicLinkFailure = new IOException("Files.createSymbolicLink is unsupported", ex);
        } catch (IOException ex) {
            symbolicLinkFailure = ex;
        }

        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Assumptions.abort("Directory symbolic-link fixture is unavailable: " + describe(symbolicLinkFailure));
        }

        try {
            createWindowsJunction(link, target);
            verifyDirectoryLink(link, target);
            fixtureLinks.add(link);
            return link;
        } catch (IOException junctionFailure) {
            deleteFixtureLink(link);
            Assumptions.abort("Directory link fixture is unavailable. Files.createSymbolicLink: "
                    + describe(symbolicLinkFailure) + "; Windows junction fallback: " + describe(junctionFailure));
            throw junctionFailure;
        }
    }

    private void createWindowsJunction(Path link, Path target) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "New-Item -ItemType Junction -Path '" + powerShellLiteral(link) + "' -Target '"
                            + powerShellLiteral(target) + "' -ErrorAction Stop | Out-Null")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("PowerShell New-Item -ItemType Junction exited " + exitCode + ": " + output);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while creating Windows junction", ex);
        }
    }

    private void verifyDirectoryLink(Path link, Path target) throws IOException {
        boolean reparsePoint = Files.isSymbolicLink(link) || isWindowsReparsePoint(link);
        if (!reparsePoint || !Files.isDirectory(link) || !Files.isSameFile(link, target)) {
            throw new IOException("Fixture is not a directory symbolic link or reparse point targeting the requested directory: " + link);
        }
    }

    private boolean isWindowsReparsePoint(Path path) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "if (((Get-Item -Force -LiteralPath '" + powerShellLiteral(path)
                            + "').Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { exit 0 } else { exit 1 }")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            if (exitCode == 1) {
                return false;
            }
            throw new IOException("PowerShell reparse-point verification exited " + exitCode + ": " + output);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while verifying Windows reparse point", ex);
        }
    }

    private void deleteFixtureLink(Path link) throws IOException {
        assertFixturePath(link);
        Files.deleteIfExists(link);
    }

    private void assertFixturePath(Path path) throws IOException {
        Path fixtureRoot = tempDir.toRealPath();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(fixtureRoot)) {
            throw new IOException("Test fixture path must stay below the temporary directory: " + path);
        }
    }

    private static String powerShellLiteral(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }

    private static String describe(Exception exception) {
        return exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    private static byte[] ooxml(String part) throws IOException {
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

    private static ProjectObjectEntry objectEntry(
            String path, byte[] content) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(content));
        return new ProjectObjectEntry(
                path, content.length, java.time.Instant.EPOCH, hash);
    }

    private ProjectService serviceFor(Path projectRoot, String includeRules, String ignoreRules) throws IOException {
        Project project = new Project(7L, "Study", "study", projectRoot.toRealPath().toString(), includeRules, ignoreRules);
        when(projects.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(project));
        when(projects.findByIdAndUserId(42L, 8L)).thenReturn(Optional.empty());
        LocalServerProjectRootProvider provider = new LocalServerProjectRootProvider(properties);
        return new ProjectService(projects, List.of(provider), properties, objectMapper);
    }
}
