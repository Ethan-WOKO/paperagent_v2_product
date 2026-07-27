package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.Route;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Freezes caller authority and a structured draft into canonical initial Plan
 * contracts without reading ambient state.
 */
public final class DeterministicInitialPlanFreezer implements InitialPlanFreezer {
    @Override
    public Plan freeze(InitialPlanFreezeRequest request) {
        InitialPlanFreezeValues.required(request, "initialPlanFreezeRequest");
        if (request.routingDecision().route() != Route.PERSISTENT_PLAN_EXECUTE) {
            InitialPlanFreezeValues.fail(
                    InitialPlanFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                    "initialPlanFreezeRequest.routingDecision.route",
                    "initial Plan freezing requires a persistent route");
        }

        InitialPlanDraft draft = request.draft();
        PlanRevision initialRevision = new PlanRevision(
                request.initialRevisionId(),
                request.taskFrame().id(),
                1,
                Optional.empty(),
                draft.reason(),
                request.createdAt(),
                draft.steps(),
                Map.of());
        return new Plan(
                request.planId(),
                request.taskFrame().id(),
                List.of(initialRevision));
    }
}
