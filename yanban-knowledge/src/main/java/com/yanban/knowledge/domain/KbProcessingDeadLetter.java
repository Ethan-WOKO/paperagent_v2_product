package com.yanban.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "kb_processing_dead_letters")
public class KbProcessingDeadLetter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "original_event_id", length = 64)
    private String originalEventId;
    @Column(name = "document_id")
    private Long documentId;
    @Column(name = "message_key", length = 128)
    private String messageKey;
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;
    @Column(name = "error_type", length = 255)
    private String errorType;
    @Column(name = "error_message", length = 512)
    private String errorMessage;
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "redrive_event_id", length = 64)
    private String redriveEventId;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "redriven_at")
    private Instant redrivenAt;

    protected KbProcessingDeadLetter() {}
    public KbProcessingDeadLetter(String originalEventId, Long documentId, String messageKey,
                                  String payloadJson, String errorType, String errorMessage, int retryCount) {
        this.originalEventId = originalEventId;
        this.documentId = documentId;
        this.messageKey = messageKey;
        this.payloadJson = payloadJson;
        this.errorType = errorType;
        this.errorMessage = limit(errorMessage);
        this.retryCount = retryCount;
        this.status = "PENDING";
    }
    public Long getId() { return id; }
    public String getOriginalEventId() { return originalEventId; }
    public Long getDocumentId() { return documentId; }
    public String getMessageKey() { return messageKey; }
    public String getPayloadJson() { return payloadJson; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public Integer getRetryCount() { return retryCount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void redriven(String eventId, Instant now) { status = "REDRIVEN"; redriveEventId = eventId; redrivenAt = now; }
    private String limit(String value) { return value == null ? null : value.substring(0, Math.min(512, value.length())); }
}
