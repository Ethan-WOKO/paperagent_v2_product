package com.yanban.api.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_memory_distillation_jobs")
class MemoryDistillationJobEntity {
    static final String TRIGGER_MANUAL = "MANUAL";
    static final String TRIGGER_AUTO = "AUTO";
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_NO_WORK = "NO_WORK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "from_message_id", nullable = false)
    private long fromMessageId;

    @Column(name = "through_message_id", nullable = false)
    private long throughMessageId;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "processed_through_message_id", nullable = false)
    private long processedThroughMessageId;

    @Column(name = "processed_message_count", nullable = false)
    private int processedMessageCount;

    @Column(name = "batch_attempt_count", nullable = false)
    private int batchAttemptCount;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "created_memory_count", nullable = false)
    private int createdMemoryCount;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MemoryDistillationJobEntity() { }

    MemoryDistillationJobEntity(long userId, String triggerType, long fromMessageId,
                                long throughMessageId, int messageCount, boolean hasWork, Instant now) {
        if (userId <= 0 || fromMessageId < 0 || throughMessageId < fromMessageId || messageCount < 0) {
            throw new IllegalArgumentException("invalid memory distillation job authority");
        }
        if (!TRIGGER_MANUAL.equals(triggerType) && !TRIGGER_AUTO.equals(triggerType)) {
            throw new IllegalArgumentException("invalid memory distillation trigger");
        }
        this.userId = userId;
        this.triggerType = triggerType;
        this.fromMessageId = fromMessageId;
        this.throughMessageId = throughMessageId;
        this.messageCount = messageCount;
        this.processedThroughMessageId = fromMessageId;
        this.status = hasWork ? STATUS_PENDING : STATUS_NO_WORK;
        this.candidateCount = 0;
        this.createdMemoryCount = 0;
        this.attemptCount = 0;
        this.finishedAt = hasWork ? null : now;
    }

    void claim(Instant now, Duration lease) {
        if (!STATUS_PENDING.equals(status)
                && !(STATUS_RUNNING.equals(status) && claimedUntil != null && !claimedUntil.isAfter(now))) {
            throw new IllegalStateException("MEMORY_DISTILLATION_JOB_NOT_CLAIMABLE");
        }
        status = STATUS_RUNNING;
        attemptCount++;
        batchAttemptCount++;
        startedAt = startedAt == null ? now : startedAt;
        claimedUntil = now.plus(lease);
        errorCode = null;
        errorMessage = null;
    }

    boolean owns(MemoryDistillationTransactions.Work work) {
        return STATUS_RUNNING.equals(status) && userId == work.userId()
                && attemptCount == work.attempt() && processedThroughMessageId == work.fromMessageId()
                && work.throughMessageId() > work.fromMessageId() && work.throughMessageId() <= throughMessageId;
    }

    void completeBatch(MemoryDistillationTransactions.Work work, int candidates, int created, Instant now) {
        requireRunning();
        processedThroughMessageId = work.throughMessageId();
        processedMessageCount += nonNegative(work.messageCount());
        candidateCount += nonNegative(candidates);
        createdMemoryCount += nonNegative(created);
        batchAttemptCount = 0;
        claimedUntil = null;
        boolean complete = processedThroughMessageId == throughMessageId;
        // Messages deleted after the snapshot are resolved by scanning past their IDs as well.
        if (complete) processedMessageCount = messageCount;
        status = complete ? STATUS_SUCCEEDED : STATUS_PENDING;
        finishedAt = complete ? now : null;
    }

    void fail(String code, String message, Instant now) {
        if (STATUS_SUCCEEDED.equals(status) || STATUS_NO_WORK.equals(status)) return;
        this.status = STATUS_FAILED;
        this.errorCode = limit(code, 64, "MEMORY_DISTILLATION_FAILED");
        this.errorMessage = limit(message, 512, "长期记忆沉淀失败");
        this.claimedUntil = null;
        this.finishedAt = now;
    }

    private void requireRunning() {
        if (!STATUS_RUNNING.equals(status)) throw new IllegalStateException("MEMORY_DISTILLATION_JOB_NOT_RUNNING");
    }

    private int nonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("count must not be negative");
        return value;
    }

    private String limit(String value, int max, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    Long id() { return id; }
    Long userId() { return userId; }
    String triggerType() { return triggerType; }
    String status() { return status; }
    long fromMessageId() { return fromMessageId; }
    long throughMessageId() { return throughMessageId; }
    int messageCount() { return messageCount; }
    long processedThroughMessageId() { return processedThroughMessageId; }
    int processedMessageCount() { return processedMessageCount; }
    int batchAttemptCount() { return batchAttemptCount; }
    int candidateCount() { return candidateCount; }
    int createdMemoryCount() { return createdMemoryCount; }
    int attemptCount() { return attemptCount; }
    Instant claimedUntil() { return claimedUntil; }
    String errorCode() { return errorCode; }
    String errorMessage() { return errorMessage; }
    Instant startedAt() { return startedAt; }
    Instant finishedAt() { return finishedAt; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
