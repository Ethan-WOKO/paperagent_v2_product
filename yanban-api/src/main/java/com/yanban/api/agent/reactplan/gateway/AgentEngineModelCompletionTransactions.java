package com.yanban.api.agent.reactplan.gateway;

import com.yanban.api.quota.UserQuotaService;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
class AgentEngineModelCompletionTransactions {
    private final AgentEngineModelCompletionRepository repository;
    private final UserQuotaService quotas;
    AgentEngineModelCompletionTransactions(AgentEngineModelCompletionRepository repository,
                                           UserQuotaService quotas) {
        this.repository = repository; this.quotas = quotas;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<String> claim(String taskId, String clientRequestId, String requestDigest) {
        Optional<AgentEngineModelCompletionEntity> found = repository.lock(taskId, clientRequestId);
        if (found.isEmpty()) {
            repository.saveAndFlush(new AgentEngineModelCompletionEntity(taskId, clientRequestId, requestDigest));
            return Optional.empty();
        }
        AgentEngineModelCompletionEntity value = found.orElseThrow();
        if (!value.requestDigest().equals(requestDigest)) throw EngineGatewayException.conflict("MODEL_REQUEST_DIGEST_CONFLICT");
        if ("SUCCEEDED".equals(value.state())) return Optional.of(value.responseJson());
        if ("PENDING".equals(value.state())) throw EngineGatewayException.conflict("MODEL_COMPLETION_IN_PROGRESS");
        value.retry(); repository.saveAndFlush(value); return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void succeed(String taskId, String clientRequestId, String responseJson, long userId,
                 int promptTokens, int completionTokens) {
        AgentEngineModelCompletionEntity value = repository.lock(taskId, clientRequestId).orElseThrow();
        if ("SUCCEEDED".equals(value.state())) return;
        value.succeeded(responseJson);
        repository.saveAndFlush(value);
        quotas.recordUsage(userId, "REACT_PLAN", promptTokens, completionTokens, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(String taskId, String clientRequestId) {
        repository.lock(taskId, clientRequestId).ifPresent(value -> { value.failed(); repository.saveAndFlush(value); });
    }
}
