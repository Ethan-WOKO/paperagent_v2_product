package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "agent_v2_active_step_replans",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_agent_v2_replan_event",
                        columnNames = "replan_event_id")
        })
class ProductActiveStepReplanEntity {
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;
    @Column(name = "superseded_step_id", nullable = false, length = 128)
    private String supersededStepId;
    @Id
    @Column(name = "supersession_event_id", nullable = false, length = 128)
    private String supersessionEventId;
    @Column(name = "replan_event_id", nullable = false, length = 128)
    private String replanEventId;
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
    @Column(name = "superseded_checkpoint_version", nullable = false)
    private long supersededCheckpointVersion;
    @Column(name = "result_checkpoint_version", nullable = false)
    private long resultCheckpointVersion;
    @Column(name = "source_event_sequence", nullable = false)
    private long sourceEventSequence;
    @Column(name = "supersession_event_sequence", nullable = false)
    private long supersessionEventSequence;
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

    protected ProductActiveStepReplanEntity() {
    }

    ProductActiveStepReplanEntity(
            String planId,
            String supersededStepId,
            String supersessionEventId,
            String replanEventId,
            String sourceRevisionId,
            long sourceRevisionNumber,
            String resultRevisionId,
            long resultRevisionNumber,
            long sourceCheckpointVersion,
            long supersededCheckpointVersion,
            long resultCheckpointVersion,
            long sourceEventSequence,
            long supersessionEventSequence,
            long resultEventSequence,
            String leaseOwnerId,
            long fencingToken,
            ProductActiveStepReplanCodec.EncodedPayload request,
            ProductActiveStepReplanCodec.EncodedPayload result,
            Instant committedAt) {
        this.planId = planId;
        this.supersededStepId = supersededStepId;
        this.supersessionEventId = supersessionEventId;
        this.replanEventId = replanEventId;
        this.sourceRevisionId = sourceRevisionId;
        this.sourceRevisionNumber = sourceRevisionNumber;
        this.resultRevisionId = resultRevisionId;
        this.resultRevisionNumber = resultRevisionNumber;
        this.sourceCheckpointVersion = sourceCheckpointVersion;
        this.supersededCheckpointVersion = supersededCheckpointVersion;
        this.resultCheckpointVersion = resultCheckpointVersion;
        this.sourceEventSequence = sourceEventSequence;
        this.supersessionEventSequence = supersessionEventSequence;
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
    String supersededStepId() { return supersededStepId; }
    String supersessionEventId() { return supersessionEventId; }
    String replanEventId() { return replanEventId; }
    String sourceRevisionId() { return sourceRevisionId; }
    long sourceRevisionNumber() { return sourceRevisionNumber; }
    String resultRevisionId() { return resultRevisionId; }
    long resultRevisionNumber() { return resultRevisionNumber; }
    long sourceCheckpointVersion() { return sourceCheckpointVersion; }
    long supersededCheckpointVersion() { return supersededCheckpointVersion; }
    long resultCheckpointVersion() { return resultCheckpointVersion; }
    long sourceEventSequence() { return sourceEventSequence; }
    long supersessionEventSequence() { return supersessionEventSequence; }
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
