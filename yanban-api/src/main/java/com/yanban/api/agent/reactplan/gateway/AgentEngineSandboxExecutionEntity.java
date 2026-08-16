package com.yanban.api.agent.reactplan.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(name = "reactplan_sandbox_executions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_engine_sandbox_request", columnNames = {"task_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_engine_sandbox_execution_ref", columnNames = "execution_ref"),
        @UniqueConstraint(name = "uk_engine_sandbox_receipt_ref", columnNames = "receipt_ref")})
class AgentEngineSandboxExecutionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false, length = 69)
    private String taskId;
    @Column(name = "client_request_id", nullable = false, length = 125)
    private String clientRequestId;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @Column(name = "semantic_digest", nullable = false, length = 64)
    private String semanticDigest;
    @Column(name = "execution_ref", nullable = false, length = 80)
    private String executionRef;
    @Column(name = "broker_execution_ref", length = 256)
    private String brokerExecutionRef;
    @Column(nullable = false, length = 32)
    private String state;
    @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;
    @Column(name = "receipt_ref", length = 80)
    private String receiptRef;
    @Column(name = "receipt_json", columnDefinition = "LONGTEXT")
    private String receiptJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AgentEngineSandboxExecutionEntity() { }

    AgentEngineSandboxExecutionEntity(String taskId, String clientRequestId,
                                      String requestDigest, String semanticDigest,
                                      String executionRef, String requestJson,
                                      LocalDateTime now) {
        this.taskId = taskId;
        this.clientRequestId = clientRequestId;
        this.requestDigest = requestDigest;
        this.semanticDigest = semanticDigest;
        this.executionRef = executionRef;
        this.requestJson = requestJson;
        this.state = "QUEUED";
        this.createdAt = now;
        this.updatedAt = now;
    }

    String taskId() { return taskId; }
    String clientRequestId() { return clientRequestId; }
    String requestDigest() { return requestDigest; }
    String semanticDigest() { return semanticDigest; }
    String executionRef() { return executionRef; }
    String brokerExecutionRef() { return brokerExecutionRef; }
    String state() { return state; }
    String requestJson() { return requestJson; }
    String receiptRef() { return receiptRef; }
    String receiptJson() { return receiptJson; }

    void dispatched(String brokerExecutionRef, String state, LocalDateTime now) {
        if (this.brokerExecutionRef != null && !this.brokerExecutionRef.equals(brokerExecutionRef)) {
            throw new IllegalStateException("broker execution identity is immutable");
        }
        this.brokerExecutionRef = brokerExecutionRef;
        this.state = state;
        this.updatedAt = now;
    }

    void terminal(String state, String receiptRef, String receiptJson, LocalDateTime now) {
        if (this.receiptRef != null && (!this.receiptRef.equals(receiptRef)
                || !this.receiptJson.equals(receiptJson))) {
            throw new IllegalStateException("engine sandbox receipt is immutable");
        }
        this.state = state;
        this.receiptRef = receiptRef;
        this.receiptJson = receiptJson;
        this.updatedAt = now;
    }
}
