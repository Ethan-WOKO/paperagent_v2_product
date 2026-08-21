package com.yanban.api.agent.reactplan.gateway;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
class AgentEngineRegisteredToolTransactions {
    private final AgentEngineRegisteredToolCallRepository repository;

    AgentEngineRegisteredToolTransactions(AgentEngineRegisteredToolCallRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<String> claim(String taskId, String callId, String toolName,
                           String requestDigest, boolean safelyReplayable) {
        Optional<AgentEngineRegisteredToolCallEntity> found = repository.lock(taskId, callId);
        if (found.isEmpty()) {
            repository.saveAndFlush(new AgentEngineRegisteredToolCallEntity(
                    taskId, callId, toolName, requestDigest));
            return Optional.empty();
        }
        AgentEngineRegisteredToolCallEntity value = found.orElseThrow();
        if (!value.toolName().equals(toolName)
                || !value.requestDigest().equals(requestDigest)) {
            throw EngineGatewayException.conflict("REGISTERED_TOOL_REQUEST_CONFLICT");
        }
        if ("COMPLETED".equals(value.state())) {
            value.replayed(); repository.saveAndFlush(value);
            return Optional.of(value.responseJson());
        }
        if (!safelyReplayable) {
            throw EngineGatewayException.conflict("REGISTERED_TOOL_OUTCOME_UNKNOWN");
        }
        value.retry(); repository.saveAndFlush(value);
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(String taskId, String callId, String responseJson) {
        AgentEngineRegisteredToolCallEntity value = repository.lock(taskId, callId).orElseThrow();
        if ("COMPLETED".equals(value.state())) return;
        value.completed(responseJson); repository.saveAndFlush(value);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(String taskId, String callId, String errorCode) {
        repository.lock(taskId, callId).ifPresent(value -> {
            value.failed(errorCode); repository.saveAndFlush(value);
        });
    }
}
