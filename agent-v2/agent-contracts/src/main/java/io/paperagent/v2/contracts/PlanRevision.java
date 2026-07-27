package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record PlanRevision(
        PlanRevisionId id,
        TaskFrameId taskFrameId,
        long number,
        Optional<PlanRevisionId> parentRevisionId,
        String reason,
        Instant createdAt,
        List<PlanStep> steps,
        Map<PlanStepId, CompletionFact> completedFacts) {

    public PlanRevision {
        Contracts.required(id, "planRevision.id");
        Contracts.required(taskFrameId, "planRevision.taskFrameId");
        if (number < 1) {
            Contracts.fail(ViolationCode.REVISION_NOT_MONOTONIC, "planRevision.number",
                    "revision number must start at 1");
        }
        parentRevisionId = Contracts.required(parentRevisionId, "planRevision.parentRevisionId");
        if (number == 1 && parentRevisionId.isPresent()) {
            Contracts.fail(ViolationCode.REVISION_PARENT_MISMATCH, "planRevision.parentRevisionId",
                    "the first revision has no parent");
        }
        if (number > 1 && parentRevisionId.isEmpty()) {
            Contracts.fail(ViolationCode.REVISION_PARENT_MISMATCH, "planRevision.parentRevisionId",
                    "later revisions require one parent");
        }
        reason = Contracts.text(reason, "planRevision.reason");
        Contracts.required(createdAt, "planRevision.createdAt");
        steps = Contracts.list(steps, "planRevision.steps");
        Contracts.unique(steps, PlanStep::id, "planRevision.steps");
        completedFacts = Contracts.map(completedFacts, "planRevision.completedFacts");
        PlanValidators.requireStepGraph(steps);
        PlanValidators.requireFactsMatchSteps(steps, completedFacts);
    }
}
