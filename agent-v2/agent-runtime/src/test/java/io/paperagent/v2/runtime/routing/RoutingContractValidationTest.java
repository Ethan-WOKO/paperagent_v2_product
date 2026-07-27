package io.paperagent.v2.runtime.routing;

import io.paperagent.v2.contracts.Route;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingContractValidationTest {
    @Test
    void decisionReasonContractContainsExactlyTheFrozenValues() {
        assertEquals(
                Set.of(
                        RoutingDecisionReason.DIRECT_ELIGIBLE,
                        RoutingDecisionReason.DECLARED_REQUIREMENT,
                        RoutingDecisionReason.INCOMPLETE_ASSESSMENT),
                Set.of(RoutingDecisionReason.values()));
    }

    @Test
    void routingRequestIdUsesFrozenShapeAndStableFailures() {
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingRequestId.value",
                () -> new RoutingRequestId(null));
        assertViolation(
                RoutingValidationCode.INVALID_ID,
                "routingRequestId.value",
                () -> new RoutingRequestId(""));
        assertViolation(
                RoutingValidationCode.INVALID_ID,
                "routingRequestId.value",
                () -> new RoutingRequestId("contains spaces"));
        assertViolation(
                RoutingValidationCode.INVALID_ID,
                "routingRequestId.value",
                () -> new RoutingRequestId("a".repeat(129)));
        assertEquals(
                "A-z_0.9:route-1",
                new RoutingRequestId("A-z_0.9:route-1").value());
        assertEquals(128, new RoutingRequestId("a".repeat(128)).value().length());
    }

    @Test
    void assessmentRejectsMissingFieldsAndNullElementsWithStablePaths() {
        RoutingRequestId requestId = requestId("assessment-validation");
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingAssessment.requestId",
                () -> new RoutingAssessment(null, true, Set.of()));
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingAssessment.requirements",
                () -> new RoutingAssessment(requestId, true, null));
        Set<RoutingRequirement> withNull = new HashSet<>();
        withNull.add(null);
        assertViolation(
                RoutingValidationCode.NULL_COLLECTION_ELEMENT,
                "routingAssessment.requirements",
                () -> new RoutingAssessment(requestId, true, withNull));
    }

    @Test
    void routerRejectsMissingAssessmentWithStableFailure() {
        DeterministicBinaryRouter router = new DeterministicBinaryRouter();

        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingAssessment",
                () -> router.route(null));
    }

    @Test
    void decisionRejectsMissingFieldsWithStablePaths() {
        RoutingRequestId requestId = requestId("decision-required-fields");
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingDecision.requestId",
                () -> new RoutingDecision(
                        null,
                        Route.DIRECT,
                        RoutingDecisionReason.DIRECT_ELIGIBLE,
                        Set.of()));
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingDecision.route",
                () -> new RoutingDecision(
                        requestId,
                        null,
                        RoutingDecisionReason.DIRECT_ELIGIBLE,
                        Set.of()));
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingDecision.reason",
                () -> new RoutingDecision(requestId, Route.DIRECT, null, Set.of()));
        assertViolation(
                RoutingValidationCode.REQUIRED_VALUE_MISSING,
                "routingDecision.requirements",
                () -> new RoutingDecision(
                        requestId,
                        Route.DIRECT,
                        RoutingDecisionReason.DIRECT_ELIGIBLE,
                        null));
        Set<RoutingRequirement> withNull = new HashSet<>();
        withNull.add(null);
        assertViolation(
                RoutingValidationCode.NULL_COLLECTION_ELEMENT,
                "routingDecision.requirements",
                () -> new RoutingDecision(
                        requestId,
                        Route.DIRECT,
                        RoutingDecisionReason.DIRECT_ELIGIBLE,
                        withNull));
    }

    @Test
    void decisionConstructorAcceptsOnlyFrozenRouteReasonRequirementCombinations() {
        RoutingRequestId requestId = requestId("decision-combinations");
        for (Route route : Route.values()) {
            for (RoutingDecisionReason reason : RoutingDecisionReason.values()) {
                for (boolean nonEmpty : new boolean[]{false, true}) {
                    Set<RoutingRequirement> requirements = nonEmpty
                            ? Set.of(RoutingRequirement.TOOL_USE)
                            : Set.of();
                    if (legal(route, reason, nonEmpty)) {
                        assertDoesNotThrow(
                                () -> new RoutingDecision(
                                        requestId,
                                        route,
                                        reason,
                                        requirements),
                                route + " / " + reason + " / nonEmpty=" + nonEmpty);
                    } else {
                        assertViolation(
                                RoutingValidationCode.INCONSISTENT_DECISION,
                                "routingDecision",
                                () -> new RoutingDecision(
                                        requestId,
                                        route,
                                        reason,
                                        requirements));
                    }
                }
            }
        }
    }

    @Test
    void inputAndDecisionSetsAreDefensiveAndImmutable() {
        Set<RoutingRequirement> callerRequirements =
                new HashSet<>(Set.of(RoutingRequirement.MODIFICATION));
        RoutingAssessment assessment = new RoutingAssessment(
                requestId("defensive-copy"),
                true,
                callerRequirements);
        callerRequirements.add(RoutingRequirement.NETWORK);

        RoutingDecision decision = new DeterministicBinaryRouter().route(assessment);
        callerRequirements.clear();

        assertEquals(Set.of(RoutingRequirement.MODIFICATION), assessment.requirements());
        assertEquals(Set.of(RoutingRequirement.MODIFICATION), decision.requirements());
        assertThrows(
                UnsupportedOperationException.class,
                () -> assessment.requirements().add(RoutingRequirement.RECOVERY));
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.requirements().add(RoutingRequirement.RECOVERY));
    }

    @Test
    void routerInstancesAreReusableDeterministicAndDoNotInterfere() {
        DeterministicBinaryRouter firstRouter = new DeterministicBinaryRouter();
        DeterministicBinaryRouter secondRouter = new DeterministicBinaryRouter();
        RoutingAssessment planned = new RoutingAssessment(
                requestId("planned-request"),
                true,
                Set.of(RoutingRequirement.CONFIRMATION));
        RoutingAssessment direct = new RoutingAssessment(
                requestId("direct-request"),
                true,
                Set.of());

        RoutingDecision firstPlanned = firstRouter.route(planned);
        RoutingDecision secondDirect = secondRouter.route(direct);
        RoutingDecision firstDirect = firstRouter.route(direct);
        RoutingDecision secondPlanned = secondRouter.route(planned);
        RoutingDecision repeatedPlanned = firstRouter.route(planned);
        RoutingDecision repeatedDirect = secondRouter.route(direct);

        assertEquals(firstPlanned, secondPlanned);
        assertEquals(firstPlanned, repeatedPlanned);
        assertEquals(secondDirect, firstDirect);
        assertEquals(secondDirect, repeatedDirect);
        assertNotEquals(firstPlanned.requestId(), secondDirect.requestId());
        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, firstPlanned.route());
        assertEquals(Route.DIRECT, secondDirect.route());
    }

    private static boolean legal(
            Route route,
            RoutingDecisionReason reason,
            boolean requirementsNonEmpty) {
        return switch (reason) {
            case DIRECT_ELIGIBLE -> route == Route.DIRECT && !requirementsNonEmpty;
            case DECLARED_REQUIREMENT ->
                    route == Route.PERSISTENT_PLAN_EXECUTE && requirementsNonEmpty;
            case INCOMPLETE_ASSESSMENT -> route == Route.PERSISTENT_PLAN_EXECUTE;
        };
    }

    private static RoutingRequestId requestId(String value) {
        return new RoutingRequestId(value);
    }

    private static RoutingValidationException assertViolation(
            RoutingValidationCode expectedCode,
            String expectedPath,
            Runnable action) {
        RoutingValidationException exception =
                assertThrows(RoutingValidationException.class, action::run);
        assertEquals(expectedCode, exception.code());
        assertEquals(expectedPath, exception.path());
        return exception;
    }
}
