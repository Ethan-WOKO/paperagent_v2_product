package com.yanban.api.agent.reactplan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "reactplan_task_checkpoints")
final class ReactPlanTaskCheckpointEntity {
    @Id
    @Column(name = "task_id", length = 69)
    private String taskId;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "turn_id", nullable = false)
    private Long turnId;
    @Column(name = "state", nullable = false, length = 32)
    private String state;
    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;
    @Column(name = "checkpoint_revision", nullable = false)
    private long checkpointRevision;
    @Lob
    @Column(name = "checkpoint_json", nullable = false, columnDefinition = "LONGTEXT")
    private String checkpointJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ReactPlanTaskCheckpointEntity() { }

    ReactPlanTaskCheckpointEntity(String taskId, String requestDigest, long userId,
                                  long sessionId, long turnId, String state,
                                  long lastSequence, String checkpointJson,
                                  LocalDateTime now) {
        this.taskId = taskId;
        this.requestDigest = requestDigest;
        this.userId = userId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.state = state;
        this.lastSequence = lastSequence;
        this.checkpointRevision = 1;
        this.checkpointJson = checkpointJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String state, long lastSequence, String checkpointJson, LocalDateTime now) {
        this.state = state;
        this.lastSequence = lastSequence;
        this.checkpointJson = checkpointJson;
        this.checkpointRevision += 1;
        this.updatedAt = now;
    }

    String taskId() { return taskId; }
    String requestDigest() { return requestDigest; }
    long userId() { return userId; }
    long sessionId() { return sessionId; }
    long turnId() { return turnId; }
    String state() { return state; }
    long lastSequence() { return lastSequence; }
    long checkpointRevision() { return checkpointRevision; }
    String checkpointJson() { return checkpointJson; }
}
