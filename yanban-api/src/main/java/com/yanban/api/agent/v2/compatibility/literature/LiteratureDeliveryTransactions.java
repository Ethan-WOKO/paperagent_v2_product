package com.yanban.api.agent.v2.compatibility.literature;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class LiteratureDeliveryTransactions {
    private final LiteratureDeliveryJpaRepository deliveries;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final TransactionTemplate requiresNew;
    private final EntityManager entityManager;

    LiteratureDeliveryTransactions(
            LiteratureDeliveryJpaRepository deliveries,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            PlatformTransactionManager transactionManager,
            EntityManager entityManager) {
        this.deliveries = deliveries;
        this.messages = messages;
        this.turns = turns;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.entityManager = entityManager;
    }

    public LiteratureDeliveryEntity open(
            Long userId, Long sessionId, String requestId,
            String requestHash, String query, int topK, Integer yearFrom,
            boolean includeBibtex, String leaseOwnerId,
            String leaseToken, Instant leaseExpiresAt) {
        LiteratureDeliveryKey key =
                new LiteratureDeliveryKey(userId, sessionId, requestId);
        var existing = deliveries.findById(key);
        if (existing.isPresent()) {
            return samePayload(existing.orElseThrow(), requestHash);
        }
        try {
            return requiresNew.execute(status -> {
                AgentSession session = entityManager.find(
                        AgentSession.class, sessionId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (session == null || !userId.equals(session.getUserId())) {
                    throw new IllegalArgumentException(
                            "agent session was not found");
                }
                var afterLock = deliveries.findById(key);
                if (afterLock.isPresent()) {
                    return samePayload(
                            afterLock.orElseThrow(), requestHash);
                }
                AgentMessage userMessage = messages.saveAndFlush(
                        new AgentMessage(
                                sessionId, userId, "user",
                                "Literature search: " + query, null, null));
                AgentTurn turn = turns.saveAndFlush(
                        new AgentTurn(
                                sessionId, userId, userMessage.getId()));
                LiteratureDeliveryEntity created =
                        new LiteratureDeliveryEntity(
                                key, requestHash, query, topK, yearFrom,
                                includeBibtex, userMessage.getId(),
                                turn.getId(), leaseOwnerId, leaseToken,
                                leaseExpiresAt, Instant.now());
                entityManager.persist(created);
                entityManager.flush();
                return created;
            });
        } catch (RuntimeException conflict) {
            LiteratureDeliveryEntity winner = requiresNew.execute(status ->
                    deliveries.findById(key).orElse(null));
            if (winner == null) {
                throw conflict;
            }
            return samePayload(winner, requestHash);
        }
    }

    private static LiteratureDeliveryEntity samePayload(
            LiteratureDeliveryEntity value, String requestHash) {
        if (!value.requestSha256().equals(requestHash)) {
            throw new IllegalArgumentException(
                    "clientRequestId was already used for another payload");
        }
        return value;
    }

    @Transactional
    LiteratureDeliveryEntity bindPlan(
            LiteratureDeliveryKey key, String planId) {
        LiteratureDeliveryEntity value = deliveries.findLocked(key)
                .orElseThrow(() -> new IllegalStateException(
                        "literature delivery disappeared"));
        value.bindPlan(planId);
        return deliveries.save(value);
    }

    @Transactional
    LiteratureDeliveryEntity rotateExpiredLease(
            LiteratureDeliveryKey key, String token, Instant expiresAt,
            Instant now) {
        LiteratureDeliveryEntity value = deliveries.findLocked(key)
                .orElseThrow(() -> new IllegalStateException(
                        "literature delivery disappeared"));
        if (!"DELIVERED".equals(value.status())
                && !value.leaseExpiresAt().isAfter(now)) {
            value.rotateLease(token, expiresAt);
            deliveries.saveAndFlush(value);
        }
        return value;
    }

    @Transactional
    LiteratureDeliveryEntity deliver(
            LiteratureDeliveryKey key, String planId,
            String synthesisId, String narrative) {
        LiteratureDeliveryEntity value = deliveries.findLocked(key)
                .orElseThrow(() -> new IllegalStateException(
                        "literature delivery disappeared"));
        if ("DELIVERED".equals(value.status())) {
            return value;
        }
        AgentMessage assistant = messages.saveAndFlush(new AgentMessage(
                key.sessionId(), key.userId(), "assistant",
                narrative, null, null));
        AgentTurn turn = turns.findById(value.turnId())
                .orElseThrow(() -> new IllegalStateException(
                        "literature turn disappeared"));
        turn.complete(assistant.getId());
        turns.saveAndFlush(turn);
        value.complete(planId, synthesisId, assistant.getId());
        return deliveries.saveAndFlush(value);
    }

    @Transactional(readOnly = true)
    AgentMessage assistant(Long id) {
        return messages.findById(id).orElseThrow(() ->
                new IllegalStateException("assistant delivery disappeared"));
    }
}
