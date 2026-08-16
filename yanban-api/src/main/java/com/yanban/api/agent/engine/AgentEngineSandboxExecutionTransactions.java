package com.yanban.api.agent.engine;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
class AgentEngineSandboxExecutionTransactions {
    private final AgentEngineSandboxExecutionRepository repository;

    AgentEngineSandboxExecutionTransactions(AgentEngineSandboxExecutionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AgentEngineSandboxExecutionEntity create(AgentEngineSandboxExecutionEntity value) {
        return repository.saveAndFlush(value);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    Optional<AgentEngineSandboxExecutionEntity> find(String taskId, String clientRequestId) {
        return repository.findByTaskIdAndClientRequestId(taskId, clientRequestId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    Optional<AgentEngineSandboxExecutionEntity> findReceipt(String taskId, String receiptRef) {
        return repository.findByTaskIdAndReceiptRef(taskId, receiptRef);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AgentEngineSandboxExecutionEntity dispatched(
            String taskId, String clientRequestId, String brokerRef, String state) {
        AgentEngineSandboxExecutionEntity value = repository.lock(taskId, clientRequestId).orElseThrow();
        value.dispatched(brokerRef, state, LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(value);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AgentEngineSandboxExecutionEntity terminal(
            String taskId, String clientRequestId, String state,
            String receiptRef, String receiptJson) {
        AgentEngineSandboxExecutionEntity value = repository.lock(taskId, clientRequestId).orElseThrow();
        value.terminal(state, receiptRef, receiptJson, LocalDateTime.now(ZoneOffset.UTC));
        return repository.saveAndFlush(value);
    }
}
