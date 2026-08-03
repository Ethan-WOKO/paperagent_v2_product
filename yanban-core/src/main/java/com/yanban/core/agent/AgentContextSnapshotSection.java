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
@Table(name = "agent_context_snapshot_sections")
public class AgentContextSnapshotSection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;
    @Column(name = "section_ordinal", nullable = false)
    private Integer sectionOrdinal;
    @Column(name = "section_type", nullable = false, length = 64)
    private String sectionType;
    @Column(name = "fixed_percentage", nullable = false)
    private Integer fixedPercentage;
    @Column(name = "token_limit", nullable = false)
    private Long tokenLimit;
    @Column(name = "tokens_before", nullable = false)
    private Long tokensBefore;
    @Column(name = "tokens_after", nullable = false)
    private Long tokensAfter;
    @Column(name = "section_status", nullable = false, length = 32)
    private String sectionStatus;
    @Lob
    @Column(name = "source_refs_json", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceRefsJson;
    @Lob
    @Column(name = "projection_json", nullable = false, columnDefinition = "LONGTEXT")
    private String projectionJson;
    @Column(name = "projection_digest", nullable = false, length = 64)
    private String projectionDigest;
    @Column(name = "compaction_reason", length = 255)
    private String compactionReason;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentContextSnapshotSection() { }

    public AgentContextSnapshotSection(
            Long snapshotId, Integer sectionOrdinal, String sectionType,
            Integer fixedPercentage, Long tokenLimit, Long tokensBefore,
            Long tokensAfter, String sectionStatus, String sourceRefsJson,
            String projectionJson, String projectionDigest,
            String compactionReason) {
        this.snapshotId = required(snapshotId, "snapshotId");
        if (sectionOrdinal == null || sectionOrdinal < 0) {
            throw new IllegalArgumentException("sectionOrdinal must not be negative");
        }
        this.sectionOrdinal = sectionOrdinal;
        this.sectionType = text(sectionType, "sectionType");
        if (fixedPercentage == null || fixedPercentage <= 0 || fixedPercentage > 100) {
            throw new IllegalArgumentException("fixedPercentage is invalid");
        }
        this.fixedPercentage = fixedPercentage;
        this.tokenLimit = nonNegative(tokenLimit, "tokenLimit");
        this.tokensBefore = nonNegative(tokensBefore, "tokensBefore");
        this.tokensAfter = nonNegative(tokensAfter, "tokensAfter");
        this.sectionStatus = text(sectionStatus, "sectionStatus");
        this.sourceRefsJson = text(sourceRefsJson, "sourceRefsJson");
        this.projectionJson = text(projectionJson, "projectionJson");
        this.projectionDigest = digest(projectionDigest);
        this.compactionReason = compactionReason == null || compactionReason.isBlank()
                ? null : compactionReason.trim();
    }

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public Integer getSectionOrdinal() { return sectionOrdinal; }
    public String getSectionType() { return sectionType; }
    public Integer getFixedPercentage() { return fixedPercentage; }
    public Long getTokenLimit() { return tokenLimit; }
    public Long getTokensBefore() { return tokensBefore; }
    public Long getTokensAfter() { return tokensAfter; }
    public String getSectionStatus() { return sectionStatus; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public String getProjectionJson() { return projectionJson; }
    public String getProjectionDigest() { return projectionDigest; }
    public String getCompactionReason() { return compactionReason; }
    public Instant getCreatedAt() { return createdAt; }

    private static Long required(Long value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    private static long nonNegative(Long value, String field) {
        if (value == null || value < 0) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }
    private static String digest(String value) {
        String result = text(value, "projectionDigest");
        if (!result.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("projectionDigest must be lowercase SHA-256");
        return result;
    }
}
