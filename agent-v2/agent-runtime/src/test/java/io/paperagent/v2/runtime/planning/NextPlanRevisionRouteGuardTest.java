package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NextPlanRevisionRouteGuardTest {
    @Test
    void directRouteWinsBeforeCanonicalInvalidDraftAndDelta() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        PlanStep duplicate = currentPlan.latestRevision().steps().get(0);
        NextPlanRevisionDraft duplicateDraft =
                new NextPlanRevisionDraft(
                        "Canonical-invalid duplicate steps",
                        List.of(duplicate, duplicate));
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        PlanningTestFixtures.directDecision(
                                "routing-next-direct"),
                        currentPlan,
                        NextPlanRevisionTestFixtures.NEXT_REVISION_ID,
                        duplicateDraft,
                        NextPlanRevisionTestFixtures.NEXT_CREATED_AT,
                        Map.of(
                                NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                                NextPlanRevisionTestFixtures.fact(
                                        NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                                        "canonical-invalid-mismatch",
                                        80)));

        NextPlanRevisionFreezeValidationException exception = assertThrows(
                NextPlanRevisionFreezeValidationException.class,
                () -> new DeterministicNextPlanRevisionFreezer().freeze(request));

        assertEquals(
                NextPlanRevisionFreezeValidationCode.ROUTE_NOT_PERSISTENT,
                exception.code());
        assertEquals(
                "nextPlanRevisionFreezeRequest.routingDecision.route",
                exception.path());
    }
}
