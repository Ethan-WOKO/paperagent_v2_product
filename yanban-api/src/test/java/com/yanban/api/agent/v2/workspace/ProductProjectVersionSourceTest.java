package com.yanban.api.agent.v2.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.project.ProjectService.SandboxWorkspaceMaterialization;
import com.yanban.core.agent.sandbox.SandboxFileSnapshot;
import com.yanban.core.agent.sandbox.SandboxWorkspaceRef;
import com.yanban.core.agent.sandbox.SandboxWorkspaceSnapshot;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectManifestIdentity;
import com.yanban.core.research.ProjectRelativePath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductProjectVersionSourceTest {

    private static final long USER_ID = 7L;
    private static final long PROJECT_ID = 42L;
    private static final String A_PATH = "a/notes.md";
    private static final String Z_PATH = "z-paper.tex";
    private static final String A_CONTENT = "alpha 学术";
    private static final String Z_CONTENT = "\\section{结论}";

    private final ProjectService projects = mock(ProjectService.class);

    @Test
    void mapsExactCutCanonicallyAndDefensivelyWithoutCaching() {
        Fixture fixture = fixture();
        doReturn(fixture.manifest()).when(projects).manifest(USER_ID, PROJECT_ID);
        when(projects.materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH)))
                .thenReturn(fixture.materialized());
        ProductProjectVersionSource source = source(fixture.version());

        ProjectVersionSnapshot first = source.load(reference(fixture.version()));
        ProjectVersionSnapshot second = source.load(reference(fixture.version()));

        assertThat(first.version()).isEqualTo(reference(fixture.version()));
        assertThat(first.files()).extracting(file -> file.path().value())
                .containsExactly(A_PATH, Z_PATH);
        assertThat(first.files()).extracting(file -> file.hash().algorithm())
                .containsOnly("sha256");
        assertThat(first.files()).extracting(file -> file.hash().value())
                .containsExactly(sha256(A_CONTENT), sha256(Z_CONTENT));
        assertThat(first.files().get(0).content()).isEqualTo(A_CONTENT.getBytes(StandardCharsets.UTF_8));
        byte[] changed = first.files().get(0).content();
        changed[0] = 0;
        assertThat(first.files().get(0).content()).isEqualTo(A_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertThat(first.metadata()).isEmpty();
        assertThat(first.files()).allSatisfy(file -> assertThat(file.metadata()).isEmpty());
        assertThat(second.files()).hasSize(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> paths = ArgumentCaptor.forClass(Set.class);
        verify(projects, times(2)).manifest(USER_ID, PROJECT_ID);
        verify(projects, times(2)).materializeSandbox(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(PROJECT_ID),
                paths.capture());
        assertThat(paths.getAllValues()).allSatisfy(value -> assertThat(value).containsExactly(A_PATH, Z_PATH));
    }

    @Test
    void rejectsNullOrChangedReferenceBeforeAnyProjectCall() {
        Fixture fixture = fixture();
        ProductProjectVersionSource source = source(fixture.version());

        assertWorkspaceFailure(
                () -> source.load(null),
                WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH);
        assertWorkspaceFailure(
                () -> source.load(new ProjectVersionRef("99", fixture.version())),
                WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH);
        assertWorkspaceFailure(
                () -> source.load(new ProjectVersionRef(String.valueOf(PROJECT_ID), "other-version")),
                WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH);

        verify(projects, never()).manifest(USER_ID, PROJECT_ID);
        verify(projects, never()).materializeSandbox(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anySet());
    }

    @Test
    void rejectsCrossBoundOrMalformedManifestBeforeMaterialization() {
        Fixture fixture = fixture();
        ProductProjectVersionSource source = source(fixture.version());
        ProjectManifestResponse wrongProject =
                new ProjectManifestResponse(99L, fixture.version(), fixture.manifest().files());
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(wrongProject);

        assertSourceFailure(() -> source.load(reference(fixture.version())));

        verify(projects, never()).materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH));
    }

    @Test
    void rejectsDuplicatePathAndNonLowercaseHashBeforeMaterialization() {
        Fixture fixture = fixture();
        ProjectFileEntry first = fixture.manifest().files().get(0);
        ProjectManifestResponse duplicate = new ProjectManifestResponse(
                PROJECT_ID, fixture.version(), List.of(first, first));
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(duplicate);
        ProductProjectVersionSource source = source(fixture.version());

        assertSourceFailure(() -> source.load(reference(fixture.version())));

        ProjectFileEntry uppercase = new ProjectFileEntry(
                first.path(), first.sizeBytes(), first.modifiedAt(), first.sha256().toUpperCase());
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(
                new ProjectManifestResponse(PROJECT_ID, fixture.version(), List.of(uppercase)));
        assertSourceFailure(() -> source.load(reference(fixture.version())));
        verify(projects, never()).materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH));
    }

    @Test
    void rejectsMissingOrExtraTextContentWithoutExposingIt() {
        Fixture fixture = fixture();
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(fixture.manifest());
        ProductProjectVersionSource source = source(fixture.version());
        when(projects.materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH)))
                .thenReturn(new SandboxWorkspaceMaterialization(
                        fixture.materialized().snapshot(),
                        Map.of(A_PATH, A_CONTENT)));

        WorkspaceException missing = captureSourceFailure(
                () -> source.load(reference(fixture.version())));
        assertThat(missing.getMessage()).doesNotContain(A_CONTENT).doesNotContain(Z_CONTENT);

        Map<String, String> extra = new LinkedHashMap<>(fixture.materialized().textFiles());
        extra.put("extra.txt", "unrelated-extra-content");
        when(projects.materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH)))
                .thenReturn(new SandboxWorkspaceMaterialization(
                        fixture.materialized().snapshot(),
                        extra));
        WorkspaceException extraFailure = captureSourceFailure(
                () -> source.load(reference(fixture.version())));
        assertThat(extraFailure.getMessage()).doesNotContain("unrelated-extra-content");
    }

    @Test
    void rejectsSizeHashAndSnapshotCutMismatch() {
        Fixture fixture = fixture();
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(fixture.manifest());
        ProductProjectVersionSource source = source(fixture.version());

        Map<String, String> corruptContent = new LinkedHashMap<>(fixture.materialized().textFiles());
        corruptContent.put(A_PATH, "changed");
        when(projects.materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH)))
                .thenReturn(new SandboxWorkspaceMaterialization(
                        fixture.materialized().snapshot(),
                        corruptContent));
        assertSourceFailure(() -> source.load(reference(fixture.version())));

        SandboxWorkspaceSnapshot crossCut = mock(SandboxWorkspaceSnapshot.class);
        when(crossCut.workspace()).thenReturn(
                new SandboxWorkspaceRef(99L, new com.yanban.core.research.ProjectVersionRef(fixture.version())));
        when(crossCut.files()).thenReturn(fixture.materialized().snapshot().files());
        when(projects.materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH)))
                .thenReturn(new SandboxWorkspaceMaterialization(
                        crossCut,
                        fixture.materialized().textFiles()));
        assertSourceFailure(() -> source.load(reference(fixture.version())));
    }

    @Test
    void propagatesManifestAndMaterializationFailuresUnchangedWithoutRetry() {
        Fixture fixture = fixture();
        ProductProjectVersionSource source = source(fixture.version());
        RuntimeException manifestFailure = new IllegalStateException("product storage unavailable");
        when(projects.manifest(USER_ID, PROJECT_ID)).thenThrow(manifestFailure);

        assertThatThrownBy(() -> source.load(reference(fixture.version()))).isSameAs(manifestFailure);
        verify(projects).manifest(USER_ID, PROJECT_ID);

        RuntimeException materializationFailure = new IllegalArgumentException("authorization changed");
        doReturn(fixture.manifest()).when(projects).manifest(USER_ID, PROJECT_ID);
        when(projects.materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH)))
                .thenThrow(materializationFailure);

        assertThatThrownBy(() -> source.load(reference(fixture.version())))
                .isSameAs(materializationFailure);
        verify(projects).materializeSandbox(USER_ID, PROJECT_ID, Set.of(A_PATH, Z_PATH));
    }

    private ProductProjectVersionSource source(String version) {
        return new ProductProjectVersionSource(USER_ID, PROJECT_ID, version, projects);
    }

    private static ProjectVersionRef reference(String version) {
        return new ProjectVersionRef(String.valueOf(PROJECT_ID), version);
    }

    private static Fixture fixture() {
        ProjectFileEntry z = entry(Z_PATH, Z_CONTENT);
        ProjectFileEntry a = entry(A_PATH, A_CONTENT);
        List<SandboxFileSnapshot> snapshots = List.of(snapshot(z), snapshot(a));
        String version = ProjectManifestIdentity.derive(snapshots.stream()
                .map(file -> new ProjectManifestIdentity.Entry(
                        file.relativePath(), file.fileHash(), file.sizeBytes()))
                .toList()).value();
        ProjectManifestResponse manifest = new ProjectManifestResponse(PROJECT_ID, version, List.of(z, a));
        SandboxWorkspaceSnapshot snapshot = new SandboxWorkspaceSnapshot(
                new SandboxWorkspaceRef(
                        PROJECT_ID,
                        new com.yanban.core.research.ProjectVersionRef(version)),
                snapshots);
        return new Fixture(
                version,
                manifest,
                new SandboxWorkspaceMaterialization(
                        snapshot,
                        Map.of(Z_PATH, Z_CONTENT, A_PATH, A_CONTENT)));
    }

    private static ProjectFileEntry entry(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ProjectFileEntry(path, bytes.length, Instant.EPOCH, sha256(content));
    }

    private static SandboxFileSnapshot snapshot(ProjectFileEntry file) {
        return new SandboxFileSnapshot(
                new ProjectRelativePath(file.path()),
                new FileHash(file.sha256()),
                file.sizeBytes());
    }

    private static String sha256(String content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertSourceFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertWorkspaceFailure(call, WorkspaceErrorCode.SOURCE_FAILURE);
    }

    private static WorkspaceException captureSourceFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        try {
            call.call();
            throw new AssertionError("Expected WorkspaceException");
        } catch (WorkspaceException failure) {
            assertThat(failure.code()).isEqualTo(WorkspaceErrorCode.SOURCE_FAILURE);
            assertThat(failure.operation()).isEqualTo("loadProjectVersion");
            return failure;
        } catch (Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertWorkspaceFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            WorkspaceErrorCode code
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        WorkspaceException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(code);
                            assertThat(failure.operation()).isEqualTo("loadProjectVersion");
                            assertThat(failure.projectPath()).isEmpty();
                        });
    }

    private record Fixture(
            String version,
            ProjectManifestResponse manifest,
            SandboxWorkspaceMaterialization materialized
    ) {
    }
}
