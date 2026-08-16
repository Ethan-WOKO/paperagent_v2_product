package com.yanban.api.agent.reactplan;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
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

    ReactPlanTurnIntakeTransactions(
            ReactPlanTurnIntakeRepository intakes,
            AgentMessageRepository messages,
            AgentTurnRepository turns) {
        this.intakes = intakes;
        this.messages = messages;
        this.turns = turns;
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
}
