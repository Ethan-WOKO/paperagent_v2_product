package io.paperagent.v2.runtime.routing;

import java.util.Set;

public record RoutingAssessment(
        RoutingRequestId requestId,
        boolean classificationComplete,
        Set<RoutingRequirement> requirements) {

    public RoutingAssessment {
        RoutingValues.required(requestId, "routingAssessment.requestId");
        requirements = RoutingValues.set(requirements, "routingAssessment.requirements");
    }
}
