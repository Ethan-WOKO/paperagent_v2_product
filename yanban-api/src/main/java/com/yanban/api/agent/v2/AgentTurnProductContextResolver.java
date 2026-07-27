package com.yanban.api.agent.v2;

import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTurnProductContextResolver {

    private static final String SOURCE = "AGENT_TURN";

    private final AgentTurnRepository turns;
    private final AgentSessionRepository sessions;
    private final ProjectService projects;

    public AgentTurnProductContextResolver(AgentTurnRepository turns,
                                           AgentSessionRepository sessions,
                                           ProjectService projects) {
        this.turns = turns;
        this.sessions = sessions;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public VerifiedAgentTurnProductContext resolve(Long userId, Long turnId) {
        requirePositive(userId, AgentTurnProductContextResolutionCode.INVALID_USER_ID, "userId");
        requirePositive(turnId, AgentTurnProductContextResolutionCode.INVALID_TURN_ID, "turnId");

        AgentTurn turn = turns.findByIdAndUserId(turnId, userId)
                .orElseThrow(() -> failure(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND, "turnId"));
        validateTurn(turn, turnId, userId);

        Long sessionId = turn.getSessionId();
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> failure(
                        AgentTurnProductContextResolutionCode.SESSION_NOT_FOUND, "turn.sessionId"));
        validateSession(session, sessionId, userId);

        Long projectId = session.getProjectId();
        Optional<String> projectVersionId = resolveProjectVersion(session.getScope(), projectId, userId);
        AgentRunIdentity identity = new AgentRunIdentity(
                SOURCE,
                String.valueOf(turnId),
                userId,
                sessionId,
                projectId
        );
        return new VerifiedAgentTurnProductContext(identity, projectVersionId);
    }

    private static void validateTurn(AgentTurn turn, Long turnId, Long userId) {
        if (turn.getId() == null || !turnId.equals(turn.getId())) {
            throw failure(AgentTurnProductContextResolutionCode.MALFORMED_TURN, "turn.id");
        }
        if (turn.getUserId() == null || !userId.equals(turn.getUserId())) {
            throw failure(AgentTurnProductContextResolutionCode.MALFORMED_TURN, "turn.userId");
        }
        if (turn.getSessionId() == null || turn.getSessionId() <= 0) {
            throw failure(AgentTurnProductContextResolutionCode.MALFORMED_TURN, "turn.sessionId");
        }
    }

    private static void validateSession(AgentSession session, Long sessionId, Long userId) {
        if (session.getId() == null || !sessionId.equals(session.getId())) {
            throw failure(AgentTurnProductContextResolutionCode.MALFORMED_SESSION, "session.id");
        }
        if (session.getUserId() == null || !userId.equals(session.getUserId())) {
            throw failure(AgentTurnProductContextResolutionCode.MALFORMED_SESSION, "session.userId");
        }
    }

    private Optional<String> resolveProjectVersion(AgentSessionScope scope, Long projectId, Long userId) {
        if (scope == null) {
            throw failure(AgentTurnProductContextResolutionCode.UNSUPPORTED_SCOPE, "session.scope");
        }
        if (scope == AgentSessionScope.WORKSPACE) {
            if (projectId != null) {
                throw failure(
                        AgentTurnProductContextResolutionCode.INVALID_SCOPE_PROJECT, "session.projectId");
            }
            return Optional.empty();
        }
        if (scope != AgentSessionScope.PROJECT) {
            throw failure(AgentTurnProductContextResolutionCode.UNSUPPORTED_SCOPE, "session.scope");
        }
        if (projectId == null || projectId <= 0) {
            throw failure(AgentTurnProductContextResolutionCode.INVALID_SCOPE_PROJECT, "session.projectId");
        }

        ProjectManifestResponse manifest = projects.manifest(userId, projectId);
        if (manifest == null) {
            throw failure(AgentTurnProductContextResolutionCode.INVALID_PROJECT_MANIFEST, "projectManifest");
        }
        if (manifest.projectId() == null || !projectId.equals(manifest.projectId())) {
            throw failure(
                    AgentTurnProductContextResolutionCode.INVALID_PROJECT_MANIFEST,
                    "projectManifest.projectId");
        }
        if (manifest.version() == null || manifest.version().isBlank()) {
            throw failure(
                    AgentTurnProductContextResolutionCode.INVALID_PROJECT_MANIFEST,
                    "projectManifest.version");
        }
        return Optional.of(manifest.version());
    }

    private static void requirePositive(
            Long value,
            AgentTurnProductContextResolutionCode code,
            String path
    ) {
        if (value == null || value <= 0) {
            throw failure(code, path);
        }
    }

    private static AgentTurnProductContextResolutionException failure(
            AgentTurnProductContextResolutionCode code,
            String path
    ) {
        return new AgentTurnProductContextResolutionException(code, path);
    }
}
