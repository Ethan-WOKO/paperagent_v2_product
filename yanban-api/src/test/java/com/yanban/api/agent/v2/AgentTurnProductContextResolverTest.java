package com.yanban.api.agent.v2;

import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.INVALID_PROJECT_MANIFEST;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.INVALID_SCOPE_PROJECT;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.INVALID_TURN_ID;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.INVALID_USER_ID;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.MALFORMED_SESSION;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.MALFORMED_TURN;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.SESSION_NOT_FOUND;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.TURN_NOT_FOUND;
import static com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode.UNSUPPORTED_SCOPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class AgentTurnProductContextResolverTest {

    private static final Long USER_ID = 7L;
    private static final Long TURN_ID = 41L;
    private static final Long SESSION_ID = 29L;
    private static final Long PROJECT_ID = 18L;

    private AgentTurnRepository turns;
    private AgentSessionRepository sessions;
    private ProjectService projects;
    private AgentTurnProductContextResolver resolver;

    @BeforeEach
    void setUp() {
        turns = mock(AgentTurnRepository.class);
        sessions = mock(AgentSessionRepository.class);
        projects = mock(ProjectService.class);
        resolver = new AgentTurnProductContextResolver(turns, sessions, projects);
    }

    @Test
    void resolvesOwnedWorkspaceTurnWithoutProjectVersion() {
        givenTurnAndSession(AgentSessionScope.WORKSPACE, null);

        VerifiedAgentTurnProductContext context = resolver.resolve(USER_ID, TURN_ID);

        assertThat(context).isEqualTo(new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "41", USER_ID, SESSION_ID, null),
                Optional.empty()));
        verify(turns).findByIdAndUserId(TURN_ID, USER_ID);
        verify(sessions).findByIdAndUserId(SESSION_ID, USER_ID);
        verifyNoInteractions(projects);
    }

    @Test
    void resolvesOwnedProjectTurnWithExactManifestVersion() {
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(PROJECT_ID, "manifest-v7", List.of()));

        VerifiedAgentTurnProductContext context = resolver.resolve(USER_ID, TURN_ID);

        assertThat(context.identity())
                .isEqualTo(new AgentRunIdentity("AGENT_TURN", "41", USER_ID, SESSION_ID, PROJECT_ID));
        assertThat(context.projectVersionId()).contains("manifest-v7");
    }

    @Test
    void equivalentFactsReplayToEqualResult() {
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(PROJECT_ID, "same-version", List.of()));

        assertThat(resolver.resolve(USER_ID, TURN_ID)).isEqualTo(resolver.resolve(USER_ID, TURN_ID));
    }

    @Test
    void publicResolutionInputCannotInjectProductFacts() throws Exception {
        Method resolve = AgentTurnProductContextResolver.class.getMethod("resolve", Long.class, Long.class);

        assertThat(resolve.getParameterTypes()).containsExactly(Long.class, Long.class);
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(PROJECT_ID, "repository-version", List.of()));

        VerifiedAgentTurnProductContext context = resolver.resolve(USER_ID, TURN_ID);

        assertThat(context.identity().source()).isEqualTo("AGENT_TURN");
        assertThat(context.identity().sourceId()).isEqualTo("41");
        assertThat(context.identity().sessionId()).isEqualTo(SESSION_ID);
        assertThat(context.identity().projectId()).isEqualTo(PROJECT_ID);
        assertThat(context.projectVersionId()).contains("repository-version");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1, 0})
    void rejectsInvalidUserId(Long userId) {
        assertFailure(() -> resolver.resolve(userId, TURN_ID), INVALID_USER_ID, "userId");
        verifyNoInteractions(turns, sessions, projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1, 0})
    void rejectsInvalidTurnId(Long turnId) {
        assertFailure(() -> resolver.resolve(USER_ID, turnId), INVALID_TURN_ID, "turnId");
        verifyNoInteractions(turns, sessions, projects);
    }

    @Test
    void hidesAbsentOrWrongOwnerTurnBehindOneFailure() {
        when(turns.findByIdAndUserId(TURN_ID, USER_ID)).thenReturn(Optional.empty());

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), TURN_NOT_FOUND, "turnId");
        verifyNoInteractions(sessions, projects);
    }

    @Test
    void hidesAbsentOrWrongOwnerSessionBehindOneFailure() {
        AgentTurn turn = turn(TURN_ID, USER_ID, SESSION_ID);
        when(turns.findByIdAndUserId(TURN_ID, USER_ID)).thenReturn(Optional.of(turn));
        when(sessions.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.empty());

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), SESSION_NOT_FOUND, "turn.sessionId");
        verifyNoInteractions(projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 999)
    void rejectsMalformedOrMismatchedTurnId(Long storedTurnId) {
        AgentTurn malformed = turn(storedTurnId, USER_ID, SESSION_ID);
        when(turns.findByIdAndUserId(TURN_ID, USER_ID))
                .thenReturn(Optional.of(malformed));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), MALFORMED_TURN, "turn.id");
        verifyNoInteractions(sessions, projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 999)
    void rejectsMalformedOrMismatchedTurnUser(Long storedUserId) {
        AgentTurn malformed = turn(TURN_ID, storedUserId, SESSION_ID);
        when(turns.findByIdAndUserId(TURN_ID, USER_ID))
                .thenReturn(Optional.of(malformed));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), MALFORMED_TURN, "turn.userId");
        verifyNoInteractions(sessions, projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1, 0})
    void rejectsMalformedTurnSessionId(Long sessionId) {
        AgentTurn malformed = turn(TURN_ID, USER_ID, sessionId);
        when(turns.findByIdAndUserId(TURN_ID, USER_ID))
                .thenReturn(Optional.of(malformed));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), MALFORMED_TURN, "turn.sessionId");
        verifyNoInteractions(sessions, projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 999)
    void rejectsMalformedOrMismatchedSessionId(Long storedSessionId) {
        givenTurn();
        AgentSession malformed = session(storedSessionId, USER_ID, AgentSessionScope.WORKSPACE, null);
        when(sessions.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(malformed));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), MALFORMED_SESSION, "session.id");
        verifyNoInteractions(projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 999)
    void rejectsMalformedOrMismatchedSessionUser(Long storedUserId) {
        givenTurn();
        AgentSession malformed = session(SESSION_ID, storedUserId, AgentSessionScope.WORKSPACE, null);
        when(sessions.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(malformed));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), MALFORMED_SESSION, "session.userId");
        verifyNoInteractions(projects);
    }

    @Test
    void rejectsNullScope() {
        givenTurnAndSession(null, null);

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), UNSUPPORTED_SCOPE, "session.scope");
        verifyNoInteractions(projects);
    }

    @Test
    void rejectsUnsupportedScope() {
        AgentSessionScope unsupported = mock(AgentSessionScope.class);
        givenTurnAndSession(unsupported, null);

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID), UNSUPPORTED_SCOPE, "session.scope");
        verifyNoInteractions(projects);
    }

    @Test
    void rejectsWorkspaceWithProject() {
        givenTurnAndSession(AgentSessionScope.WORKSPACE, PROJECT_ID);

        assertFailure(
                () -> resolver.resolve(USER_ID, TURN_ID), INVALID_SCOPE_PROJECT, "session.projectId");
        verifyNoInteractions(projects);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1, 0})
    void rejectsProjectWithoutPositiveProjectId(Long projectId) {
        givenTurnAndSession(AgentSessionScope.PROJECT, projectId);

        assertFailure(
                () -> resolver.resolve(USER_ID, TURN_ID), INVALID_SCOPE_PROJECT, "session.projectId");
        verifyNoInteractions(projects);
    }

    @Test
    void rejectsNullManifest() {
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(null);

        assertFailure(
                () -> resolver.resolve(USER_ID, TURN_ID), INVALID_PROJECT_MANIFEST, "projectManifest");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 999)
    void rejectsNullOrMismatchedManifestProject(Long manifestProjectId) {
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(manifestProjectId, "version", List.of()));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID),
                INVALID_PROJECT_MANIFEST, "projectManifest.projectId");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsBlankManifestVersion(String version) {
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(PROJECT_ID, version, List.of()));

        assertFailure(() -> resolver.resolve(USER_ID, TURN_ID),
                INVALID_PROJECT_MANIFEST, "projectManifest.version");
    }

    @Test
    void propagatesProjectServiceFailureUnchanged() {
        givenTurnAndSession(AgentSessionScope.PROJECT, PROJECT_ID);
        ResponseStatusException failure = new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "project not found");
        when(projects.manifest(USER_ID, PROJECT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> resolver.resolve(USER_ID, TURN_ID)).isSameAs(failure);
    }

    private void givenTurnAndSession(AgentSessionScope scope, Long projectId) {
        givenTurn();
        AgentSession verifiedSession = session(SESSION_ID, USER_ID, scope, projectId);
        when(sessions.findByIdAndUserId(SESSION_ID, USER_ID))
                .thenReturn(Optional.of(verifiedSession));
    }

    private void givenTurn() {
        AgentTurn verifiedTurn = turn(TURN_ID, USER_ID, SESSION_ID);
        when(turns.findByIdAndUserId(TURN_ID, USER_ID))
                .thenReturn(Optional.of(verifiedTurn));
    }

    private static AgentTurn turn(Long id, Long userId, Long sessionId) {
        AgentTurn turn = mock(AgentTurn.class);
        when(turn.getId()).thenReturn(id);
        when(turn.getUserId()).thenReturn(userId);
        when(turn.getSessionId()).thenReturn(sessionId);
        return turn;
    }

    private static AgentSession session(
            Long id,
            Long userId,
            AgentSessionScope scope,
            Long projectId
    ) {
        AgentSession session = mock(AgentSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getUserId()).thenReturn(userId);
        when(session.getScope()).thenReturn(scope);
        when(session.getProjectId()).thenReturn(projectId);
        return session;
    }

    private static void assertFailure(
            ThrowingAction action,
            AgentTurnProductContextResolutionCode code,
            String path
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AgentTurnProductContextResolutionException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(code);
                            assertThat(failure.path()).isEqualTo(path);
                        });
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
