package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.Route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Appends a deterministic next revision and delegates all semantic and
 * cross-revision validation to the canonical contracts.
 */
public final class DeterministicNextPlanRevisionFreezer implements NextPlanRevisionFreezer {
    @Override
    public Plan freeze(NextPlanRevisionFreezeRequest request) {
        NextPlanRevisionFreezeValues.required(
                request,
                "nextPlanRevisionFreezeRequest");
        if (request.routingDecision().route() != Route.PERSISTENT_PLAN_EXECUTE) {
            NextPlanRevisionFreezeValues.fail(
                    NextPlanRevisionFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                    "nextPlanRevisionFreezeRequest.routingDecision.route",
                    "next Plan revision freezing requires a persistent route");
        }

        Plan currentPlan = request.currentPlan();
        PlanRevision latestRevision = currentPlan.latestRevision();
        Map<PlanStepId, CompletionFact> completedFacts =
                new LinkedHashMap<>(latestRevision.completedFacts());
        completedFacts.putAll(request.newlyCompletedFacts());

        NextPlanRevisionDraft draft = request.draft();
        PlanRevision nextRevision = new PlanRevision(
                request.nextRevisionId(),
                currentPlan.taskFrameId(),
                latestRevision.number() + 1,
                Optional.of(latestRevision.id()),
                draft.reason(),
                request.createdAt(),
                draft.steps(),
                completedFacts);

        ArrayList<PlanRevision> revisions = new ArrayList<>(currentPlan.revisions());
        revisions.add(nextRevision);
        return new Plan(
                currentPlan.id(),
                currentPlan.taskFrameId(),
                revisions);
    }
}
