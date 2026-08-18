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
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected AgentEngineModelCompletionEntity() { }
    AgentEngineModelCompletionEntity(String taskId, String clientRequestId, String requestDigest) {
        this.taskId = taskId; this.clientRequestId = clientRequestId; this.requestDigest = requestDigest;
        this.state = "PENDING"; this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt;
    }
    String requestDigest() { return requestDigest; }
    String state() { return state; }
    String responseJson() { return responseJson; }
    void retry() { state = "PENDING"; responseJson = null; updatedAt = LocalDateTime.now(); }
    void failed() { state = "FAILED"; updatedAt = LocalDateTime.now(); }
    void succeeded(String responseJson) { state = "SUCCEEDED"; this.responseJson = responseJson; updatedAt = LocalDateTime.now(); }
}
