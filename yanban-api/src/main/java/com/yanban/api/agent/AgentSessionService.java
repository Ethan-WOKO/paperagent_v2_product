package com.yanban.api.agent;

import com.yanban.api.agent.v2.intake.V2SessionDeletionService;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurnRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Stable session persistence used by the V2 Project workspace. */
@Service
public class AgentSessionService {
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final AgentMessageCacheService messageCache;
    private final UserSettingsService userSettings;
    private final V2SessionDeletionService v2SessionDeletion;

    public AgentSessionService(
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            AgentMessageCacheService messageCache,
            UserSettingsService userSettings,
            V2SessionDeletionService v2SessionDeletion) {
        this.sessions = sessions;
        this.messages = messages;
        this.turns = turns;
        this.messageCache = messageCache;
        this.userSettings = userSettings;
        this.v2SessionDeletion = v2SessionDeletion;
    }

    @Transactional
    public AgentSessionResponse createProjectSession(
            Long userId,
            Long projectId,
            CreateSessionRequest request,
            String fallbackTitle) {
        SysUserSettings settings = userSettings.getOrCreate(userId);
        String requestedProvider = StringUtils.hasText(request.modelProvider())
                ? request.modelProvider().trim()
                : settings.getDefaultProvider();
        UserSettingsService.ModelEndpoint endpoint = userSettings.resolveModelEndpoint(
                userId,
                requestedProvider,
                StringUtils.hasText(request.model()) ? request.model().trim() : null);
        AgentSession session = new AgentSession(
                userId,
                StringUtils.hasText(request.title()) ? request.title().trim() : fallbackTitle,
                endpoint.providerKey(),
                endpoint.modelName(),
                request.maxSteps() == null ? settings.getMaxSteps() : request.maxSteps(),
                request.ragDisabled() != null
                        ? request.ragDisabled()
                        : !Boolean.TRUE.equals(settings.getRagDefaultEnabled()),
                AgentSessionScope.PROJECT,
                projectId);
        AgentSession saved = sessions.saveAndFlush(session);
        messageCache.evictSession(userId, saved.getId());
        return AgentSessionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AgentSessionResponse> listProjectSessions(Long userId, Long projectId) {
        return sessions.findByUserIdAndScopeAndProjectIdOrderByUpdatedAtDesc(
                        userId, AgentSessionScope.PROJECT, projectId).stream()
                .map(AgentSessionResponse::from)
                .toList();
    }

    @Transactional
    public AgentSessionResponse updateSession(
            Long userId,
            Long sessionId,
            UpdateSessionRequest request) {
        AgentSession session = getOwnedSession(userId, sessionId);
        if (request.title() != null) {
            String title = request.title().trim();
            if (!StringUtils.hasText(title)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Session title cannot be blank.");
            }
            session.updateTitle(title);
        }
        if (StringUtils.hasText(request.modelProvider())
                || StringUtils.hasText(request.model())) {
            String provider = StringUtils.hasText(request.modelProvider())
                    ? request.modelProvider().trim()
                    : session.getModelProviderSnapshot();
            UserSettingsService.ModelEndpoint endpoint = userSettings.resolveModelEndpoint(
                    userId,
                    provider,
                    StringUtils.hasText(request.model()) ? request.model().trim() : null);
            session.updateModel(endpoint.providerKey(), endpoint.modelName());
        }
        if (request.maxSteps() != null) {
            session.updateMaxSteps(request.maxSteps());
        }
        if (request.ragDisabled() != null) {
            session.updateRagDisabled(request.ragDisabled());
        }
        session.touch();
        return AgentSessionResponse.from(sessions.saveAndFlush(session));
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        AgentSession session = getOwnedSession(userId, sessionId);
        v2SessionDeletion.deleteOwnedSessionData(userId, session.getId());
        turns.deleteBySessionId(session.getId());
        messages.deleteBySessionId(session.getId());
        messageCache.evictSession(userId, session.getId());
        sessions.delete(session);
        sessions.flush();
    }

    @Transactional(readOnly = true)
    public AgentSession getOwnedSession(Long userId, Long sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Session does not exist."));
    }

}
