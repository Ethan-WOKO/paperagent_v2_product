package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NextPlanRevisionCanonicalValidationTest {
    @Test
    void deletingCompletedStepPreservesCanonicalCandidateFailure() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        PlanStep remainingStep = PlanningTestFixtures.step(
                NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                Set.of());
        assertCanonicalFailure(
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Delete the completed step",
                                List.of(remainingStep)),
                        Map.of()),
                ViolationCode.INCONSISTENT_REFERENCE,
                "planRevision.completedFacts");
    }

    @Test
    void deltaKeyAndFactStepMismatchPreservesCanonicalCandidateFailure() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        assertCanonicalFailure(
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Mismatched fact reference",
                                currentPlan.latestRevision().steps()),
                        Map.of(
                                NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                                NextPlanRevisionTestFixtures.fact(
                                        NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                                        "mismatch",
                                        70))),
                ViolationCode.INCONSISTENT_REFERENCE,
                "planRevision.completedFacts");
    }

    @Test
    void unknownDependencyPreservesCanonicalCandidateFailure() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        PlanStep unknownDependency = PlanningTestFixtures.step(
                NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                Set.of(new PlanStepId("step-missing")));
        assertCanonicalFailure(
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Unknown dependency",
                                List.of(unknownDependency)),
                        Map.of()),
                ViolationCode.UNKNOWN_STEP_DEPENDENCY,
                "planStep.dependencies");
    }

    @Test
    void dependencyCyclePreservesCanonicalCandidateFailure() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        PlanStep first = PlanningTestFixtures.step(
                NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                Set.of(NextPlanRevisionTestFixtures.SECOND_STEP_ID));
        PlanStep second = PlanningTestFixtures.step(
                NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                Set.of(NextPlanRevisionTestFixtures.FIRST_STEP_ID));
        assertCanonicalFailure(
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Cyclic dependency",
                                List.of(first, second)),
                        Map.of()),
                ViolationCode.STEP_DEPENDENCY_CYCLE,
                "planRevision.steps");
    }

    @Test
    void blankReasonPreservesCanonicalCandidateFailure() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        assertCanonicalFailure(
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                " ",
                                currentPlan.latestRevision().steps()),
                        Map.of()),
                ViolationCode.REQUIRED_TEXT_BLANK,
                "planRevision.reason");
    }

    @Test
    void duplicateRevisionIdPreservesCanonicalPlanFailure() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        PlanningTestFixtures.declaredRequirementDecision(
                                "routing-next-duplicate-revision"),
                        currentPlan,
                        currentPlan.latestRevision().id(),
                        NextPlanRevisionTestFixtures.draft(
                                "Duplicate revision ID",
                                currentPlan.latestRevision().steps()),
                        NextPlanRevisionTestFixtures.NEXT_CREATED_AT,
                        Map.of());

        assertCanonicalFailure(
                request,
                ViolationCode.DUPLICATE_ID,
                "plan.revisions");
    }

    private static ContractViolationException assertCanonicalFailure(
            NextPlanRevisionFreezeRequest request,
            ViolationCode expectedCode,
            String expectedPath) {
        ContractViolationException exception = assertThrows(
                ContractViolationException.class,
                () -> new DeterministicNextPlanRevisionFreezer().freeze(request));
        assertEquals(expectedCode, exception.primaryCode());
        assertEquals(expectedPath, exception.violations().get(0).path());
        assertNull(exception.getCause());
        return exception;
    }
}
