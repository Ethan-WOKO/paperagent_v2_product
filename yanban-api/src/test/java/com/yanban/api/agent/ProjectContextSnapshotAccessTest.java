package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentSession;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectContextSnapshotAccessTest {

    @Test
    void requiresAuthenticatedProjectAndSessionOwnershipBeforeReturningProjection() {
        ProjectService projects = mock(ProjectService.class);
        AgentService agents = mock(AgentService.class);
        AgentContextSnapshotService snapshots = mock(AgentContextSnapshotService.class);
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(
                projects, agents, mock(PlanAgentService.class), snapshots);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(
                42L, "a".repeat(64), List.<ProjectFileEntry>of()));
        when(agents.getOwnedProjectSession(7L, 42L, 24L)).thenReturn(mock(AgentSession.class));
        AgentContextSnapshotResponse response = new AgentContextSnapshotResponse(
                1L, 2L, 24L, 7L, "trace", List.of(), List.of(),
                0, 0, 0, 0, null, null);
        when(snapshots.listSessionSnapshots(7L, 24L, 1)).thenReturn(List.of(response));

        assertThat(service.listContextSnapshots(7L, 42L, 24L, 1)).containsExactly(response);
        verify(projects).manifest(7L, 42L);
        verify(agents).getOwnedProjectSession(7L, 42L, 24L);
    }

    @Test
    void projectOwnershipFailureStopsBeforeSessionOrSnapshotRead() {
        ProjectService projects = mock(ProjectService.class);
        AgentService agents = mock(AgentService.class);
        AgentContextSnapshotService snapshots = mock(AgentContextSnapshotService.class);
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(
                projects, agents, mock(PlanAgentService.class), snapshots);
        when(projects.manifest(8L, 42L)).thenThrow(new IllegalArgumentException("not owned"));

        assertThatThrownBy(() -> service.listContextSnapshots(8L, 42L, 24L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not owned");
        verify(agents, never()).getOwnedProjectSession(8L, 42L, 24L);
        verify(snapshots, never()).listSessionSnapshots(8L, 24L, 1);
    }

    @Test
    void sessionProjectBindingFailureStopsBeforeSnapshotRead() {
        ProjectService projects = mock(ProjectService.class);
        AgentService agents = mock(AgentService.class);
        AgentContextSnapshotService snapshots = mock(AgentContextSnapshotService.class);
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(
                projects, agents, mock(PlanAgentService.class), snapshots);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(
                42L, "a".repeat(64), List.<ProjectFileEntry>of()));
        when(agents.getOwnedProjectSession(7L, 42L, 999L))
                .thenThrow(new IllegalArgumentException("session not bound"));

        assertThatThrownBy(() -> service.listContextSnapshots(7L, 42L, 999L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not bound");
        verify(snapshots, never()).listSessionSnapshots(7L, 999L, 1);
    }
}
