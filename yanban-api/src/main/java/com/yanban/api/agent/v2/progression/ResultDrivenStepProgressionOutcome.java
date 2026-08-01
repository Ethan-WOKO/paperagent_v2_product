package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import java.util.Objects;

public record ResultDrivenStepProgressionOutcome(
        PlanId planId,
        PlanStepId completedStepId,
        EffectDrivenStepProgressionState state,
        StepRecoverySnapshot snapshot) {
    public ResultDrivenStepProgressionOutcome {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(completedStepId, "completedStepId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.planId().equals(planId)) {
            throw new IllegalArgumentException("snapshot.planId mismatch");
        }
    }
}
