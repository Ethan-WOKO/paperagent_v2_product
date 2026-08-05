package com.yanban.api.agent.v2.result;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "agent_v2_step_results",
        indexes = {
                @Index(
                        name = "idx_agent_v2_step_results_plan_step",
                        columnList = "plan_id,step_id,created_at"),
                @Index(
                        name = "idx_agent_v2_step_results_activation_status",
                        columnList = "activation_event_id,status")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_agent_v2_step_result_proposal",
                        columnNames = {
                                "activation_event_id", "source",
                                "proposed_sha256"
                        }),
                @UniqueConstraint(
                        name = "uk_agent_v2_step_result_accepted_activation",
                        columnNames = "accepted_activation_event_id")
        })
class V2StepResultEntity {
    @Id
    @Column(name = "result_id", nullable = false, length = 128)
    private String resultId;
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;
    @Column(name = "plan_revision_id", nullable = false, length = 128)
    private String planRevisionId;
    @Column(name = "step_id", nullable = false, length = 128)
    private String stepId;
    @Column(name = "activation_event_id", nullable = false, length = 128)
    private String activationEventId;
    @Column(nullable = false, length = 16)
    private String source;
    @Lob
    @Column(name = "proposed_text", nullable = false,
            columnDefinition = "LONGTEXT")
    private String proposedText;
    @Column(name = "proposed_sha256", nullable = false, length = 64)
    private String proposedSha256;
    @Lob
    @Column(name = "evidence_receipt_ids_json", nullable = false,
            columnDefinition = "LONGTEXT")
    private String evidenceReceiptIdsJson;
    @Column(nullable = false, length = 16)
    private String status;
    @Lob
    @Column(name = "accepted_text", columnDefinition = "LONGTEXT")
    private String acceptedText;
    @Column(name = "accepted_sha256", length = 64)
    private String acceptedSha256;
    @Column(name = "accepted_activation_event_id", length = 128)
    private String acceptedActivationEventId;
    @Column(name = "resolution_reason", length = 1000)
    private String resolutionReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected V2StepResultEntity() {
    }

    V2StepResultEntity(
            String resultId, String planId, String planRevisionId,
            String stepId, String activationEventId, String source,
            String proposedText, String proposedSha256,
            String evidenceReceiptIdsJson, Instant now) {
        this.resultId = resultId;
        this.planId = planId;
        this.planRevisionId = planRevisionId;
        this.stepId = stepId;
        this.activationEventId = activationEventId;
        this.source = source;
        this.proposedText = proposedText;
        this.proposedSha256 = proposedSha256;
        this.evidenceReceiptIdsJson = evidenceReceiptIdsJson;
        this.status = V2StepResultStatus.PROPOSED.name();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void accept(String text, String sha256, Instant now) {
        if (V2StepResultStatus.ACCEPTED.name().equals(status)) {
            if (!text.equals(acceptedText) || !sha256.equals(acceptedSha256)) {
                throw new IllegalStateException(
                        "accepted Step result is immutable");
            }
            return;
        }
        if (!V2StepResultStatus.PROPOSED.name().equals(status)) {
            throw new IllegalStateException(
                    "rejected Step result cannot be accepted");
        }
        status = V2StepResultStatus.ACCEPTED.name();
        acceptedText = text;
        acceptedSha256 = sha256;
        acceptedActivationEventId = activationEventId;
        resolutionReason = null;
        updatedAt = now;
    }

    void reject(String reason, Instant now) {
        if (V2StepResultStatus.ACCEPTED.name().equals(status)) {
            throw new IllegalStateException(
                    "accepted Step result is immutable");
        }
        status = V2StepResultStatus.REJECTED.name();
        resolutionReason = reason;
        updatedAt = now;
    }

    String resultId() { return resultId; }
    String planId() { return planId; }
    String planRevisionId() { return planRevisionId; }
    String stepId() { return stepId; }
    String activationEventId() { return activationEventId; }
    String source() { return source; }
    String proposedText() { return proposedText; }
    String proposedSha256() { return proposedSha256; }
    String evidenceReceiptIdsJson() { return evidenceReceiptIdsJson; }
    String status() { return status; }
    String acceptedText() { return acceptedText; }
    String acceptedSha256() { return acceptedSha256; }
    String resolutionReason() { return resolutionReason; }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }
}
