package com.yanban.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "kb_processing_outbox")
public class KbProcessingOutboxEvent {
    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;
    @Column(name = "document_id", nullable = false)
    private Long documentId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(name = "aggregate_key", nullable = false, length = 128)
    private String aggregateKey;
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;
    @Column(name = "last_error", length = 512)
    private String lastError;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbProcessingOutboxEvent() {}

    public KbProcessingOutboxEvent(String eventId, Long documentId, Long userId,
                                   String aggregateKey, String payloadJson) {
        this.eventId = eventId;
        this.documentId = documentId;
        this.userId = userId;
        this.eventType = "KNOWLEDGE_DOCUMENT_PROCESS";
        this.aggregateKey = aggregateKey;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public Long getDocumentId() { return documentId; }
    public Long getUserId() { return userId; }
    public String getAggregateKey() { return aggregateKey; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public Integer getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void claim() { status = "DISPATCHING"; }
    public void dispatched(Instant now) { status = "DISPATCHED"; dispatchedAt = now; lastError = null; }
    public void failed(Instant now, int maxAttempts, String error) {
        attemptCount = (attemptCount == null ? 0 : attemptCount) + 1;
        lastError = limit(error);
        if (attemptCount >= maxAttempts) {
            status = "DEAD";
            nextAttemptAt = now;
        } else {
            status = "RETRY";
            long seconds = Math.min(300L, 1L << Math.min(8, attemptCount));
            nextAttemptAt = now.plusSeconds(seconds);
        }
    }
    public void recoverStale(Instant now) { status = "RETRY"; nextAttemptAt = now; }

    private String limit(String value) {
        if (value == null || value.isBlank()) return "Kafka dispatch failed";
        return value.length() > 512 ? value.substring(0, 512) : value;
    }
}
