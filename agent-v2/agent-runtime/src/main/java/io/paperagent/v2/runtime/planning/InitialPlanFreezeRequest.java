package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.runtime.routing.RoutingDecision;

import java.time.Instant;

public record InitialPlanFreezeRequest(
        RoutingDecision routingDecision,
        TaskFrame taskFrame,
        PlanId planId,
        PlanRevisionId initialRevisionId,
        InitialPlanDraft draft,
        Instant createdAt) {

    public InitialPlanFreezeRequest {
        InitialPlanFreezeValues.required(
                routingDecision,
                "initialPlanFreezeRequest.routingDecision");
        InitialPlanFreezeValues.required(
                taskFrame,
                "initialPlanFreezeRequest.taskFrame");
        InitialPlanFreezeValues.required(planId, "initialPlanFreezeRequest.planId");
        InitialPlanFreezeValues.required(
                initialRevisionId,
                "initialPlanFreezeRequest.initialRevisionId");
        InitialPlanFreezeValues.required(draft, "initialPlanFreezeRequest.draft");
        InitialPlanFreezeValues.required(
                createdAt,
                "initialPlanFreezeRequest.createdAt");
    }
}
