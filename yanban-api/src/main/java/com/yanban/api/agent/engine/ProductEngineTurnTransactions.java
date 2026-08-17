package com.yanban.api.agent.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ProductEngineTurnTransactions {
    private final ProductEngineTurnRepository engineTurns;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final ObjectMapper json;

    ProductEngineTurnTransactions(ProductEngineTurnRepository engineTurns,
                                  AgentMessageRepository messages,
                                  AgentTurnRepository turns,
                                  ObjectMapper json) {
        this.engineTurns = engineTurns; this.messages = messages; this.turns = turns; this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Begin begin(ProductEngineMode mode, long userId, long sessionId, long projectId,
                String projectVersion, String clientRequestId, String taskId,
                String digest, String productRequestDigest, String authorityJson, String question) {
        ProductEngineTurnEntity existing = engineTurns
                .findLockedByUserIdAndSessionIdAndRootClientRequestId(userId, sessionId, clientRequestId)
                .orElse(null);
        if (existing != null) {
            if (!existing.question().equals(question) || !existing.requestDigest().equals(digest)
                    || !existing.productRequestDigest().equals(productRequestDigest)
                    || existing.mode() != mode) {
                throw new ProductEngineControlException(409, "ENGINE_PRODUCT_REQUEST_CONFLICT");
            }
            return new Begin(existing.id(), existing.agentTurnId(), true);
        }
        AgentMessage userMessage = messages.saveAndFlush(
                new AgentMessage(sessionId, userId, "user", question, null, null));
        AgentTurn turn = turns.saveAndFlush(new AgentTurn(sessionId, userId, userMessage.getId()));
        ProductEngineTurnEntity created = engineTurns.saveAndFlush(new ProductEngineTurnEntity(
                mode, userId, sessionId, projectId, projectVersion, clientRequestId,
                taskId, digest, productRequestDigest, authorityJson, question,
                userMessage.getId(), turn.getId()));
        return new Begin(created.id(), turn.getId(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ProductEngineTurnEntity apply(long userId, long sessionId, String rootClientRequestId,
                                  List<ProductEngineDtos.Event> events) {
        ProductEngineTurnEntity entity = locked(userId, sessionId, rootClientRequestId);
        for (ProductEngineDtos.Event event : events) {
            if (event.sequence() <= entity.lastSequence()) continue;
            if (event.sequence() != entity.lastSequence() + 1) {
                throw new ProductEngineControlException(502, "ENGINE_EVENT_SEQUENCE_GAP");
            }
            if (terminal(entity.engineState())) {
                throw new ProductEngineControlException(502, "ENGINE_EVENT_AFTER_TERMINAL");
            }
            if ("delivery".equals(event.type()) && entity.finalText() != null) {
                throw new ProductEngineControlException(502, "ENGINE_DELIVERY_DUPLICATE");
            }
            if ("status".equals(event.type()) && "succeeded".equals(event.state())
                    && entity.finalText() == null) {
                throw new ProductEngineControlException(502, "ENGINE_DELIVERY_ORDER_INVALID");
            }
            if ("status".equals(event.type()) && "waiting_user".equals(event.state())
                    && entity.pendingQuestionId() == null) {
                throw new ProductEngineControlException(502, "ENGINE_QUESTION_ORDER_INVALID");
            }
            String receipts = event.receiptRefs() == null ? entity.receiptRefsJson() : write(event.receiptRefs());
            entity.applyEvent(event, receipts);
        }
        finalizeProductTurn(entity);
        return engineTurns.saveAndFlush(entity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ProductEngineTurnEntity recordAnswer(long userId, long sessionId, String rootClientRequestId,
                                         String clientRequestId, String questionId, String digest) {
        ProductEngineTurnEntity entity = locked(userId, sessionId, rootClientRequestId);
        entity.recordAnswer(clientRequestId, questionId, digest);
        return engineTurns.saveAndFlush(entity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ProductEngineTurnEntity recordCancel(long userId, long sessionId, String rootClientRequestId,
                                         String clientRequestId) {
        ProductEngineTurnEntity entity = locked(userId, sessionId, rootClientRequestId);
        entity.recordCancel(clientRequestId);
        return engineTurns.saveAndFlush(entity);
    }

    @Transactional(readOnly = true)
    ProductEngineTurnEntity require(long userId, long sessionId, String rootClientRequestId) {
        return engineTurns.findByUserIdAndSessionIdAndRootClientRequestId(userId, sessionId, rootClientRequestId)
                .orElseThrow(() -> new ProductEngineControlException(404, "ENGINE_PRODUCT_TURN_NOT_FOUND"));
    }

    @Transactional(readOnly = true)
    Optional<ProductEngineTurnEntity> find(long userId, long sessionId, String rootClientRequestId) {
        return engineTurns.findByUserIdAndSessionIdAndRootClientRequestId(userId, sessionId, rootClientRequestId);
    }

    private ProductEngineTurnEntity locked(long userId, long sessionId, String rootClientRequestId) {
        return engineTurns.findLockedByUserIdAndSessionIdAndRootClientRequestId(userId, sessionId, rootClientRequestId)
                .orElseThrow(() -> new ProductEngineControlException(404, "ENGINE_PRODUCT_TURN_NOT_FOUND"));
    }

    private void finalizeProductTurn(ProductEngineTurnEntity entity) {
        if (!terminal(entity.engineState())) return;
        AgentTurn turn = turns.findByIdAndUserId(entity.agentTurnId(), entity.userId()).orElseThrow();
        if (!AgentTurn.STATUS_RUNNING.equals(turn.getStatus())) {
            if (turn.getAssistantMessageId() != null && entity.assistantMessageId() == null) {
                entity.bindAssistant(turn.getAssistantMessageId());
            }
            return;
        }
        if ("succeeded".equals(entity.engineState())) {
            if (entity.finalText() == null || entity.finalText().isBlank()) {
                throw new ProductEngineControlException(502, "ENGINE_DELIVERY_MISSING");
            }
            AgentMessage assistant = messages.saveAndFlush(new AgentMessage(
                    entity.sessionId(), entity.userId(), "assistant", entity.finalText(), null, null));
            turn.complete(assistant.getId());
            entity.bindAssistant(assistant.getId());
        } else if ("cancelled".equals(entity.engineState())) {
            turn.cancel(entity.failureCode() == null ? "ENGINE_CANCELLED" : entity.failureCode());
        } else {
            turn.fail(null, entity.failureCode() == null ? "ENGINE_FAILED" : entity.failureCode());
        }
        turns.saveAndFlush(turn);
    }

    private boolean terminal(String state) {
        return "succeeded".equals(state) || "failed".equals(state) || "cancelled".equals(state);
    }

    private String write(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (Exception failure) { throw new IllegalStateException("receipt refs serialization failed", failure); }
    }

    List<String> receipts(ProductEngineTurnEntity entity) {
        try { return json.readValue(entity.receiptRefsJson(), new TypeReference<>() { }); }
        catch (Exception failure) { throw new IllegalStateException("receipt refs persistence is invalid", failure); }
    }

    record Begin(long entityId, long turnId, boolean replayed) { }
}
