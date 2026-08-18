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
@Table(name = "reactplan_model_completions", uniqueConstraints =
        @UniqueConstraint(name = "uk_reactplan_model_request", columnNames = {"task_id", "client_request_id"}))
class AgentEngineModelCompletionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_id", nullable = false, length = 69) private String taskId;
    @Column(name = "client_request_id", nullable = false, length = 125) private String clientRequestId;
    @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "response_json", columnDefinition = "LONGTEXT") private String responseJson;
    @Column(name = "provider_key", length = 64) private String providerKey;
    @Column(name = "model_name", length = 128) private String modelName;
    @Column(name = "request_bytes", nullable = false) private long requestBytes;
    @Column(name = "response_bytes", nullable = false) private long responseBytes;
    @Column(name = "prompt_tokens", nullable = false) private int promptTokens;
    @Column(name = "completion_tokens", nullable = false) private int completionTokens;
    @Column(name = "replay_count", nullable = false) private int replayCount;
    @Column(name = "error_code", length = 96) private String errorCode;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected AgentEngineModelCompletionEntity() { }
    AgentEngineModelCompletionEntity(String taskId, String clientRequestId, String requestDigest) {
        this(taskId, clientRequestId, requestDigest, null, null, 0);
    }
    AgentEngineModelCompletionEntity(String taskId, String clientRequestId, String requestDigest,
                                     String providerKey, String modelName, long requestBytes) {
        this.taskId = taskId; this.clientRequestId = clientRequestId; this.requestDigest = requestDigest;
        this.providerKey = providerKey; this.modelName = modelName; this.requestBytes = requestBytes;
        this.state = "PENDING"; this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt;
    }
    String requestDigest() { return requestDigest; }
    String state() { return state; }
    String responseJson() { return responseJson; }
    void replayed() { replayCount += 1; }
    void retry() { state = "PENDING"; responseJson = null; errorCode = null; replayCount += 1; updatedAt = LocalDateTime.now(); }
    void failed(String errorCode) { state = "FAILED"; this.errorCode = errorCode; updatedAt = LocalDateTime.now(); }
    void succeeded(String responseJson, long responseBytes, int promptTokens, int completionTokens) {
        state = "SUCCEEDED"; this.responseJson = responseJson; this.responseBytes = responseBytes;
        this.promptTokens = promptTokens; this.completionTokens = completionTokens;
        this.errorCode = null; updatedAt = LocalDateTime.now();
    }
    String taskId() { return taskId; }
    String clientRequestId() { return clientRequestId; }
    String providerKey() { return providerKey; }
    String modelName() { return modelName; }
    long requestBytes() { return requestBytes; }
    long responseBytes() { return responseBytes; }
    int promptTokens() { return promptTokens; }
    int completionTokens() { return completionTokens; }
    int replayCount() { return replayCount; }
    String errorCode() { return errorCode; }
    LocalDateTime createdAt() { return createdAt; }
    LocalDateTime updatedAt() { return updatedAt; }
}
