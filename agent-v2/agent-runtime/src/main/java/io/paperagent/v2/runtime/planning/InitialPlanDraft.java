package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.PlanStep;

import java.util.List;

/**
 * A structured, non-authoritative initial Plan draft.
 *
 * <p>Only structural validity is enforced here. Plan graph and semantic
 * validation remain the responsibility of the canonical contracts.
 */
public record InitialPlanDraft(String reason, List<PlanStep> steps) {
    public InitialPlanDraft {
        InitialPlanFreezeValues.required(reason, "initialPlanDraft.reason");
        steps = InitialPlanFreezeValues.list(steps, "initialPlanDraft.steps");
    }
}
