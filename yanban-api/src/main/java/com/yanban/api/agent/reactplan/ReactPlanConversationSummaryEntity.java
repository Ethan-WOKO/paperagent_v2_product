package com.yanban.api.agent.reactplan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "reactplan_conversation_summaries")
final class ReactPlanConversationSummaryEntity {
    @Id
    @Column(name = "session_id")
    private Long sessionId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Lob
    @Column(name = "summary_text", columnDefinition = "LONGTEXT")
    private String summaryText;
    @Column(name = "covered_intake_id", nullable = false)
    private long coveredIntakeId;
    @Column(name = "target_intake_id", nullable = false)
    private long targetIntakeId;
    @Column(name = "covered_turn_count", nullable = false)
    private int coveredTurnCount;
    @Column(name = "model_provider_snapshot", length = 64)
    private String modelProviderSnapshot;
    @Column(name = "model_snapshot", length = 128)
    private String modelSnapshot;
    @Column(nullable = false, length = 24)
    private String state;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;
    @Column(name = "last_error", length = 128)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ReactPlanConversationSummaryEntity() { }

    ReactPlanConversationSummaryEntity(long sessionId, long userId, long targetIntakeId,
                                       LocalDateTime now) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.targetIntakeId = targetIntakeId;
        this.state = "PENDING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    void request(long userId, long targetIntakeId, LocalDateTime now) {
        if (this.userId != userId) throw new IllegalStateException("ReAct summary owner mismatch");
        if (targetIntakeId > this.targetIntakeId) {
            this.targetIntakeId = targetIntakeId;
            state = "PENDING";
            leaseExpiresAt = null;
        }
        updatedAt = now;
    }

    void claim(LocalDateTime leaseUntil, LocalDateTime now) {
        state = "PROCESSING";
        attemptCount += 1;
        leaseExpiresAt = leaseUntil;
        updatedAt = now;
    }

    void requeue(LocalDateTime now) {
        state = "PENDING";
        leaseExpiresAt = null;
        updatedAt = now;
    }

    void succeed(String summary, long coveredIntakeId, int coveredTurnCount,
                 String provider, String model, boolean moreWork, LocalDateTime now) {
        this.summaryText = summary;
        this.coveredIntakeId = Math.max(this.coveredIntakeId, coveredIntakeId);
        this.coveredTurnCount = Math.max(this.coveredTurnCount, coveredTurnCount);
        this.modelProviderSnapshot = provider;
        this.modelSnapshot = model;
        this.state = moreWork ? "PENDING" : "READY";
        this.leaseExpiresAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    void noWork(LocalDateTime now) {
        state = "READY";
        leaseExpiresAt = null;
        lastError = null;
        updatedAt = now;
    }

    void fail(String error, LocalDateTime now) {
        state = "PENDING";
        leaseExpiresAt = now.plusSeconds(30);
        lastError = error == null ? "SUMMARY_FAILED" : error.substring(0, Math.min(128, error.length()));
        updatedAt = now;
    }

    long sessionId() { return sessionId; }
    long userId() { return userId; }
    String summaryText() { return summaryText; }
    long coveredIntakeId() { return coveredIntakeId; }
    long targetIntakeId() { return targetIntakeId; }
    int coveredTurnCount() { return coveredTurnCount; }
    String state() { return state; }
    LocalDateTime leaseExpiresAt() { return leaseExpiresAt; }
}
