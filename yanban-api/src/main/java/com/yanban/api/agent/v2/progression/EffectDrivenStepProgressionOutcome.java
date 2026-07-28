package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepRecoverySnapshot;

import java.util.Objects;
import java.util.Optional;

/** Authoritative post-progression cut and classifications of writes this call observed. */
public record EffectDrivenStepProgressionOutcome(
        PlanId planId,
        PlanStepId completedStepId,
        ReceiptId receiptId,
        EffectDrivenStepProgressionState state,
        Optional<PersistenceOutcome> completionOutcome,
        Optional<PersistenceOutcome> activationOutcome,
        StepRecoverySnapshot snapshot) {

    public EffectDrivenStepProgressionOutcome {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(completedStepId, "completedStepId");
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(state, "state");
        completionOutcome = Objects.requireNonNull(
                completionOutcome, "completionOutcome");
        activationOutcome = Objects.requireNonNull(
                activationOutcome, "activationOutcome");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.planId().equals(planId)) {
            throw new IllegalArgumentException("snapshot.planId mismatch");
        }
    }

    @Override
    public String toString() {
        return "EffectDrivenStepProgressionOutcome["
                + "planId=<provided>, completedStepId=<provided>, "
                + "receiptId=<provided>, state=" + state
                + ", completionOutcome=" + completionOutcome
                + ", activationOutcome=" + activationOutcome
                + ", snapshot=<provided>]";
    }
}
