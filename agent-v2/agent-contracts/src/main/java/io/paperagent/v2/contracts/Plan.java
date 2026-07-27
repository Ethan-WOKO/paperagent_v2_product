package io.paperagent.v2.contracts;

import java.util.List;

public record Plan(PlanId id, TaskFrameId taskFrameId, List<PlanRevision> revisions) {
    public Plan {
        Contracts.required(id, "plan.id");
        Contracts.required(taskFrameId, "plan.taskFrameId");
        revisions = Contracts.list(revisions, "plan.revisions");
        if (revisions.isEmpty()) {
            Contracts.fail(ViolationCode.REQUIRED_VALUE_MISSING, "plan.revisions",
                    "a plan requires at least one revision");
        }
        Contracts.unique(revisions, PlanRevision::id, "plan.revisions");
        PlanValidators.requireValidHistory(taskFrameId, revisions);
    }

    public PlanRevision latestRevision() {
        return revisions.get(revisions.size() - 1);
    }
}
