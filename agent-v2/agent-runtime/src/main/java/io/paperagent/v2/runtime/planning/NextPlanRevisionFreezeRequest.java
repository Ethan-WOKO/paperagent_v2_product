package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.runtime.routing.RoutingDecision;

import java.time.Instant;
import java.util.Map;

public record NextPlanRevisionFreezeRequest(
        RoutingDecision routingDecision,
        Plan currentPlan,
        PlanRevisionId nextRevisionId,
        NextPlanRevisionDraft draft,
        Instant createdAt,
        Map<PlanStepId, CompletionFact> newlyCompletedFacts) {

    public NextPlanRevisionFreezeRequest {
        NextPlanRevisionFreezeValues.required(
                routingDecision,
                "nextPlanRevisionFreezeRequest.routingDecision");
        NextPlanRevisionFreezeValues.required(
                currentPlan,
                "nextPlanRevisionFreezeRequest.currentPlan");
        NextPlanRevisionFreezeValues.required(
                nextRevisionId,
                "nextPlanRevisionFreezeRequest.nextRevisionId");
        NextPlanRevisionFreezeValues.required(
                draft,
                "nextPlanRevisionFreezeRequest.draft");
        NextPlanRevisionFreezeValues.required(
                createdAt,
                "nextPlanRevisionFreezeRequest.createdAt");
        newlyCompletedFacts = NextPlanRevisionFreezeValues.map(
                newlyCompletedFacts,
                "nextPlanRevisionFreezeRequest.newlyCompletedFacts");
    }
}
