package com.yanban.api.agent.reactplan.gateway;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
class AgentEngineModelCompletionTransactions {
    private final AgentEngineModelCompletionRepository repository;
    AgentEngineModelCompletionTransactions(AgentEngineModelCompletionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<String> claim(String taskId, String clientRequestId, String requestDigest) {
        return claim(taskId, clientRequestId, requestDigest, null, null, 0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<String> claim(String taskId, String clientRequestId, String requestDigest,
                           String providerKey, String modelName, long requestBytes) {
        Optional<AgentEngineModelCompletionEntity> found = repository.lock(taskId, clientRequestId);
        if (found.isEmpty()) {
            repository.saveAndFlush(new AgentEngineModelCompletionEntity(
                    taskId, clientRequestId, requestDigest, providerKey, modelName, requestBytes));
            return Optional.empty();
        }
        AgentEngineModelCompletionEntity value = found.orElseThrow();
        if (!value.requestDigest().equals(requestDigest)) throw EngineGatewayException.conflict("MODEL_REQUEST_DIGEST_CONFLICT");
        if ("SUCCEEDED".equals(value.state())) {
            value.replayed(); repository.saveAndFlush(value);
            return Optional.of(value.responseJson());
        }
        // A durable PENDING row can outlive the gateway process that owned the
        // network call. Reclaim the same deterministic model request so a
        // restarted Engine can continue instead of permanently failing.
        value.retry(); repository.saveAndFlush(value); return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void succeed(String taskId, String clientRequestId, String responseJson,
                 int promptTokens, int completionTokens) {
        AgentEngineModelCompletionEntity value = repository.lock(taskId, clientRequestId).orElseThrow();
        if ("SUCCEEDED".equals(value.state())) return;
        value.succeeded(responseJson, responseJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                promptTokens, completionTokens);
        repository.saveAndFlush(value);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(String taskId, String clientRequestId) { fail(taskId, clientRequestId, "MODEL_COMPLETION_FAILED"); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(String taskId, String clientRequestId, String errorCode) {
        repository.lock(taskId, clientRequestId).ifPresent(value -> {
            value.failed(errorCode); repository.saveAndFlush(value);
        });
    }
}
