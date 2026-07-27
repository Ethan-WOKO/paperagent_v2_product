package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialPlanDraftValidationTest {
    @Test
    void structuralValidationCodesContainExactlyTheFrozenValues() {
        assertEquals(
                Set.of(
                        InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                        InitialPlanFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                        InitialPlanFreezeValidationCode.ROUTE_NOT_PERSISTENT),
                Set.of(InitialPlanFreezeValidationCode.values()));
    }

    @Test
    void draftRejectsMissingFieldsAndNullElementsWithStablePaths() {
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanDraft.reason",
                () -> new InitialPlanDraft(null, List.of()));
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanDraft.steps",
                () -> new InitialPlanDraft("reason", null));
        ArrayList<PlanStep> withNull = new ArrayList<>();
        withNull.add(PlanningTestFixtures.step(new PlanStepId("step-valid"), Set.of()));
        withNull.add(null);
        assertViolation(
                InitialPlanFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                "initialPlanDraft.steps",
                () -> new InitialPlanDraft("reason", withNull));
    }

    @Test
    void requestRejectsEveryMissingFieldWithStablePaths() {
        var decision =
                PlanningTestFixtures.declaredRequirementDecision("routing-request-validation");
        var taskFrame = PlanningTestFixtures.taskFrame("task-request-validation");
        var planId = PlanningTestFixtures.PLAN_ID;
        var revisionId = PlanningTestFixtures.REVISION_ID;
        var draft = PlanningTestFixtures.twoStepDraft();
        var createdAt = PlanningTestFixtures.CREATED_AT;

        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest.routingDecision",
                () -> new InitialPlanFreezeRequest(
                        null, taskFrame, planId, revisionId, draft, createdAt));
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest.taskFrame",
                () -> new InitialPlanFreezeRequest(
                        decision, null, planId, revisionId, draft, createdAt));
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest.planId",
                () -> new InitialPlanFreezeRequest(
                        decision, taskFrame, null, revisionId, draft, createdAt));
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest.initialRevisionId",
                () -> new InitialPlanFreezeRequest(
                        decision, taskFrame, planId, null, draft, createdAt));
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest.draft",
                () -> new InitialPlanFreezeRequest(
                        decision, taskFrame, planId, revisionId, null, createdAt));
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest.createdAt",
                () -> new InitialPlanFreezeRequest(
                        decision, taskFrame, planId, revisionId, draft, null));
    }

    @Test
    void freezerRejectsMissingTopLevelRequestWithStablePath() {
        assertViolation(
                InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                "initialPlanFreezeRequest",
                () -> new DeterministicInitialPlanFreezer().freeze(null));
    }

    @Test
    void draftAndAuthorityOutputsPreserveOrderedImmutableSnapshots() {
        PlanStep first = PlanningTestFixtures.step(
                new PlanStepId("step-snapshot-1"),
                Set.of());
        PlanStep second = PlanningTestFixtures.step(
                new PlanStepId("step-snapshot-2"),
                Set.of(first.id()));
        ArrayList<PlanStep> callerSteps = new ArrayList<>(List.of(first, second));
        InitialPlanDraft draft = new InitialPlanDraft("snapshot plan", callerSteps);
        InitialPlanFreezeRequest request = PlanningTestFixtures.request(draft);

        callerSteps.clear();
        callerSteps.add(PlanningTestFixtures.step(
                new PlanStepId("step-late-mutation"),
                Set.of()));
        Plan result = new DeterministicInitialPlanFreezer().freeze(request);
        callerSteps.clear();

        assertEquals(List.of(first, second), draft.steps());
        assertEquals(List.of(first, second), result.latestRevision().steps());
        assertThrows(
                UnsupportedOperationException.class,
                () -> draft.steps().add(first));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.revisions().add(result.latestRevision()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.latestRevision().steps().add(first));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.latestRevision().completedFacts().clear());
    }

    private static InitialPlanFreezeValidationException assertViolation(
            InitialPlanFreezeValidationCode expectedCode,
            String expectedPath,
            Runnable action) {
        InitialPlanFreezeValidationException exception =
                assertThrows(InitialPlanFreezeValidationException.class, action::run);
        assertEquals(expectedCode, exception.code());
        assertEquals(expectedPath, exception.path());
        return exception;
    }
}
