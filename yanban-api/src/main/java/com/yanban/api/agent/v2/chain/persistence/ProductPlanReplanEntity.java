package com.yanban.api.agent.v2.chain.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "agent_v2_plan_replans",
        indexes = @Index(name = "idx_plan_replan_source",
                columnList = "plan_id,source_event_sequence"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_plan_replan_source",
                columnNames = {"plan_id", "source_event_sequence"}))
class ProductPlanReplanEntity {
    @Id
    @Column(name = "replan_event_id", nullable = false, length = 128)
    private String replanEventId;
    @Column(name = "task_id", nullable = false, length = 128)
    private String taskId;
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;
    @Column(name = "source_event_sequence", nullable = false)
    private long sourceEventSequence;
    @Column(name = "source_revision_id", nullable = false, length = 128)
    private String sourceRevisionId;
    @Column(name = "source_revision_number", nullable = false)
    private long sourceRevisionNumber;
    @Column(name = "result_revision_id", nullable = false, length = 128)
    private String resultRevisionId;
    @Column(name = "result_revision_number", nullable = false)
    private long resultRevisionNumber;
    @Column(name = "source_checkpoint_version", nullable = false)
    private long sourceCheckpointVersion;
    @Column(name = "result_checkpoint_version", nullable = false)
    private long resultCheckpointVersion;
    @Column(name = "result_event_sequence", nullable = false)
    private long resultEventSequence;
    @Column(name = "lease_owner", nullable = false, length = 255)
    private String leaseOwner;
    @Column(name = "fence_token", nullable = false)
    private long fenceToken;
    @Column(name = "request_format_version", nullable = false)
    private int requestFormatVersion;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "request_json", nullable = false,
            columnDefinition = "LONGTEXT")
    private String requestJson;
    @Column(name = "result_format_version", nullable = false)
    private int resultFormatVersion;
    @Column(name = "result_sha256", nullable = false, length = 64)
    private String resultSha256;
    @Column(name = "result_json", nullable = false,
            columnDefinition = "LONGTEXT")
    private String resultJson;
    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    protected ProductPlanReplanEntity() {
    }

    ProductPlanReplanEntity(
            String taskId, String planId, long sourceEventSequence,
            String sourceRevisionId, long sourceRevisionNumber,
            String resultRevisionId, long resultRevisionNumber,
            long sourceCheckpointVersion, long resultCheckpointVersion,
            String replanEventId, long resultEventSequence,
            String leaseOwner, long fenceToken,
            ProductPlanReplanCodec.EncodedPayload request,
            ProductPlanReplanCodec.EncodedPayload result,
            Instant committedAt) {
        this.taskId = taskId;
        this.planId = planId;
        this.sourceEventSequence = sourceEventSequence;
        this.sourceRevisionId = sourceRevisionId;
        this.sourceRevisionNumber = sourceRevisionNumber;
        this.resultRevisionId = resultRevisionId;
        this.resultRevisionNumber = resultRevisionNumber;
        this.sourceCheckpointVersion = sourceCheckpointVersion;
        this.resultCheckpointVersion = resultCheckpointVersion;
        this.replanEventId = replanEventId;
        this.resultEventSequence = resultEventSequence;
        this.leaseOwner = leaseOwner;
        this.fenceToken = fenceToken;
        this.requestFormatVersion = request.formatVersion();
        this.requestSha256 = request.sha256();
        this.requestJson = request.json();
        this.resultFormatVersion = result.formatVersion();
        this.resultSha256 = result.sha256();
        this.resultJson = result.json();
        this.committedAt = committedAt;
    }

    String replanEventId() { return replanEventId; }
    String taskId() { return taskId; }
    String planId() { return planId; }
    long sourceEventSequence() { return sourceEventSequence; }
    String sourceRevisionId() { return sourceRevisionId; }
    long sourceRevisionNumber() { return sourceRevisionNumber; }
    String resultRevisionId() { return resultRevisionId; }
    long resultRevisionNumber() { return resultRevisionNumber; }
    long sourceCheckpointVersion() { return sourceCheckpointVersion; }
    long resultCheckpointVersion() { return resultCheckpointVersion; }
    long resultEventSequence() { return resultEventSequence; }
    String leaseOwner() { return leaseOwner; }
    long fenceToken() { return fenceToken; }
    int requestFormatVersion() { return requestFormatVersion; }
    String requestSha256() { return requestSha256; }
    String requestJson() { return requestJson; }
    int resultFormatVersion() { return resultFormatVersion; }
    String resultSha256() { return resultSha256; }
    String resultJson() { return resultJson; }
    Instant committedAt() { return committedAt; }
}
