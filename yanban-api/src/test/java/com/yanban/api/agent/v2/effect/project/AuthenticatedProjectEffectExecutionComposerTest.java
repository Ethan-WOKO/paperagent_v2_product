package com.yanban.api.agent.v2.effect.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticatedProjectEffectExecutionComposerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuthenticatedProjectEffectExecutionComposer composer =
            new AuthenticatedProjectEffectExecutionComposer(
                    mock(com.yanban.api.agent.v2
                            .AgentTurnProductContextResolver.class),
                    mock(com.yanban.agent.v2.adapter.bootstrap
                            .ProductPlanIdDerivation.class),
                    mock(io.paperagent.v2.runtime.execution.recovery.composition
                            .StepRecoverer.class),
                    mock(io.paperagent.v2.persistence
                            .EffectIntentRepository.class),
                    mock(com.yanban.api.agent.v2.persistence
                            .ProductEffectExecutionClaimRepository.class),
                    mock(io.paperagent.v2.persistence
                            .PlanExecutionContextRepository.class),
                    mock(com.yanban.api.agent.v2.workspace
                            .AuthenticatedAgentTurnWorkspacePortFactory.class),
                    mock(com.yanban.api.agent.v2.compatibility.project
                            .ProjectAnalysisAuthoritySource.class),
                    json);

    @Test
    void exactReadIsBoundedUtf8AndBinaryOrOversizeFailsClosed() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = ref();
        ProjectPath path = new ProjectPath("paper/main.md");
        when(workspace.read(ref, path)).thenReturn(
                "evidence".getBytes(StandardCharsets.UTF_8));
        var arguments = json.createObjectNode().put(
                "path", "paper/main.md");

        String output = composer.read(workspace, ref, arguments);
        assertTrue(output.contains("\"path\":\"paper/main.md\""));
        assertTrue(output.contains("\"content\":\"evidence\""));

        when(workspace.read(ref, path)).thenReturn(new byte[]{0, 1});
        assertThrows(IllegalStateException.class,
                () -> composer.read(workspace, ref, arguments));
        when(workspace.read(ref, path)).thenReturn(
                new byte[64 * 1024 + 1]);
        assertThrows(IllegalStateException.class,
                () -> composer.read(workspace, ref, arguments));
        assertThrows(RuntimeException.class, () -> composer.read(
                workspace, ref,
                json.createObjectNode().put("path", "../secret")));
    }

    @Test
    void literalSearchIsSortedBoundedAndDoesNotReturnNonmatchingContent() {
        WorkspacePort workspace = mock(WorkspacePort.class);
        WorkspaceRef ref = ref();
        ProjectPath first = new ProjectPath("a.md");
        ProjectPath second = new ProjectPath("b.md");
        WorkspaceFileStat firstStat = stat(first);
        WorkspaceFileStat secondStat = stat(second);
        when(workspace.list(ref)).thenReturn(List.of(secondStat, firstStat));
        when(workspace.read(ref, first)).thenReturn(
                "needle alpha".getBytes(StandardCharsets.UTF_8));
        when(workspace.read(ref, second)).thenReturn(
                "unrelated".getBytes(StandardCharsets.UTF_8));
        var arguments = json.createObjectNode()
                .put("maxResults", 1).put("query", "needle");

        String output = composer.search(workspace, ref, arguments);

        assertTrue(output.contains("\"path\":\"a.md\""));
        assertTrue(output.contains("needle alpha"));
        assertFalse(output.contains("b.md"));
        assertThrows(IllegalStateException.class,
                () -> composer.search(workspace, ref,
                        json.createObjectNode()
                                .put("maxResults", 21)
                                .put("query", "needle")));
    }

    private static WorkspaceRef ref() {
        return new WorkspaceRef(
                new WorkspaceId("workspace"),
                new ProjectVersionRef("8", "version"));
    }

    private static WorkspaceFileStat stat(ProjectPath path) {
        return new WorkspaceFileStat(
                path, 10,
                new ContentHash("sha256", "a".repeat(64)));
    }
}
