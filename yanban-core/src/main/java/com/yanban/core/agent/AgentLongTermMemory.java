package com.yanban.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_long_term_memories")
public class AgentLongTermMemory {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String CONFIRMATION_UNCONFIRMED = "UNCONFIRMED";
    public static final String CONFIRMATION_CONFIRMED = "CONFIRMED";
    public static final String CONFIRMATION_REJECTED = "REJECTED";
    public static final String CONFIRMED_SOURCE_USER_ACTION = "USER_ACTION";
    public static final String PROVENANCE_USER_MESSAGE = "USER_MESSAGE";
    public static final String PROVENANCE_USER_SETTINGS_ACTION = "USER_SETTINGS_ACTION";
    public static final String SOURCE_USER_CONFIRMED = "USER_CONFIRMED";
    public static final String SOURCE_USER_CORRECTED = "USER_CORRECTED";
    public static final String SOURCE_LLM_DISTILLED = "LLM_DISTILLED";
    public static final String TYPE_FACT = "FACT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(nullable = false, length = 32)
    private String scope;

    @Column(name = "memory_type", nullable = false, length = 64)
    private String memoryType;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Lob
    @Column(name = "tags_json", columnDefinition = "LONGTEXT")
    private String tagsJson;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(name = "source_ref_id", length = 128)
    private String sourceRefId;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "confirmation_status", nullable = false, length = 32)
    private String confirmationStatus;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_source", length = 64)
    private String confirmedSource;

    @Column(name = "provenance_type", length = 64)
    private String provenanceType;

    @Column(name = "provenance_ref", length = 255)
    private String provenanceRef;

    @Column(name = "project_version", length = 64)
    private String projectVersion;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_reason", length = 512)
    private String invalidationReason;

    @Column(name = "supersedes_memory_id")
    private Long supersedesMemoryId;

    @Column(name = "superseded_by_memory_id")
    private Long supersededByMemoryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AgentLongTermMemory() {
    }

    public AgentLongTermMemory(Long userId,
                               Long projectId,
                               String scope,
                               String memoryType,
                               String content,
                               String tagsJson,
                               String sourceType,
                               String sourceRefId,
                               BigDecimal confidence,
                               Long supersedesMemoryId) {
        this.userId = requireNonNull(userId, "userId");
        this.projectId = projectId;
        this.scope = normalize(scope, SCOPE_USER);
        this.memoryType = normalize(memoryType, TYPE_FACT);
        this.content = requireText(content, "content");
        this.tagsJson = blankToNull(tagsJson);
        this.sourceType = normalize(sourceType, SOURCE_USER_CONFIRMED);
        this.sourceRefId = blankToNull(sourceRefId);
        this.confidence = normalizeConfidence(confidence);
        this.status = STATUS_ACTIVE;
        this.confirmationStatus = CONFIRMATION_UNCONFIRMED;
        this.supersedesMemoryId = supersedesMemoryId;
    }

    public void markDeleted() {
        this.status = STATUS_DELETED;
        this.deletedAt = Instant.now();
    }

    public void markSuperseded(Long supersededByMemoryId) {
        this.status = STATUS_SUPERSEDED;
        this.supersededByMemoryId = requireNonNull(supersededByMemoryId, "supersededByMemoryId");
    }

    public void bindProjectVersion(String projectVersion) {
        this.projectVersion = requireText(projectVersion, "projectVersion");
    }

    public void confirm(Instant confirmedAt,
                        String confirmedSource,
                        String provenanceType,
                        String provenanceRef) {
        this.confirmationStatus = CONFIRMATION_CONFIRMED;
        this.confirmedAt = requireNonNull(confirmedAt, "confirmedAt");
        this.confirmedSource = requireText(confirmedSource, "confirmedSource");
        this.provenanceType = requireText(provenanceType, "provenanceType");
        this.provenanceRef = requireText(provenanceRef, "provenanceRef");
    }

    public void reject() {
        this.confirmationStatus = CONFIRMATION_REJECTED;
        this.confirmedAt = null;
        this.confirmedSource = null;
        this.provenanceType = null;
        this.provenanceRef = null;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getProjectId() { return projectId; }
    public String getScope() { return scope; }
    public String getMemoryType() { return memoryType; }
    public String getContent() { return content; }
    public String getTagsJson() { return tagsJson; }
    public String getSourceType() { return sourceType; }
    public String getSourceRefId() { return sourceRefId; }
    public BigDecimal getConfidence() { return confidence; }
    public String getStatus() { return status; }
    public String getConfirmationStatus() { return confirmationStatus; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public String getConfirmedSource() { return confirmedSource; }
    public String getProvenanceType() { return provenanceType; }
    public String getProvenanceRef() { return provenanceRef; }
    public String getProjectVersion() { return projectVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getInvalidatedAt() { return invalidatedAt; }
    public String getInvalidationReason() { return invalidationReason; }
    public Long getSupersedesMemoryId() { return supersedesMemoryId; }
    public Long getSupersededByMemoryId() { return supersededByMemoryId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }

    private <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal normalizeConfidence(BigDecimal value) {
        if (value == null) {
            return BigDecimal.valueOf(0.5);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }
}
