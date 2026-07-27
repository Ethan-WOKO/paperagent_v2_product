package io.paperagent.v2.runtime.routing;

@FunctionalInterface
public interface RuntimeRouter {
    RoutingDecision route(RoutingAssessment assessment);
}
