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
@Table(name = "reactplan_registered_tool_calls", uniqueConstraints =
        @UniqueConstraint(name = "uk_reactplan_registered_tool_call", columnNames = {"task_id", "call_id"}))
class AgentEngineRegisteredToolCallEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_id", nullable = false, length = 69) private String taskId;
    @Column(name = "call_id", nullable = false, length = 45) private String callId;
    @Column(name = "tool_name", nullable = false, length = 64) private String toolName;
    @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "response_json", columnDefinition = "LONGTEXT") private String responseJson;
    @Column(name = "error_code", length = 96) private String errorCode;
    @Column(name = "replay_count", nullable = false) private int replayCount;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected AgentEngineRegisteredToolCallEntity() { }

    AgentEngineRegisteredToolCallEntity(String taskId, String callId,
                                        String toolName, String requestDigest) {
        this.taskId = taskId; this.callId = callId; this.toolName = toolName;
        this.requestDigest = requestDigest; this.state = "PENDING";
        this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt;
    }

    String toolName() { return toolName; }
    String requestDigest() { return requestDigest; }
    String state() { return state; }
    String responseJson() { return responseJson; }
    void replayed() { replayCount += 1; updatedAt = LocalDateTime.now(); }
    void retry() { state = "PENDING"; responseJson = null; errorCode = null; replayed(); }
    void completed(String response) {
        state = "COMPLETED"; responseJson = response; errorCode = null;
        updatedAt = LocalDateTime.now();
    }
    void failed(String code) {
        state = "FAILED"; errorCode = code; updatedAt = LocalDateTime.now();
    }
}
