package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NextPlanRevisionValidationTest {
    @Test
    void structuralValidationCodesContainExactlyTheFrozenValues() {
        assertEquals(
                Set.of(
                        NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                        NextPlanRevisionFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                        NextPlanRevisionFreezeValidationCode.ROUTE_NOT_PERSISTENT),
                Set.of(NextPlanRevisionFreezeValidationCode.values()));
    }

    @Test
    void draftRejectsMissingFieldsAndNullElementsWithStablePaths() {
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionDraft.reason",
                () -> new NextPlanRevisionDraft(null, List.of()));
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionDraft.steps",
                () -> new NextPlanRevisionDraft("reason", null));
        ArrayList<PlanStep> withNull = new ArrayList<>();
        withNull.add(NextPlanRevisionTestFixtures.steps().get(0));
        withNull.add(null);
        assertViolation(
                NextPlanRevisionFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                "nextPlanRevisionDraft.steps",
                () -> new NextPlanRevisionDraft("reason", withNull));
    }

    @Test
    void requestRejectsEveryMissingFieldWithStablePaths() {
        var decision =
                PlanningTestFixtures.declaredRequirementDecision(
                        "routing-next-request-validation");
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        var nextRevisionId = NextPlanRevisionTestFixtures.NEXT_REVISION_ID;
        var draft = NextPlanRevisionTestFixtures.draft(
                "request validation",
                currentPlan.latestRevision().steps());
        var createdAt = NextPlanRevisionTestFixtures.NEXT_CREATED_AT;
        Map<PlanStepId, CompletionFact> delta = Map.of();

        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest.routingDecision",
                () -> new NextPlanRevisionFreezeRequest(
                        null, currentPlan, nextRevisionId, draft, createdAt, delta));
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest.currentPlan",
                () -> new NextPlanRevisionFreezeRequest(
                        decision, null, nextRevisionId, draft, createdAt, delta));
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest.nextRevisionId",
                () -> new NextPlanRevisionFreezeRequest(
                        decision, currentPlan, null, draft, createdAt, delta));
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest.draft",
                () -> new NextPlanRevisionFreezeRequest(
                        decision, currentPlan, nextRevisionId, null, createdAt, delta));
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest.createdAt",
                () -> new NextPlanRevisionFreezeRequest(
                        decision, currentPlan, nextRevisionId, draft, null, delta));
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest.newlyCompletedFacts",
                () -> new NextPlanRevisionFreezeRequest(
                        decision, currentPlan, nextRevisionId, draft, createdAt, null));
    }

    @Test
    void requestRejectsNullMapKeysAndValuesWithStablePath() {
        Plan currentPlan = NextPlanRevisionTestFixtures.standardCurrentPlan();
        NextPlanRevisionDraft draft = NextPlanRevisionTestFixtures.draft(
                "map validation",
                currentPlan.latestRevision().steps());
        HashMap<PlanStepId, CompletionFact> nullKey = new HashMap<>();
        nullKey.put(
                null,
                NextPlanRevisionTestFixtures.fact(
                        NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                        "null-key",
                        1));
        HashMap<PlanStepId, CompletionFact> nullValue = new HashMap<>();
        nullValue.put(NextPlanRevisionTestFixtures.FIRST_STEP_ID, null);

        assertRequestMapViolation(currentPlan, draft, nullKey);
        assertRequestMapViolation(currentPlan, draft, nullValue);
    }

    @Test
    void freezerRejectsMissingTopLevelRequestWithStablePath() {
        assertViolation(
                NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "nextPlanRevisionFreezeRequest",
                () -> new DeterministicNextPlanRevisionFreezer().freeze(null));
    }

    @Test
    void requestDraftAndResultKeepImmutableSnapshotsWithoutMutatingCurrentPlan() {
        Plan currentPlan = NextPlanRevisionTestFixtures.currentPlanWithOneCompletedFact();
        Plan currentSnapshot = new Plan(
                currentPlan.id(),
                currentPlan.taskFrameId(),
                currentPlan.revisions());
        ArrayList<PlanStep> callerSteps =
                new ArrayList<>(currentPlan.latestRevision().steps());
        CompletionFact newlyCompleted = NextPlanRevisionTestFixtures.fact(
                NextPlanRevisionTestFixtures.SECOND_STEP_ID,
                "snapshot-second",
                60);
        LinkedHashMap<PlanStepId, CompletionFact> callerDelta = new LinkedHashMap<>();
        callerDelta.put(NextPlanRevisionTestFixtures.SECOND_STEP_ID, newlyCompleted);
        NextPlanRevisionDraft draft =
                new NextPlanRevisionDraft("snapshot revision", callerSteps);
        NextPlanRevisionFreezeRequest request =
                NextPlanRevisionTestFixtures.request(currentPlan, draft, callerDelta);

        callerSteps.clear();
        callerDelta.clear();
        callerDelta.put(
                NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                NextPlanRevisionTestFixtures.fact(
                        NextPlanRevisionTestFixtures.FIRST_STEP_ID,
                        "late-conflict",
                        61));
        Plan result = new DeterministicNextPlanRevisionFreezer().freeze(request);
        callerSteps.add(PlanningTestFixtures.step(
                new PlanStepId("step-late"),
                Set.of()));
        callerDelta.clear();

        assertEquals(NextPlanRevisionTestFixtures.steps(), draft.steps());
        assertEquals(
                Map.of(NextPlanRevisionTestFixtures.SECOND_STEP_ID, newlyCompleted),
                request.newlyCompletedFacts());
        assertEquals(currentSnapshot, currentPlan);
        assertEquals(draft.steps(), result.latestRevision().steps());
        assertEquals(2, result.latestRevision().completedFacts().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> draft.steps().add(draft.steps().get(0)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.newlyCompletedFacts().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.revisions().add(result.latestRevision()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.latestRevision().completedFacts().clear());
    }

    private static void assertRequestMapViolation(
            Plan currentPlan,
            NextPlanRevisionDraft draft,
            Map<PlanStepId, CompletionFact> map) {
        assertViolation(
                NextPlanRevisionFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                "nextPlanRevisionFreezeRequest.newlyCompletedFacts",
                () -> NextPlanRevisionTestFixtures.request(
                        PlanningTestFixtures.declaredRequirementDecision(
                                "routing-next-null-map"),
                        currentPlan,
                        new PlanRevisionId("revision-next-null-map"),
                        draft,
                        NextPlanRevisionTestFixtures.NEXT_CREATED_AT,
                        map));
    }

    private static NextPlanRevisionFreezeValidationException assertViolation(
            NextPlanRevisionFreezeValidationCode expectedCode,
            String expectedPath,
            Runnable action) {
        NextPlanRevisionFreezeValidationException exception =
                assertThrows(
                        NextPlanRevisionFreezeValidationException.class,
                        action::run);
        assertEquals(expectedCode, exception.code());
        assertEquals(expectedPath, exception.path());
        return exception;
    }
}
