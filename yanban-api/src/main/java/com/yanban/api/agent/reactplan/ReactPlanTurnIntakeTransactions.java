package com.yanban.api.agent.reactplan;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.api.agent.AgentSessionTitleGenerator;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReactPlanTurnIntakeTransactions {
    private final ReactPlanTurnIntakeRepository intakes;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final AgentSessionRepository sessions;

    ReactPlanTurnIntakeTransactions(
            ReactPlanTurnIntakeRepository intakes,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            AgentSessionRepository sessions) {
        this.intakes = intakes;
        this.messages = messages;
        this.turns = turns;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    Optional<ReactPlanTurnIntakeEntity> find(
            long userId, long sessionId, String clientRequestId) {
        return intakes.findByUserIdAndSessionIdAndClientRequestId(
                userId, sessionId, clientRequestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReactPlanTurnIntakeEntity create(
            long userId, long sessionId, String clientRequestId,
            String requestDigest, String instruction) {
        AgentMessage message = messages.saveAndFlush(new AgentMessage(
                sessionId, userId, "user", instruction, null, null));
        AgentTurn turn = turns.saveAndFlush(new AgentTurn(
                sessionId, userId, message.getId()));
        String taskId = ReactPlanRuntimeService.taskId(userId, turn.getId());
        return intakes.saveAndFlush(new ReactPlanTurnIntakeEntity(
                userId, sessionId, clientRequestId, requestDigest,
                turn.getId(), message.getId(), taskId,
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    boolean shouldInitializeTitle(long userId, long sessionId, String taskId) {
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId).orElse(null);
        return session != null
                && AgentSessionTitleGenerator.isDefaultTitle(session.getTitle())
                && intakes.findFirstByUserIdAndSessionIdOrderByIdAsc(userId, sessionId)
                        .map(first -> first.taskId().equals(taskId))
                        .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean initializeTitleIfStillEligible(
            long userId, long sessionId, String taskId, String generatedTitle) {
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId).orElse(null);
        boolean firstTask = intakes.findFirstByUserIdAndSessionIdOrderByIdAsc(userId, sessionId)
                .map(first -> first.taskId().equals(taskId))
                .orElse(false);
        if (session == null || !firstTask
                || !AgentSessionTitleGenerator.isDefaultTitle(session.getTitle())) {
            return false;
        }
        session.updateTitle(generatedTitle);
        sessions.saveAndFlush(session);
        return true;
    }
}
