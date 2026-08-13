package com.yanban.api.agent.v2.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.chain.effect.ChainActionWorkspaceAuthority;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSource;
import io.paperagent.v2.workspace.WorkspaceErrorCode;
import io.paperagent.v2.workspace.WorkspaceException;
import io.paperagent.v2.workspace.WorkspacePort;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthenticatedAgentTurnWorkspacePortFactoryTest {

    private static final long USER_ID = 7L;
    private static final long TURN_ID = 31L;
    private static final ProjectVersionRef VERSION =
            new ProjectVersionRef("42", "a".repeat(64));
    private static final WorkspaceMaterializationLimits LIMITS =
            new WorkspaceMaterializationLimits(1024, 4096, 8);

    @TempDir
    Path temp;

    @Test
    void resolvesDocumentedDefaultAndRelativeAndOverriddenRootsWithoutExposure()
            throws Exception {
        String application;
        try (InputStream stream = getClass().getResourceAsStream("/application.yml")) {
            assertThat(stream).isNotNull();
            application = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(application).contains(
                "root: ${YANBAN_AGENT_V2_WORKSPACE_ROOT:data/agent-v2-workspaces}");

        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        when(sources.create(USER_ID, TURN_ID)).thenReturn(source(VERSION, new AtomicInteger()));
        Path expectedRelativeRoot = temp.resolve("relative-root").toAbsolutePath().normalize();
        Path relative = Path.of("").toAbsolutePath().normalize().relativize(expectedRelativeRoot);

        WorkspacePort relativePort =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, relative.toString())
                        .create(USER_ID, TURN_ID);
        Path overriddenRoot = temp.resolve("overridden-root");
        WorkspacePort overriddenPort =
                new AuthenticatedAgentTurnWorkspacePortFactory(
                        sources,
                        overriddenRoot.toString())
                        .create(USER_ID, TURN_ID);

        assertThat(relativePort).isNotNull();
        assertThat(overriddenPort).isNotNull();
        assertThat(expectedRelativeRoot).isDirectory();
        assertThat(overriddenRoot).isDirectory();
        assertThat(relativePort.toString()).doesNotContain(expectedRelativeRoot.toString());
        assertThat(overriddenPort.toString()).doesNotContain(overriddenRoot.toString());
    }

    @Test
    void bindsAuthenticatedSourceBeforeRootInitializationAndDoesNotLoadIt()
            throws Exception {
        Path root = temp.resolve("bind-before-root");
        AtomicInteger sourceLoads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        when(sources.create(USER_ID, TURN_ID)).thenAnswer(invocation -> {
            assertThat(root).doesNotExist();
            return source(VERSION, sourceLoads);
        });

        WorkspacePort port =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, root.toString())
                        .create(USER_ID, TURN_ID);

        assertThat(port).isNotNull();
        assertThat(root).isDirectory();
        assertThat(sourceLoads).hasValue(0);
        verify(sources).create(USER_ID, TURN_ID);
    }

    @Test
    void exposesOnlyWorkspacePortAndPropagatesBindingFailureBeforeFilesystemEffects()
            throws Exception {
        assertThat(AuthenticatedAgentTurnWorkspacePortFactory.class
                .getMethod("create", Long.class, Long.class)
                .getReturnType()).isEqualTo(WorkspacePort.class);

        Path root = temp.resolve("must-stay-absent");
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        RuntimeException rejection = new IllegalStateException("owner-qualified rejection");
        when(sources.create(USER_ID, TURN_ID)).thenThrow(rejection);
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, root.toString());

        assertThatThrownBy(() -> factory.create(USER_ID, TURN_ID)).isSameAs(rejection);
        assertThat(root).doesNotExist();
    }

    @Test
    void sanitizesInvalidAndUnusableRootFailures() throws Exception {
        assertThatThrownBy(() ->
                new AuthenticatedAgentTurnWorkspacePortFactory(
                        mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class),
                        " "))
                .isInstanceOfSatisfying(
                        AuthenticatedAgentTurnWorkspaceConfigurationException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(
                                    AuthenticatedAgentTurnWorkspaceConfigurationException.Code.INVALID_ROOT);
                            assertThat(failure.getMessage()).doesNotContain(temp.toString());
                            assertThat(failure.getCause()).isNull();
                        });

        Path unusable = temp.resolve("configured-private-root");
        Files.writeString(unusable, "not a directory");
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        when(sources.create(USER_ID, TURN_ID)).thenReturn(source(VERSION, new AtomicInteger()));
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, unusable.toString());

        assertThatThrownBy(() -> factory.create(USER_ID, TURN_ID))
                .isInstanceOfSatisfying(
                        AuthenticatedAgentTurnWorkspaceConfigurationException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(
                                    AuthenticatedAgentTurnWorkspaceConfigurationException.Code.INVALID_ROOT);
                            assertThat(failure.getMessage())
                                    .doesNotContain(unusable.toString())
                                    .doesNotContain("configured-private-root");
                            assertThat(failure.getCause()).isNull();
                        });
        verify(sources).create(USER_ID, TURN_ID);
    }

    @Test
    void repeatedCallsReuseTurnBoundProviderAndPreserveWorkspaceMutations() {
        Path root = temp.resolve("shared-root");
        AtomicInteger loads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = context();
        when(sources.create(context)).thenReturn(source(VERSION, loads));
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, root.toString());

        WorkspacePort first = factory.create(context);
        WorkspacePort second = factory.create(USER_ID, TURN_ID);
        var materialized = first.materialize(spec("bound-turn", VERSION));
        byte[] replacement = "changed in isolated workspace"
                .getBytes(StandardCharsets.UTF_8);
        first.replace(materialized.workspace(),
                new ProjectPath("paper.txt"), replacement);

        assertThat(second).isSameAs(first);
        assertThat(second.inspectMaterialization(
                spec("bound-turn", VERSION))).isEqualTo(materialized);
        assertThat(second.read(materialized.workspace(),
                new ProjectPath("paper.txt"))).isEqualTo(replacement);
        assertThat(loads).hasValue(1);
        verify(sources).create(context);
        verify(sources, never()).create(USER_ID, TURN_ID);
    }

    @Test
    void chainWorkspaceEnforcesExactReadWriteAndDiffScopes()
            throws Exception {
        Path root = temp.resolve("chain-scope-root");
        AtomicInteger loads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = context();
        when(sources.create(context)).thenReturn(source(
                VERSION, loads, Map.of(
                        "read.txt", "read",
                        "write.txt", "write",
                        "hidden.txt", "hidden")));
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(
                        sources, root.toString());
        ChainActionWorkspaceAuthority authority = chainAuthority(
                "scope-action", "NONE", null, Map.of(),
                List.of("read.txt"),
                List.of("write.txt", "new.txt", "moved.txt"));

        WorkspacePort workspace = factory.createChain(context, authority);
        assertThatThrownBy(() -> workspace.materialize(
                spec("another-workspace", VERSION)))
                .isInstanceOfSatisfying(
                        WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT));
        assertThat(loads).hasValue(0);
        var materialized = workspace.materialize(
                spec("chain-base", VERSION));
        var ref = materialized.workspace();

        assertThat(workspace.list(ref)).extracting(stat ->
                stat.path().value()).containsExactlyInAnyOrder(
                "read.txt", "write.txt");
        assertThat(new String(workspace.read(
                ref, new ProjectPath("read.txt")), StandardCharsets.UTF_8))
                .isEqualTo("read");
        assertThat(new String(workspace.read(
                ref, new ProjectPath("write.txt")), StandardCharsets.UTF_8))
                .isEqualTo("write");
        assertThat(workspace.stat(ref, new ProjectPath("write.txt")).path())
                .isEqualTo(new ProjectPath("write.txt"));
        assertPathEscape(() -> workspace.read(
                ref, new ProjectPath("hidden.txt")));
        assertPathEscape(() -> workspace.stat(
                ref, new ProjectPath("hidden.txt")));
        assertPathEscape(() -> workspace.replace(
                ref, new ProjectPath("read.txt"), bytes("changed")));
        assertPathEscape(() -> workspace.create(
                ref, new ProjectPath("hidden.txt"), bytes("changed")));
        assertPathEscape(() -> workspace.delete(
                ref, new ProjectPath("hidden.txt")));
        assertPathEscape(() -> workspace.move(
                ref, new ProjectPath("write.txt"),
                new ProjectPath("hidden.txt")));

        workspace.replace(
                ref, new ProjectPath("write.txt"), bytes("changed"));
        workspace.create(ref, new ProjectPath("new.txt"), bytes("new"));
        workspace.move(ref, new ProjectPath("new.txt"),
                new ProjectPath("moved.txt"));
        workspace.delete(ref, new ProjectPath("moved.txt"));
        var diff = workspace.diff(
                ref, new DiffId("chain-scope-diff"), Instant.EPOCH);

        assertThat(diff.entries()).extracting(entry ->
                entry.path().value()).containsExactly("write.txt");
        Path hidden;
        try (var files = Files.walk(root)) {
            hidden = files.filter(path -> path.getFileName().toString()
                            .equals("hidden.txt")
                            && path.getParent().getFileName().toString()
                            .equals("data"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(hidden, "out-of-band change");
        assertPathEscape(() -> workspace.diff(
                ref, new DiffId("chain-scope-rejected-diff"),
                Instant.EPOCH));
        assertThat(workspace.list(ref)).extracting(stat ->
                stat.path().value()).containsExactlyInAnyOrder(
                "read.txt", "write.txt");
        assertThat(loads).hasValue(1);
        workspace.cleanup(ref);
    }

    @Test
    void chainWorkspaceRebuildsEachActionFromItsFormalBaseCandidate() {
        Path root = temp.resolve("chain-rebase-root");
        AtomicInteger loads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = context();
        when(sources.create(context)).thenReturn(source(
                VERSION, loads, Map.of("paper.txt", "project")));
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(
                        sources, root.toString());
        WorkspacePort first = factory.createChain(
                context, chainAuthority(
                        "action-1", "NONE", null, Map.of(),
                        List.of("paper.txt"), List.of("paper.txt")));
        var firstMaterialized = first.materialize(spec("chain-base", VERSION));
        first.replace(firstMaterialized.workspace(),
                new ProjectPath("paper.txt"), bytes("unbound mutation"));

        WorkspacePort second = factory.createChain(
                context, chainAuthority(
                        "action-2", "b".repeat(64), 51L,
                        Map.of("paper.txt", "formal base Candidate"),
                        List.of("paper.txt"), List.of("paper.txt")));
        var secondMaterialized = second.materialize(
                spec("chain-base", VERSION));

        assertThat(second).isNotSameAs(first);
        assertThat(new String(second.read(
                secondMaterialized.workspace(),
                new ProjectPath("paper.txt")), StandardCharsets.UTF_8))
                .isEqualTo("formal base Candidate");
        assertThat(loads).hasValue(2);
        first.cleanup(firstMaterialized.workspace());
        second.cleanup(secondMaterialized.workspace());
    }

    @Test
    void chainWorkspaceAppliesMixedTypedOverlay() {
        Path root = temp.resolve("chain-mixed-root");
        AtomicInteger loads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = context();
        when(sources.create(context)).thenReturn(source(
                VERSION, loads, Map.of(
                        "old.txt", "old",
                        "gone.txt", "gone",
                        "keep.txt", "keep")));
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(
                        sources, root.toString());
        List<ChainActionWorkspaceAuthority.TypedChange> changes = List.of(
                typedChange(
                        ChainActionWorkspaceAuthority.ChangeType.ADD,
                        "new.txt", null, "new"),
                typedChange(
                        ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                        "old.txt", sha256(bytes("old")), "changed"),
                typedChange(
                        ChainActionWorkspaceAuthority.ChangeType.DELETE,
                        "gone.txt", sha256(bytes("gone")), null));
        WorkspacePort workspace = factory.createChain(
                context, chainAuthority(
                        "mixed-action", "c".repeat(64), 61L, changes,
                        List.of("new.txt", "old.txt", "keep.txt"),
                        List.of("new.txt", "old.txt", "gone.txt")));

        var materialized = workspace.materialize(spec("chain-base", VERSION));
        var ref = materialized.workspace();
        assertThat(workspace.list(ref)).extracting(stat -> stat.path().value())
                .containsExactlyInAnyOrder("new.txt", "old.txt", "keep.txt");
        assertThat(new String(workspace.read(
                ref, new ProjectPath("new.txt")), StandardCharsets.UTF_8))
                .isEqualTo("new");
        assertThat(new String(workspace.read(
                ref, new ProjectPath("old.txt")), StandardCharsets.UTF_8))
                .isEqualTo("changed");
        assertThat(loads).hasValue(1);
        workspace.cleanup(ref);
    }

    @Test
    void typedOverlayRejectsExistenceHashCaseAndScopeDrift() {
        assertOverlayRejected(
                Map.of("new.txt", "existing"),
                List.of(typedChange(
                        ChainActionWorkspaceAuthority.ChangeType.ADD,
                        "new.txt", null, "new")),
                List.of("new.txt"), List.of("new.txt"));
        assertOverlayRejected(
                Map.of("old.txt", "actual"),
                List.of(typedChange(
                        ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                        "old.txt", sha256(bytes("different")), "changed")),
                List.of("old.txt"), List.of("old.txt"));
        assertOverlayRejected(
                Map.of(),
                List.of(typedChange(
                        ChainActionWorkspaceAuthority.ChangeType.DELETE,
                        "gone.txt", sha256(bytes("gone")), null)),
                List.of("kept.txt"), List.of("gone.txt"));
        assertOverlayRejected(
                Map.of("A.txt", "one", "a.TXT", "two"),
                List.of(), List.of("A.txt"), List.of());
        assertOverlayRejected(
                Map.of("present.txt", "present"),
                List.of(), List.of("missing.txt"), List.of());
        assertOverlayRejected(
                Map.of("one/name.txt", "one", "two/name.txt", "two"),
                List.of(), List.of("one/name.txt"), List.of("name.txt"));
        assertOverlayRejected(
                Map.of("folder/name.txt", "one"),
                List.of(), List.of("folder/name.txt"), List.of("NAME.txt"));
    }

    private void assertOverlayRejected(
            Map<String, String> files,
            List<ChainActionWorkspaceAuthority.TypedChange> changes,
            List<String> readScopes,
            List<String> writeScopes) {
        Path root = temp.resolve("rejected-" + UUID.randomUUID());
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        VerifiedAgentTurnProductContext context = context();
        when(sources.create(context)).thenReturn(source(
                VERSION, new AtomicInteger(), files));
        var factory = new AuthenticatedAgentTurnWorkspacePortFactory(
                sources, root.toString());
        WorkspacePort workspace = factory.createChain(
                context, chainAuthority(
                        "rejected-action", changes.isEmpty()
                                ? "NONE" : "c".repeat(64),
                        changes.isEmpty() ? null : 71L,
                        changes, readScopes, writeScopes));

        assertThatThrownBy(() -> workspace.materialize(
                spec("chain-base", VERSION)))
                .isInstanceOfSatisfying(
                        WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                WorkspaceErrorCode.SOURCE_FAILURE));
    }

    @Test
    void chainAuthorityRejectsEmptyNonCanonicalAndDuplicateScopes() {
        assertThatThrownBy(() -> chainAuthority(
                "empty", "NONE", null, Map.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chainAuthority(
                "traversal", "NONE", null, Map.of(),
                List.of("../secret.txt"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chainAuthority(
                "non-canonical", "NONE", null, Map.of(),
                List.of("paper/./main.txt"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chainAuthority(
                "duplicate", "NONE", null, Map.of(),
                List.of("paper.txt", "paper.txt"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chainAuthority(
                "invalid-overlay", "c".repeat(64), 52L,
                Map.of("paper/../secret.txt", "invalid"),
                List.of("paper.txt"), List.of("paper.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recreatedProviderAdoptsExactPublishedWorkspaceAndRejectsChangedReference() {
        Path root = temp.resolve("restart-root");
        AtomicInteger firstLoads = new AtomicInteger();
        AtomicInteger recoveredLoads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        when(sources.create(USER_ID, TURN_ID))
                .thenReturn(
                        source(VERSION, firstLoads),
                        source(VERSION, recoveredLoads));
        AuthenticatedAgentTurnWorkspacePortFactory firstFactory =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, root.toString());
        WorkspaceMaterializationSpec exact = spec("restart-exact", VERSION);

        var published = firstFactory.create(USER_ID, TURN_ID).materialize(exact);
        AuthenticatedAgentTurnWorkspacePortFactory restartedFactory =
                new AuthenticatedAgentTurnWorkspacePortFactory(
                        sources, root.toString());
        WorkspacePort recreated = restartedFactory.create(USER_ID, TURN_ID);
        var adopted = recreated.inspectMaterialization(exact);

        assertThat(adopted).isEqualTo(published);
        assertThat(firstLoads).hasValue(1);
        assertThat(recoveredLoads).hasValue(1);
        assertThatThrownBy(() -> recreated.inspectMaterialization(
                spec(
                        "restart-exact",
                        new ProjectVersionRef(VERSION.projectId(), "c".repeat(64)))))
                .isInstanceOfSatisfying(
                        WorkspaceException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(WorkspaceErrorCode.WORKSPACE_SPEC_CONFLICT));
        assertThat(recoveredLoads).hasValue(1);
        verify(sources, times(2)).create(USER_ID, TURN_ID);
    }

    private static ProjectVersionSource source(
            ProjectVersionRef exactVersion,
            AtomicInteger loads
    ) {
        return source(
                exactVersion, loads, Map.of("paper.txt", "paper"));
    }

    private static ProjectVersionSource source(
            ProjectVersionRef exactVersion,
            AtomicInteger loads,
            Map<String, String> files
    ) {
        return requested -> {
            loads.incrementAndGet();
            if (!exactVersion.equals(requested)) {
                throw new WorkspaceException(
                        WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH,
                        "loadProjectVersion");
            }
            return new ProjectVersionSnapshot(
                    exactVersion,
                    files.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> file(
                                    entry.getKey(), entry.getValue()))
                            .toList(),
                    Map.of());
        };
    }

    private static ProjectFileSnapshot file(String path, String content) {
        byte[] bytes = bytes(content);
        return new ProjectFileSnapshot(
                new ProjectPath(path), bytes,
                new ContentHash("sha256", sha256(bytes)), Map.of());
    }

    private static ChainActionWorkspaceAuthority chainAuthority(
            String actionId,
            String candidateIdentity,
            Long artifactId,
            Map<String, String> overlay,
            List<String> readScopes,
            List<String> writeScopes) {
        List<ChainActionWorkspaceAuthority.TypedChange> changes = overlay
                .entrySet().stream().map(entry -> {
                    String base = sha256(bytes("project"));
                    String result = sha256(bytes(entry.getValue()));
                    return new ChainActionWorkspaceAuthority.TypedChange(
                            ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                            entry.getKey(), base, result, entry.getValue());
                }).toList();
        return new ChainActionWorkspaceAuthority(
                actionId, "f".repeat(64), "chain-base",
                readScopes, writeScopes,
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        candidateIdentity, VERSION.versionId(), artifactId,
                        changes));
    }

    private static ChainActionWorkspaceAuthority chainAuthority(
            String actionId,
            String candidateIdentity,
            Long artifactId,
            List<ChainActionWorkspaceAuthority.TypedChange> changes,
            List<String> readScopes,
            List<String> writeScopes) {
        return new ChainActionWorkspaceAuthority(
                actionId, "f".repeat(64), "chain-base",
                readScopes, writeScopes,
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        candidateIdentity, VERSION.versionId(), artifactId,
                        changes));
    }

    private static ChainActionWorkspaceAuthority.TypedChange typedChange(
            ChainActionWorkspaceAuthority.ChangeType type,
            String path,
            String baseSha256,
            String text) {
        return new ChainActionWorkspaceAuthority.TypedChange(
                type, path, baseSha256,
                text == null ? null : sha256(bytes(text)), text);
    }

    private static void assertPathEscape(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(
                WorkspaceException.class,
                failure -> assertThat(failure.code()).isEqualTo(
                        WorkspaceErrorCode.PATH_ESCAPE));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static VerifiedAgentTurnProductContext context() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN",
                        String.valueOf(TURN_ID),
                        USER_ID,
                        11L,
                        42L),
                Optional.of(VERSION.versionId()));
    }

    private static WorkspaceMaterializationSpec spec(
            String workspaceId,
            ProjectVersionRef version
    ) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId(workspaceId),
                version,
                LIMITS);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
