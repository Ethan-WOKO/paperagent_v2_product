package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.intake.V2SessionDeletionService;
import com.yanban.api.project.ProjectService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurnRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentSessionServiceTest {

    @Test
    void deletesV2DependentsBeforeSharedSessionRows() {
        var fixture = fixture();
        var session = mock(AgentSession.class);
        when(session.getId()).thenReturn(45L);
        when(fixture.sessions.findByIdAndUserId(45L, 7L))
                .thenReturn(Optional.of(session));

        fixture.service.deleteSession(7L, 45L);

        var order = inOrder(
                fixture.v2Data, fixture.turns, fixture.messages,
                fixture.cache, fixture.sessions);
        order.verify(fixture.v2Data).deleteOwnedSessionData(7L, 45L);
        order.verify(fixture.turns).deleteBySessionId(45L);
        order.verify(fixture.messages).deleteBySessionId(45L);
        order.verify(fixture.cache).evictSession(7L, 45L);
        order.verify(fixture.sessions).delete(session);
        order.verify(fixture.sessions).flush();
    }

    @Test
    void projectFacadeChecksProjectOwnershipBeforeListingOrCreatingSessions() {
        ProjectService projects = mock(ProjectService.class);
        AgentSessionService sessions = mock(AgentSessionService.class);
        ProjectSessionService service = new ProjectSessionService(projects, sessions);
        CreateSessionRequest request = new CreateSessionRequest(
                "Study", null, null, null, true);
        AgentSessionResponse response = mock(AgentSessionResponse.class);
        when(sessions.listProjectSessions(7L, 42L)).thenReturn(List.of(response));
        when(sessions.createProjectSession(7L, 42L, request, "Project #42"))
                .thenReturn(response);

        assertThat(service.listSessions(7L, 42L)).containsExactly(response);
        assertThat(service.createSession(7L, 42L, request)).isSameAs(response);

        verify(projects, org.mockito.Mockito.times(2)).manifest(7L, 42L);
        verify(sessions).listProjectSessions(7L, 42L);
        verify(sessions).createProjectSession(7L, 42L, request, "Project #42");
    }

    @Test
    void projectFacadeDeletesOwnedSessionsBeforeDeletingProject() {
        ProjectService projects = mock(ProjectService.class);
        AgentSessionService sessions = mock(AgentSessionService.class);
        ProjectSessionService service = new ProjectSessionService(projects, sessions);
        AgentSessionResponse first = projectSession(45L);
        AgentSessionResponse second = projectSession(46L);
        when(sessions.listProjectSessions(7L, 42L))
                .thenReturn(List.of(first, second));

        service.deleteProject(7L, 42L);

        var order = inOrder(projects, sessions);
        order.verify(sessions).listProjectSessions(7L, 42L);
        order.verify(sessions).deleteSession(7L, 45L);
        order.verify(sessions).deleteSession(7L, 46L);
        order.verify(projects).delete(7L, 42L);
    }

    private static AgentSessionResponse projectSession(Long id) {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        return new AgentSessionResponse(
                id, 7L, AgentSessionScope.PROJECT, 42L, "Study",
                "provider", "model", 8, true, now, now);
    }

    private static Fixture fixture() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentTurnRepository turns = mock(AgentTurnRepository.class);
        AgentMessageCacheService cache = mock(AgentMessageCacheService.class);
        V2SessionDeletionService v2Data = mock(V2SessionDeletionService.class);
        AgentSessionService service = new AgentSessionService(
                sessions, messages, turns, cache,
                mock(UserSettingsService.class), v2Data);
        return new Fixture(service, sessions, messages, turns, cache, v2Data);
    }

    private record Fixture(
            AgentSessionService service,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            AgentMessageCacheService cache,
            V2SessionDeletionService v2Data) {
    }
}
