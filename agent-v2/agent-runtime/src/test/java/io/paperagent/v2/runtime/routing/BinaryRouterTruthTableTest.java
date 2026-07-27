package io.paperagent.v2.runtime.routing;

import io.paperagent.v2.contracts.Route;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinaryRouterTruthTableTest {
    private final RuntimeRouter router = new DeterministicBinaryRouter();

    @Test
    void requirementContractContainsExactlyTheFrozenValues() {
        assertEquals(
                Set.of(
                        RoutingRequirement.PROJECT_FILE_ACCESS,
                        RoutingRequirement.TOOL_USE,
                        RoutingRequirement.EXECUTION,
                        RoutingRequirement.NETWORK,
                        RoutingRequirement.MODIFICATION,
                        RoutingRequirement.CONFIRMATION,
                        RoutingRequirement.RECOVERY,
                        RoutingRequirement.EXTERNAL_OBSERVATION),
                Set.of(RoutingRequirement.values()));
    }

    @Test
    void completeAssessmentWithoutRequirementsRoutesDirect() {
        RoutingAssessment assessment = assessment(true, Set.of());

        RoutingDecision decision = router.route(assessment);

        assertEquals(assessment.requestId(), decision.requestId());
        assertEquals(Route.DIRECT, decision.route());
        assertEquals(RoutingDecisionReason.DIRECT_ELIGIBLE, decision.reason());
        assertEquals(Set.of(), decision.requirements());
    }

    @Test
    void everyDeclaredRequirementIndependentlyForcesPersistentRouting() {
        for (RoutingRequirement requirement : RoutingRequirement.values()) {
            RoutingAssessment assessment = assessment(true, Set.of(requirement));

            RoutingDecision decision = router.route(assessment);

            assertEquals(Route.PERSISTENT_PLAN_EXECUTE, decision.route(), requirement.name());
            assertEquals(
                    RoutingDecisionReason.DECLARED_REQUIREMENT,
                    decision.reason(),
                    requirement.name());
            assertEquals(Set.of(requirement), decision.requirements(), requirement.name());
        }
    }

    @Test
    void completeAssessmentPreservesMultipleDeclaredRequirements() {
        Set<RoutingRequirement> requirements = EnumSet.allOf(RoutingRequirement.class);
        RoutingAssessment assessment = assessment(true, requirements);

        RoutingDecision decision = router.route(assessment);

        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, decision.route());
        assertEquals(RoutingDecisionReason.DECLARED_REQUIREMENT, decision.reason());
        assertEquals(requirements, decision.requirements());
    }

    private static RoutingAssessment assessment(
            boolean complete,
            Set<RoutingRequirement> requirements) {
        return new RoutingAssessment(
                new RoutingRequestId("routing-request-truth-table"),
                complete,
                requirements);
    }
}
