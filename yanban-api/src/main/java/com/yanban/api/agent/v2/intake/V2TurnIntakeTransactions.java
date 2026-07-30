package com.yanban.api.agent.v2.intake;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class V2TurnIntakeTransactions {
    private final V2TurnIntakeJpaRepository intakes;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNew;

    V2TurnIntakeTransactions(
            V2TurnIntakeJpaRepository intakes,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.intakes = intakes;
        this.messages = messages;
        this.turns = turns;
        this.entityManager = entityManager;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    V2TurnIntakeEntity open(
            Long userId,
            Long sessionId,
            String clientRequestId,
            String requestHash,
            String content,
            boolean ragDisabled,
            String skillId,
            String experimentJson) {
        var existing = intakes.findByUserIdAndSessionIdAndClientRequestId(
                userId, sessionId, clientRequestId);
        if (existing.isPresent()) {
            return sameRequest(existing.orElseThrow(), requestHash);
        }
        try {
            return requiresNew.execute(status -> {
                AgentSession session = entityManager.find(
                        AgentSession.class,
                        sessionId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (session == null || !userId.equals(session.getUserId())) {
                    throw new IllegalArgumentException(
                            "agent session was not found");
                }
                var afterLock =
                        intakes.findByUserIdAndSessionIdAndClientRequestId(
                                userId, sessionId, clientRequestId);
                if (afterLock.isPresent()) {
                    return sameRequest(afterLock.orElseThrow(), requestHash);
                }
                AgentMessage userMessage = messages.saveAndFlush(
                        new AgentMessage(
                                sessionId, userId, "user", content, null, null));
                AgentTurn turn = turns.saveAndFlush(
                        new AgentTurn(
                                sessionId, userId, userMessage.getId()));
                V2TurnIntakeEntity created = new V2TurnIntakeEntity(
                        userId,
                        sessionId,
                        clientRequestId,
                        requestHash,
                        content,
                        ragDisabled,
                        skillId,
                        experimentJson,
                        userMessage.getId(),
                        turn.getId(),
                        Instant.now());
                entityManager.persist(created);
                entityManager.flush();
                return created;
            });
        } catch (RuntimeException race) {
            V2TurnIntakeEntity winner = requiresNew.execute(status ->
                    intakes.findByUserIdAndSessionIdAndClientRequestId(
                            userId, sessionId, clientRequestId).orElse(null));
            if (winner == null) {
                throw race;
            }
            return sameRequest(winner, requestHash);
        }
    }

    <T> T locked(
            V2TurnIntakeEntity intake,
            Function<V2TurnIntakeEntity, T> operation) {
        return requiresNew.execute(status -> {
            V2TurnIntakeEntity locked = intakes.findLocked(
                            intake.userId(),
                            intake.sessionId(),
                            intake.clientRequestId())
                    .orElseThrow(() -> new IllegalStateException(
                            "V2 turn intake disappeared"));
            return operation.apply(locked);
        });
    }

    AgentMessage saveAssistant(
            V2TurnIntakeEntity intake, String answer, String outputJson) {
        AgentMessage assistant = messages.saveAndFlush(
                new AgentMessage(
                        intake.sessionId(),
                        intake.userId(),
                        "assistant",
                        answer,
                        null,
                        null));
        AgentTurn turn = turns.findById(intake.turnId())
                .orElseThrow(() -> new IllegalStateException(
                        "V2 Agent turn disappeared"));
        turn.complete(assistant.getId());
        turns.saveAndFlush(turn);
        intake.completeDirect(assistant.getId(), outputJson, Instant.now());
        intakes.saveAndFlush(intake);
        return assistant;
    }

    void savePersistent(
            V2TurnIntakeEntity intake,
            String planId,
            String outputJson,
            String bindingsJson) {
        intake.completePersistent(
                planId, outputJson, bindingsJson, Instant.now());
        intakes.saveAndFlush(intake);
    }

    void saveFailure(V2TurnIntakeEntity intake, String code) {
        AgentTurn turn = turns.findById(intake.turnId())
                .orElseThrow(() -> new IllegalStateException(
                        "V2 Agent turn disappeared"));
        turn.fail(null, "V2 planning failed");
        turns.saveAndFlush(turn);
        intake.fail(code, Instant.now());
        intakes.saveAndFlush(intake);
    }

    AgentMessage message(Long messageId) {
        return messages.findById(messageId).orElseThrow(() ->
                new IllegalStateException("V2 assistant message disappeared"));
    }

    private static V2TurnIntakeEntity sameRequest(
            V2TurnIntakeEntity value, String requestHash) {
        if (!value.requestSha256().equals(requestHash)) {
            throw new IllegalArgumentException(
                    "clientRequestId was already used for another payload");
        }
        return value;
    }
}
