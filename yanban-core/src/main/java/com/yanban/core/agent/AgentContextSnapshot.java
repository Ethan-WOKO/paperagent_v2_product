package com.yanban.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "agent_context_snapshots")
public class AgentContextSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "revision_number", nullable = false)
    private Integer revisionNumber;

    @Column(name = "parent_snapshot_id")
    private Long parentSnapshotId;

    @Column(name = "context_stage", length = 32)
    private String contextStage;

    @Column(name = "stable_stage_key", length = 255)
    private String stableStageKey;

    @Column(name = "revision_status", length = 32)
    private String revisionStatus;

    @Column(name = "model_provider_snapshot", length = 64)
    private String modelProviderSnapshot;

    @Column(name = "model_snapshot", length = 128)
    private String modelSnapshot;

    @Column(name = "context_window_tokens")
    private Long contextWindowTokens;

    @Column(name = "max_output_tokens")
    private Long maxOutputTokens;

    @Column(name = "token_counter_version", length = 64)
    private String tokenCounterVersion;

    @Column(name = "profile_version", length = 64)
    private String profileVersion;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "output_reserve_tokens")
    private Long outputReserveTokens;

    @Column(name = "parent_digest", length = 64)
    private String parentDigest;

    @Column(name = "context_digest", length = 64)
    private String contextDigest;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Lob
    @Column(name = "sections_json", nullable = false, columnDefinition = "LONGTEXT")
    private String sectionsJson;

    @Lob
    @Column(name = "dropped_items_json", nullable = false, columnDefinition = "LONGTEXT")
    private String droppedItemsJson;

    @Column(name = "raw_message_count", nullable = false)
    private Integer rawMessageCount;

    @Column(name = "normalized_message_count", nullable = false)
    private Integer normalizedMessageCount;

    @Column(name = "context_message_count", nullable = false)
    private Integer contextMessageCount;

    @Column(name = "estimated_characters", nullable = false)
    private Integer estimatedCharacters;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentContextSnapshot() {
    }

    public AgentContextSnapshot(Long turnId,
                                Long sessionId,
                                Long userId,
                                String traceId,
                                String sectionsJson,
                                String droppedItemsJson,
                                Integer rawMessageCount,
                                Integer normalizedMessageCount,
                                Integer contextMessageCount,
                                Integer estimatedCharacters) {
        this.turnId = requireNonNull(turnId, "turnId");
        this.sessionId = requireNonNull(sessionId, "sessionId");
        this.userId = requireNonNull(userId, "userId");
        this.revisionNumber = 1;
        this.traceId = blankToNull(traceId);
        this.sectionsJson = requireText(sectionsJson, "sectionsJson");
        this.droppedItemsJson = requireText(droppedItemsJson, "droppedItemsJson");
        this.rawMessageCount = nonNegative(rawMessageCount);
        this.normalizedMessageCount = nonNegative(normalizedMessageCount);
        this.contextMessageCount = nonNegative(contextMessageCount);
        this.estimatedCharacters = nonNegative(estimatedCharacters);
    }

    public AgentContextSnapshot(Long turnId,
                                Long sessionId,
                                Long userId,
                                Integer revisionNumber,
                                Long parentSnapshotId,
                                String contextStage,
                                String stableStageKey,
                                String revisionStatus,
                                String modelProviderSnapshot,
                                String modelSnapshot,
                                Long contextWindowTokens,
                                Long maxOutputTokens,
                                String tokenCounterVersion,
                                String profileVersion,
                                Long totalTokens,
                                Long outputReserveTokens,
                                String parentDigest,
                                String contextDigest) {
        this.turnId = requireNonNull(turnId, "turnId");
        this.sessionId = requireNonNull(sessionId, "sessionId");
        this.userId = requireNonNull(userId, "userId");
        this.revisionNumber = positive(revisionNumber, "revisionNumber");
        this.parentSnapshotId = parentSnapshotId;
        this.contextStage = requireText(contextStage, "contextStage");
        this.stableStageKey = requireText(stableStageKey, "stableStageKey");
        this.revisionStatus = requireText(revisionStatus, "revisionStatus");
        this.modelProviderSnapshot = requireText(modelProviderSnapshot, "modelProviderSnapshot");
        this.modelSnapshot = requireText(modelSnapshot, "modelSnapshot");
        this.contextWindowTokens = positive(contextWindowTokens, "contextWindowTokens");
        this.maxOutputTokens = positive(maxOutputTokens, "maxOutputTokens");
        this.tokenCounterVersion = requireText(tokenCounterVersion, "tokenCounterVersion");
        this.profileVersion = requireText(profileVersion, "profileVersion");
        this.totalTokens = nonNegativeLong(totalTokens, "totalTokens");
        this.outputReserveTokens = nonNegativeLong(outputReserveTokens, "outputReserveTokens");
        this.parentDigest = blankToNull(parentDigest);
        this.contextDigest = requireDigest(contextDigest, "contextDigest");
        this.sectionsJson = "[]";
        this.droppedItemsJson = "[]";
        this.rawMessageCount = 0;
        this.normalizedMessageCount = 0;
        this.contextMessageCount = 0;
        this.estimatedCharacters = 0;
    }

    public Long getId() { return id; }
    public Long getTurnId() { return turnId; }
    public Long getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public Integer getRevisionNumber() { return revisionNumber; }
    public Long getParentSnapshotId() { return parentSnapshotId; }
    public String getContextStage() { return contextStage; }
    public String getStableStageKey() { return stableStageKey; }
    public String getRevisionStatus() { return revisionStatus; }
    public String getModelProviderSnapshot() { return modelProviderSnapshot; }
    public String getModelSnapshot() { return modelSnapshot; }
    public Long getContextWindowTokens() { return contextWindowTokens; }
    public Long getMaxOutputTokens() { return maxOutputTokens; }
    public String getTokenCounterVersion() { return tokenCounterVersion; }
    public String getProfileVersion() { return profileVersion; }
    public Long getTotalTokens() { return totalTokens; }
    public Long getOutputReserveTokens() { return outputReserveTokens; }
    public String getParentDigest() { return parentDigest; }
    public String getContextDigest() { return contextDigest; }
    public String getTraceId() { return traceId; }
    public String getSectionsJson() { return sectionsJson; }
    public String getDroppedItemsJson() { return droppedItemsJson; }
    public Integer getRawMessageCount() { return rawMessageCount; }
    public Integer getNormalizedMessageCount() { return normalizedMessageCount; }
    public Integer getContextMessageCount() { return contextMessageCount; }
    public Integer getEstimatedCharacters() { return estimatedCharacters; }
    public Instant getCreatedAt() { return createdAt; }

    private Long requireNonNull(Long value, String name) {
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private int positive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private long positive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private long nonNegativeLong(Long value, String name) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private String requireDigest(String value, String name) {
        String digest = requireText(value, name);
        if (!digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return digest;
    }
}
