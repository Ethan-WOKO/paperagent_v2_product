package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_v2_final_syntheses")
class ProductFinalSynthesisEntity {
    @Id
    @Column(name = "plan_id", length = 128, nullable = false)
    private String planId;
    @Column(name = "synthesis_id", length = 128, nullable = false, unique = true)
    private String synthesisId;
    @Column(name = "task_frame_id", length = 128, nullable = false)
    private String taskFrameId;
    @Column(name = "plan_revision_id", length = 128, nullable = false)
    private String planRevisionId;
    @Column(name = "receipt_ids_json", columnDefinition = "LONGTEXT", nullable = false)
    private String receiptIdsJson;
    @Column(name = "narrative", columnDefinition = "LONGTEXT", nullable = false)
    private String narrative;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "canonical_sha256", length = 64, nullable = false)
    private String canonicalSha256;
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;
    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    protected ProductFinalSynthesisEntity() {
    }

    ProductFinalSynthesisEntity(
            String planId, String synthesisId, String taskFrameId,
            String planRevisionId, String receiptIdsJson, String narrative,
            Instant observedAt, String canonicalSha256, Instant committedAt) {
        this.planId = planId;
        this.synthesisId = synthesisId;
        this.taskFrameId = taskFrameId;
        this.planRevisionId = planRevisionId;
        this.receiptIdsJson = receiptIdsJson;
        this.narrative = narrative;
        this.observedAt = observedAt;
        this.canonicalSha256 = canonicalSha256;
        this.committedAt = committedAt;
    }

    String planId() { return planId; }
    String synthesisId() { return synthesisId; }
    String taskFrameId() { return taskFrameId; }
    String planRevisionId() { return planRevisionId; }
    String receiptIdsJson() { return receiptIdsJson; }
    String narrative() { return narrative; }
    Instant observedAt() { return observedAt; }
    String canonicalSha256() { return canonicalSha256; }
    Long assistantMessageId() { return assistantMessageId; }
    void bindAssistantMessage(Long value) { assistantMessageId = value; }
}
