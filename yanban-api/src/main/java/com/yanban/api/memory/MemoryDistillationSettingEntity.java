package com.yanban.api.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_memory_distillation_settings")
class MemoryDistillationSettingEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "auto_enabled", nullable = false)
    private boolean autoEnabled;

    @Column(name = "last_processed_message_id", nullable = false)
    private long lastProcessedMessageId;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MemoryDistillationSettingEntity() { }

    MemoryDistillationSettingEntity(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        this.userId = userId;
        this.autoEnabled = false;
        this.lastProcessedMessageId = 0L;
    }

    void updateAutoEnabled(boolean enabled, Instant now, Duration interval) {
        this.autoEnabled = enabled;
        this.nextRunAt = enabled ? now.plus(interval) : null;
    }

    void scheduleNext(Instant now, Duration interval) {
        this.nextRunAt = autoEnabled ? now.plus(interval) : null;
    }

    void advance(long expectedCursor, long throughMessageId, Instant now, Duration interval) {
        if (lastProcessedMessageId != expectedCursor) {
            throw new IllegalStateException("MEMORY_DISTILLATION_CURSOR_CHANGED");
        }
        if (throughMessageId < expectedCursor) {
            throw new IllegalArgumentException("throughMessageId must not move backwards");
        }
        lastProcessedMessageId = throughMessageId;
        lastSuccessAt = now;
        scheduleNext(now, interval);
    }

    Long userId() { return userId; }
    boolean autoEnabled() { return autoEnabled; }
    long lastProcessedMessageId() { return lastProcessedMessageId; }
    Instant nextRunAt() { return nextRunAt; }
    Instant lastSuccessAt() { return lastSuccessAt; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
