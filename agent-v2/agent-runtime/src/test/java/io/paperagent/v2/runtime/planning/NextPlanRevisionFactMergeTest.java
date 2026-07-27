package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NextPlanRevisionFactMergeTest {
    @Test
    void emptyDeltaAutomaticallyPreservesLatestFacts() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Preserve existing facts",
                                currentPlan.latestRevision().steps()),
                        Map.of());

        Plan result = new DeterministicNextPlanRevisionFreezer().freeze(request);

        assertEquals(
                currentPlan.latestRevision().completedFacts(),
                result.latestRevision().completedFacts());
    }

    @Test
    void exactFactReplayIsAcceptedOnceButConflictingOverwriteReachesPlanHistory() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        CompletionFact existing =
                currentPlan.latestRevision().completedFacts()
                        .get(NextPlanRevisionTestFixtures.FIRST_STEP_ID);
        NextPlanRevisionDraft draft = NextPlanRevisionTestFixtures.draft(
                "Replay or conflict",
                currentPlan.latestRevision().steps());
        NextPlanRevisionFreezeRequest replayRequest =
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        draft,
                        Map.of(NextPlanRevisionTestFixtures.FIRST_STEP_ID, existing));

        Plan replayResult =
                new DeterministicNextPlanRevisionFreezer().freeze(replayRequest);

        assertEquals(1, replayResult.latestRevision().completedFacts().size());
        assertEquals(
                existing,
                replayResult.latestRevision().completedFacts()
                        .get(NextPlanRevisionTestFixtures.FIRST_STEP_ID));

        CompletionFact conflicting = NextPlanRevisionTestFixtures.fact(
                NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                "contradictory-outcome",
                20);
        NextPlanRevisionFreezeRequest conflictRequest =
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        draft,
                        Map.of(NextPlanRevisionTestFixtures.FIRST_STEP_ID, conflicting));
        PlanRevision candidate = assertDoesNotThrow(
                () -> candidateWithMergedFacts(conflictRequest));
        assertEquals(
                conflicting,
                candidate.completedFacts().get(
                        NextPlanRevisionTestFixtures.FIRST_STEP_ID));

        assertCanonicalFailure(
                conflictRequest,
                ViolationCode.COMPLETED_FACT_REDEFINED,
                "planRevision.completedFacts."
                        + NextPlanRevisionTestFixtures.FIRST_STEP_ID.value());
    }

    @Test
    void unchangedPriorStepAcceptsItsFirstFactAndKeepsOldFactBaseline() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        CompletionFact newlyCompleted = NextPlanRevisionTestFixtures.fact(
                NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                "outcome-second",
                30);
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Complete the unfinished step",
                                currentPlan.latestRevision().steps()),
                        Map.of(NextPlanRevisionTestFixtures.SECOND_STEP_ID, newlyCompleted));

        Plan result = new DeterministicNextPlanRevisionFreezer().freeze(request);

        assertEquals(2, result.latestRevision().completedFacts().size());
        assertEquals(
                currentPlan.latestRevision().completedFacts().get(
                        NextPlanRevisionTestFixtures.FIRST_STEP_ID),
                result.latestRevision().completedFacts().get(
                        NextPlanRevisionTestFixtures.FIRST_STEP_ID));
        assertEquals(
                newlyCompleted,
                result.latestRevision().completedFacts().get(
                        NextPlanRevisionTestFixtures.SECOND_STEP_ID));
        assertEquals(
                currentPlan.latestRevision().steps(),
                result.latestRevision().steps());
    }

    @Test
    void rewrittenPriorStepWithFirstFactIsRejectedByHardenedHistory() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        PlanStep priorSecond = currentPlan.latestRevision().steps().get(1);
        PlanStep rewrittenSecond = new PlanStep(
                priorSecond.id(),
                "rewritten unfinished step",
                priorSecond.expectedOutcome(),
                priorSecond.dependencies(),
                priorSecond.completionCriteria(),
                priorSecond.executionHints());
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Rewrite and complete",
                                List.of(
                                        currentPlan.latestRevision().steps().get(0),
                                        rewrittenSecond)),
                        Map.of(
                                NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                                NextPlanRevisionTestFixtures.fact(
                                        NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                                        "rewritten-second",
                                        40)));

        assertDoesNotThrow(() -> candidateWithMergedFacts(request));
        assertCanonicalFailure(
                request,
                ViolationCode.COMPLETED_FACT_REDEFINED,
                "planRevision.completedFacts."
                        + NextPlanRevisionTestFixtures.SECOND_STEP_ID.value());
    }

    @Test
    void draftOnlyNewStepWithFactIsRejectedByHardenedHistory() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        PlanStepId newStepId = new PlanStepId("step-next-new-only");
        PlanStep newStep = PlanningTestFixtures.step(newStepId, Set.of());
        List<PlanStep> candidateSteps = List.of(
                currentPlan.latestRevision().steps().get(0),
                currentPlan.latestRevision().steps().get(1),
                newStep);
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(
                        currentPlan,
                        NextPlanRevisionTestFixtures.draft(
                                "Add and immediately complete a new step",
                                candidateSteps),
                        Map.of(
                                newStepId,
                                NextPlanRevisionTestFixtures.fact(
                                        newStepId,
                                        "new-only",
                                        50)));

        assertDoesNotThrow(() -> candidateWithMergedFacts(request));
        assertCanonicalFailure(
                request,
                ViolationCode.INCONSISTENT_REFERENCE,
                "planRevision.completedFacts." + newStepId.value());
    }

    private static PlanRevision candidateWithMergedFacts(
            NextPlanRevisionFreezeRequest request) {
        PlanRevision latest = request.currentPlan().latestRevision();
        Map<PlanStepId, CompletionFact> merged =
                new LinkedHashMap<>(latest.completedFacts());
        merged.putAll(request.newlyCompletedFacts());
        return new PlanRevision(
                request.nextRevisionId(),
                request.currentPlan().taskFrameId(),
                latest.number() + 1,
                Optional.of(latest.id()),
                request.draft().reason(),
                request.createdAt(),
                request.draft().steps(),
                merged);
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
