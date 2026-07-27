package com.yanban.api.agent.v2.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paperagent.v2.contracts.ContentHash;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    void repeatedCallsShareRootButKeepSourcesIndependentlyBound() {
        Path root = temp.resolve("shared-root");
        ProjectVersionRef firstVersion = VERSION;
        ProjectVersionRef secondVersion =
                new ProjectVersionRef("84", "b".repeat(64));
        AtomicInteger firstLoads = new AtomicInteger();
        AtomicInteger secondLoads = new AtomicInteger();
        AuthenticatedAgentTurnProjectVersionSourceFactory sources =
                mock(AuthenticatedAgentTurnProjectVersionSourceFactory.class);
        when(sources.create(USER_ID, TURN_ID))
                .thenReturn(
                        source(firstVersion, firstLoads),
                        source(secondVersion, secondLoads));
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, root.toString());

        WorkspacePort first = factory.create(USER_ID, TURN_ID);
        WorkspacePort second = factory.create(USER_ID, TURN_ID);
        first.materialize(spec("bound-first", firstVersion));
        second.materialize(spec("bound-second", secondVersion));

        assertThat(firstLoads).hasValue(1);
        assertThat(secondLoads).hasValue(1);
        verify(sources, times(2)).create(USER_ID, TURN_ID);
        assertThatThrownBy(() -> first.materialize(spec("wrong-source", secondVersion)))
                .isInstanceOfSatisfying(
                        WorkspaceException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH));
        assertThat(firstLoads).hasValue(2);
        assertThat(secondLoads).hasValue(1);
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
        AuthenticatedAgentTurnWorkspacePortFactory factory =
                new AuthenticatedAgentTurnWorkspacePortFactory(sources, root.toString());
        WorkspaceMaterializationSpec exact = spec("restart-exact", VERSION);

        var published = factory.create(USER_ID, TURN_ID).materialize(exact);
        WorkspacePort recreated = factory.create(USER_ID, TURN_ID);
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
        return requested -> {
            loads.incrementAndGet();
            if (!exactVersion.equals(requested)) {
                throw new WorkspaceException(
                        WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH,
                        "loadProjectVersion");
            }
            byte[] content = "paper".getBytes(StandardCharsets.UTF_8);
            return new ProjectVersionSnapshot(
                    exactVersion,
                    List.of(new ProjectFileSnapshot(
                            new ProjectPath("paper.txt"),
                            content,
                            new ContentHash("sha256", sha256(content)),
                            Map.of())),
                    Map.of());
        };
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
