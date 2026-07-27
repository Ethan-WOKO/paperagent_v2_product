package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "agent_v2_step_interruptions",
        indexes = @Index(
                name = "idx_agent_v2_step_interruptions_plan",
                columnList = "plan_id",
                unique = true))
class ProductStepInterruptionEntity {
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;
    @Column(name = "step_id", nullable = false, length = 128)
    private String stepId;
    @Id
    @Column(name = "interruption_event_id", nullable = false, length = 128)
    private String interruptionEventId;
    @Column(name = "interruption_kind", nullable = false, length = 16)
    private String interruptionKind;
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
    @Column(name = "source_event_sequence", nullable = false)
    private long sourceEventSequence;
    @Column(name = "result_event_sequence", nullable = false)
    private long resultEventSequence;
    @Column(name = "lease_owner_id", nullable = false)
    private String leaseOwnerId;
    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;
    @Column(name = "request_format_version", nullable = false)
    private int requestFormatVersion;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;
    @Column(name = "result_format_version", nullable = false)
    private int resultFormatVersion;
    @Column(name = "result_sha256", nullable = false, length = 64)
    private String resultSha256;
    @Column(name = "result_json", nullable = false, columnDefinition = "LONGTEXT")
    private String resultJson;
    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    protected ProductStepInterruptionEntity() {
    }

    ProductStepInterruptionEntity(
            String planId, String stepId, String eventId, String kind,
            String sourceRevisionId, long sourceRevisionNumber,
            String resultRevisionId, long resultRevisionNumber,
            long sourceCheckpointVersion, long resultCheckpointVersion,
            long sourceEventSequence, long resultEventSequence,
            String leaseOwnerId, long fencingToken,
            ProductStepInterruptionCodec.EncodedPayload request,
            ProductStepInterruptionCodec.EncodedPayload result,
            Instant committedAt) {
        this.planId = planId;
        this.stepId = stepId;
        this.interruptionEventId = eventId;
        this.interruptionKind = kind;
        this.sourceRevisionId = sourceRevisionId;
        this.sourceRevisionNumber = sourceRevisionNumber;
        this.resultRevisionId = resultRevisionId;
        this.resultRevisionNumber = resultRevisionNumber;
        this.sourceCheckpointVersion = sourceCheckpointVersion;
        this.resultCheckpointVersion = resultCheckpointVersion;
        this.sourceEventSequence = sourceEventSequence;
        this.resultEventSequence = resultEventSequence;
        this.leaseOwnerId = leaseOwnerId;
        this.fencingToken = fencingToken;
        this.requestFormatVersion = request.formatVersion();
        this.requestSha256 = request.sha256();
        this.requestJson = request.json();
        this.resultFormatVersion = result.formatVersion();
        this.resultSha256 = result.sha256();
        this.resultJson = result.json();
        this.committedAt = committedAt;
    }

    String planId() { return planId; }
    String stepId() { return stepId; }
    String interruptionEventId() { return interruptionEventId; }
    String interruptionKind() { return interruptionKind; }
    String sourceRevisionId() { return sourceRevisionId; }
    long sourceRevisionNumber() { return sourceRevisionNumber; }
    String resultRevisionId() { return resultRevisionId; }
    long resultRevisionNumber() { return resultRevisionNumber; }
    long sourceCheckpointVersion() { return sourceCheckpointVersion; }
    long resultCheckpointVersion() { return resultCheckpointVersion; }
    long sourceEventSequence() { return sourceEventSequence; }
    long resultEventSequence() { return resultEventSequence; }
    String leaseOwnerId() { return leaseOwnerId; }
    long fencingToken() { return fencingToken; }
    int requestFormatVersion() { return requestFormatVersion; }
    String requestSha256() { return requestSha256; }
    String requestJson() { return requestJson; }
    int resultFormatVersion() { return resultFormatVersion; }
    String resultSha256() { return resultSha256; }
    String resultJson() { return resultJson; }
    Instant committedAt() { return committedAt; }
}
