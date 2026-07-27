package io.paperagent.v2.runtime.routing;

import io.paperagent.v2.contracts.Route;

/**
 * Stateless truth-table implementation. Natural-language classification is
 * deliberately outside this policy.
 */
public final class DeterministicBinaryRouter implements RuntimeRouter {
    @Override
    public RoutingDecision route(RoutingAssessment assessment) {
        RoutingValues.required(assessment, "routingAssessment");
        if (!assessment.classificationComplete()) {
            return decision(
                    assessment,
                    Route.PERSISTENT_PLAN_EXECUTE,
                    RoutingDecisionReason.INCOMPLETE_ASSESSMENT);
        }
        if (!assessment.requirements().isEmpty()) {
            return decision(
                    assessment,
                    Route.PERSISTENT_PLAN_EXECUTE,
                    RoutingDecisionReason.DECLARED_REQUIREMENT);
        }
        return decision(
                assessment,
                Route.DIRECT,
                RoutingDecisionReason.DIRECT_ELIGIBLE);
    }

    private static RoutingDecision decision(
            RoutingAssessment assessment,
            Route route,
            RoutingDecisionReason reason) {
        return new RoutingDecision(
                assessment.requestId(),
                route,
                reason,
                assessment.requirements());
    }
}
