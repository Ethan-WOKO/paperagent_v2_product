package com.yanban.api.agent.v2.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_v2_effect_execution_claims")
class ProductEffectExecutionClaimEntity {
    @Id
    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;
    @Column(name = "plan_id", nullable = false, length = 128)
    private String planId;
    @Column(name = "step_id", nullable = false, length = 128)
    private String stepId;
    @Column(name = "activation_event_id", nullable = false, length = 128)
    private String activationEventId;
    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "tool_call_id", referencedColumnName = "tool_call_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "plan_id", referencedColumnName = "plan_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "step_id", referencedColumnName = "step_id",
                    insertable = false, updatable = false),
            @JoinColumn(name = "activation_event_id", referencedColumnName = "activation_event_id",
                    insertable = false, updatable = false)
    })
    private ProductEffectIntentEntity intent;

    protected ProductEffectExecutionClaimEntity() {
    }

    ProductEffectExecutionClaimEntity(
            String toolCallId, String planId, String stepId,
            String activationEventId, Instant claimedAt) {
        this.toolCallId = toolCallId;
        this.planId = planId;
        this.stepId = stepId;
        this.activationEventId = activationEventId;
        this.claimedAt = claimedAt;
    }

    String toolCallId() { return toolCallId; }
    String planId() { return planId; }
    String stepId() { return stepId; }
    String activationEventId() { return activationEventId; }
    Instant claimedAt() { return claimedAt; }
}
