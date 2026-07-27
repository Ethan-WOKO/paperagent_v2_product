package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.PlanStep;

import java.util.List;

/**
 * A structured, non-authoritative next-revision draft.
 *
 * <p>Only structural validity is enforced here. Revision and history semantics
 * remain the responsibility of the canonical contracts.
 */
public record NextPlanRevisionDraft(String reason, List<PlanStep> steps) {
    public NextPlanRevisionDraft {
        NextPlanRevisionFreezeValues.required(
                reason,
                "nextPlanRevisionDraft.reason");
        steps = NextPlanRevisionFreezeValues.list(
                steps,
                "nextPlanRevisionDraft.steps");
    }
}
