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
    @Column(name = "usage_settled", nullable = false)
    private boolean usageSettled;
    @Column(name = "settled_prompt_tokens", nullable = false)
    private long settledPromptTokens;
    @Column(name = "settled_completion_tokens", nullable = false)
    private long settledCompletionTokens;
    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;
    @Column(name = "lease_token", length = 64)
    private String leaseToken;
    @Column(name = "lease_fence", nullable = false)
    private long leaseFence;
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;

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
        this.usageSettled = false;
        this.cancellationRequested = false;
    }

    void update(String state, long lastSequence, String checkpointJson, LocalDateTime now) {
        this.state = state;
        this.lastSequence = lastSequence;
        this.checkpointJson = checkpointJson;
        this.checkpointRevision += 1;
        this.updatedAt = now;
        if (!"running".equals(state)) releaseLease();
    }

    void claim(String owner, String token, LocalDateTime expiresAt, LocalDateTime now) {
        leaseOwner = owner;
        leaseToken = token;
        leaseFence += 1;
        leaseExpiresAt = expiresAt;
        updatedAt = now;
    }

    void renew(String owner, String token, long fence, LocalDateTime expiresAt, LocalDateTime now) {
        requireLease(owner, token, fence, now);
        leaseExpiresAt = expiresAt;
        updatedAt = now;
    }

    void requireLease(String owner, String token, long fence, LocalDateTime now) {
        if (!java.util.Objects.equals(leaseOwner, owner)
                || !java.util.Objects.equals(leaseToken, token)
                || leaseFence != fence || leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)) {
            throw new IllegalStateException("stale ReAct task lease");
        }
    }

    void requestCancellation(LocalDateTime now) {
        if (!java.util.Set.of("succeeded", "failed", "cancelled").contains(state)) {
            cancellationRequested = true;
            updatedAt = now;
        }
    }

    void releaseLease() {
        leaseOwner = null;
        leaseToken = null;
        leaseExpiresAt = null;
    }

    void settleUsage(long promptTokens, long completionTokens) {
        this.usageSettled = true;
        this.settledPromptTokens = Math.max(0L, promptTokens);
        this.settledCompletionTokens = Math.max(0L, completionTokens);
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
    LocalDateTime createdAt() { return createdAt; }
    LocalDateTime updatedAt() { return updatedAt; }
    boolean usageSettled() { return usageSettled; }
    long settledPromptTokens() { return settledPromptTokens; }
    long settledCompletionTokens() { return settledCompletionTokens; }
    String leaseOwner() { return leaseOwner; }
    long leaseFence() { return leaseFence; }
    LocalDateTime leaseExpiresAt() { return leaseExpiresAt; }
    boolean cancellationRequested() { return cancellationRequested; }
}
