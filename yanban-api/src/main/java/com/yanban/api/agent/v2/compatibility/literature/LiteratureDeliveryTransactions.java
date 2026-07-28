package com.yanban.api.agent.v2.compatibility.literature;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class LiteratureDeliveryTransactions {
    private final LiteratureDeliveryJpaRepository deliveries;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;

    LiteratureDeliveryTransactions(
            LiteratureDeliveryJpaRepository deliveries,
            AgentMessageRepository messages,
            AgentTurnRepository turns) {
        this.deliveries = deliveries;
        this.messages = messages;
        this.turns = turns;
    }

    @Transactional
    public synchronized LiteratureDeliveryEntity open(
            Long userId, Long sessionId, String requestId,
            String requestHash, String query, String leaseOwnerId,
            String leaseToken, Instant leaseExpiresAt) {
        LiteratureDeliveryKey key =
                new LiteratureDeliveryKey(userId, sessionId, requestId);
        var existing = deliveries.findById(key);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().requestSha256().equals(requestHash)) {
                throw new IllegalArgumentException(
                        "clientRequestId was already used for another payload");
            }
            return existing.orElseThrow();
        }
        AgentMessage userMessage = messages.saveAndFlush(new AgentMessage(
                sessionId, userId, "user",
                "Literature search: " + query, null, null));
        AgentTurn turn = turns.saveAndFlush(
                new AgentTurn(sessionId, userId, userMessage.getId()));
        return deliveries.saveAndFlush(new LiteratureDeliveryEntity(
                key, requestHash, userMessage.getId(), turn.getId(),
                leaseOwnerId, leaseToken, leaseExpiresAt, Instant.now()));
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
