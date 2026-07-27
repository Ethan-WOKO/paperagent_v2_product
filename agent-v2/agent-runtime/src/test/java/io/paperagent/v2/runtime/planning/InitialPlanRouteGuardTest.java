package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialPlanRouteGuardTest {
    @Test
    void directRouteFailsBeforeCanonicalPlanRevisionConstruction() {
        PlanStep duplicate = PlanningTestFixtures.step(
                new PlanStepId("step-direct-duplicate"),
                Set.of());
        InitialPlanDraft structurallyValidCanonicalInvalidDraft =
                new InitialPlanDraft("route guard plan", List.of(duplicate, duplicate));
        InitialPlanFreezeRequest request = PlanningTestFixtures.request(
                PlanningTestFixtures.directDecision("routing-direct-plan"),
                PlanningTestFixtures.taskFrame("task-direct-plan"),
                PlanningTestFixtures.PLAN_ID,
                PlanningTestFixtures.REVISION_ID,
                structurallyValidCanonicalInvalidDraft,
                PlanningTestFixtures.CREATED_AT);

        InitialPlanFreezeValidationException exception = assertThrows(
                InitialPlanFreezeValidationException.class,
                () -> new DeterministicInitialPlanFreezer().freeze(request));

        assertEquals(
                InitialPlanFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                exception.code());
        assertEquals(
                "initialPlanFreezeRequest.routingDecision.route",
                exception.path());
    }
}
