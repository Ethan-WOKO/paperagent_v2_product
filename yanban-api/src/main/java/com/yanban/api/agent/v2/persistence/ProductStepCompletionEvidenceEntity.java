package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@IdClass(ProductStepCompletionEvidenceId.class)
@Table(
        name = "agent_v2_step_completion_evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_v2_step_completion_tool",
                columnNames = {"completion_event_id", "tool_call_id"}))
class ProductStepCompletionEvidenceEntity {
    @Id
    @Column(name = "completion_event_id", nullable = false, length = 128)
    private String completionEventId;
    @Id
    @Column(name = "ordinal", nullable = false)
    private int ordinal;
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;
    @Column(name = "step_id", nullable = false, length = 128)
    private String stepId;
    @Column(name = "activation_event_id", nullable = false, length = 128)
    private String activationEventId;
    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;
    @Column(name = "receipt_id", nullable = false, length = 128)
    private String receiptId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "completion_event_id",
                    referencedColumnName = "completion_event_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "plan_id",
                    referencedColumnName = "plan_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "step_id",
                    referencedColumnName = "step_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "activation_event_id",
                    referencedColumnName = "activation_event_id",
                    insertable = false,
                    updatable = false)
    })
    private ProductStepCompletionEntity completion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "tool_call_id",
                    referencedColumnName = "tool_call_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "plan_id",
                    referencedColumnName = "plan_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "step_id",
                    referencedColumnName = "step_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "activation_event_id",
                    referencedColumnName = "activation_event_id",
                    insertable = false,
                    updatable = false)
    })
    private ProductEffectIntentEntity intent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(
                    name = "tool_call_id",
                    referencedColumnName = "tool_call_id",
                    insertable = false,
                    updatable = false),
            @JoinColumn(
                    name = "receipt_id",
                    referencedColumnName = "receipt_id",
                    insertable = false,
                    updatable = false)
    })
    private ProductEffectOutcomeResultEntity outcome;

    protected ProductStepCompletionEvidenceEntity() {
    }

    ProductStepCompletionEvidenceEntity(
            String completionEventId, int ordinal,
            String planId, String stepId, String activationEventId,
            String toolCallId, String receiptId) {
        this.completionEventId = completionEventId;
        this.ordinal = ordinal;
        this.planId = planId;
        this.stepId = stepId;
        this.activationEventId = activationEventId;
        this.toolCallId = toolCallId;
        this.receiptId = receiptId;
    }

    String completionEventId() { return completionEventId; }
    int ordinal() { return ordinal; }
    String planId() { return planId; }
    String stepId() { return stepId; }
    String activationEventId() { return activationEventId; }
    String toolCallId() { return toolCallId; }
    String receiptId() { return receiptId; }
}
