package io.paperagent.v2.runtime.routing;

import io.paperagent.v2.contracts.Route;

import java.util.Set;

public record RoutingDecision(
        RoutingRequestId requestId,
        Route route,
        RoutingDecisionReason reason,
        Set<RoutingRequirement> requirements) {

    public RoutingDecision {
        RoutingValues.required(requestId, "routingDecision.requestId");
        RoutingValues.required(route, "routingDecision.route");
        RoutingValues.required(reason, "routingDecision.reason");
        requirements = RoutingValues.set(requirements, "routingDecision.requirements");
        requireConsistent(route, reason, requirements);
    }

    private static void requireConsistent(
            Route route,
            RoutingDecisionReason reason,
            Set<RoutingRequirement> requirements) {
        boolean consistent = switch (reason) {
            case DIRECT_ELIGIBLE ->
                    route == Route.DIRECT && requirements.isEmpty();
            case DECLARED_REQUIREMENT ->
                    route == Route.PERSISTENT_PLAN_EXECUTE && !requirements.isEmpty();
            case INCOMPLETE_ASSESSMENT ->
                    route == Route.PERSISTENT_PLAN_EXECUTE;
        };
        if (!consistent) {
            RoutingValues.fail(
                    RoutingValidationCode.INCONSISTENT_DECISION,
                    "routingDecision",
                    "route, reason, and requirements are inconsistent");
        }
    }
}
