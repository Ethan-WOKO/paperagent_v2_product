package io.paperagent.v2.runtime.routing;

import io.paperagent.v2.contracts.Route;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BinaryRouterFailClosedTest {
    private final RuntimeRouter router = new DeterministicBinaryRouter();

    @Test
    void incompleteEmptyAssessmentFailsClosed() {
        RoutingAssessment assessment = assessment(Set.of());

        RoutingDecision decision = router.route(assessment);

        assertPersistentAndIncomplete(assessment, decision);
    }

    @Test
    void incompleteNonEmptyAssessmentUsesIncompletePrecedenceAndPreservesRequirements() {
        RoutingAssessment assessment = assessment(Set.of(
                RoutingRequirement.PROJECT_FILE_ACCESS,
                RoutingRequirement.EXTERNAL_OBSERVATION));

        RoutingDecision decision = router.route(assessment);

        assertPersistentAndIncomplete(assessment, decision);
    }

    @Test
    void frozenContractContainsExactlyTwoNonNullRoutes() {
        assertEquals(
                Set.of(Route.DIRECT, Route.PERSISTENT_PLAN_EXECUTE),
                Set.of(Route.values()));
        for (Route route : Route.values()) {
            assertNotNull(route);
        }
    }

    private static RoutingAssessment assessment(Set<RoutingRequirement> requirements) {
        return new RoutingAssessment(
                new RoutingRequestId("routing-request-incomplete"),
                false,
                requirements);
    }

    private static void assertPersistentAndIncomplete(
            RoutingAssessment assessment,
            RoutingDecision decision) {
        assertEquals(assessment.requestId(), decision.requestId());
        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, decision.route());
        assertEquals(RoutingDecisionReason.INCOMPLETE_ASSESSMENT, decision.reason());
        assertEquals(assessment.requirements(), decision.requirements());
    }
}
