package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepCompletionRepositoryBoundaryTest {
    @Test
    void publicValuesValidateEveryDirectComponentAndRedactAllSensitiveSurfaces() {
        StepCompletionRequest request = request();
        PersistedStepCompletion persisted = new PersistedStepCompletion(
                request.planId(),
                request.stepId(),
                "owner-opaque",
                1,
                request.completionEvent(),
                request.completedRevision(),
                new VersionedCheckpoint(4, request.completedCheckpoint()));

        for (String sentinel : List.of(
                "completion-token-opaque",
                "owner-opaque",
                "completion-event-opaque",
                "outcome-opaque",
                "receipt-opaque",
                "revision-completion-opaque")) {
            assertFalse(request.toString().contains(sentinel), request.toString());
            assertFalse(persisted.toString().contains(sentinel), persisted.toString());
        }
        assertThrows(NullPointerException.class, () -> new StepCompletionRequest(
                null,
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.completionFact(),
                request.completionEvent(),
                request.completedRevision(),
                request.completedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new StepCompletionRequest(
                request.planId(),
                " ",
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.completionFact(),
                request.completionEvent(),
                request.completedRevision(),
                request.completedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new StepCompletionRequest(
                request.planId(),
                request.leaseToken(),
                0,
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.completionFact(),
                request.completionEvent(),
                request.completedRevision(),
                request.completedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new StepCompletionRequest(
                request.planId(),
                request.leaseToken(),
                1,
                request.expectedRevisionId(),
                1,
                2,
                2,
                request.stepId(),
                request.completionFact(),
                request.completionEvent(),
                request.completedRevision(),
                request.completedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new PersistedStepCompletion(
                request.planId(),
                request.stepId(),
                "owner",
                1,
                request.completionEvent(),
                request.completedRevision(),
                new VersionedCheckpoint(3, request.completedCheckpoint())));
        PlanRevision wrongTaskFrame = new PlanRevision(
                request.completedRevision().id(),
                new TaskFrameId("other-task-frame"),
                request.completedRevision().number(),
                request.completedRevision().parentRevisionId(),
                request.completedRevision().reason(),
                request.completedRevision().createdAt(),
                request.completedRevision().steps(),
                request.completedRevision().completedFacts());
        assertThrows(IllegalArgumentException.class, () -> new PersistedStepCompletion(
                request.planId(),
                request.stepId(),
                "owner",
                1,
                request.completionEvent(),
                wrongTaskFrame,
                new VersionedCheckpoint(4, request.completedCheckpoint())));
        for (PlanExecutionState invalidPlanState : List.of(
                PlanExecutionState.NOT_STARTED,
                PlanExecutionState.PAUSED,
                PlanExecutionState.FAILED,
                PlanExecutionState.CANCELLED)) {
            Checkpoint invalidPlanStateCheckpoint = new Checkpoint(
                    request.completedCheckpoint().taskFrameId(),
                    request.completedCheckpoint().planId(),
                    request.completedCheckpoint().revisionId(),
                    request.completedCheckpoint().revisionNumber(),
                    request.completedCheckpoint().lastEventSequence(),
                    invalidPlanState,
                    request.completedCheckpoint().stepStates(),
                    request.completedCheckpoint().receiptReferences(),
                    request.completedCheckpoint().createdAt());
            assertThrows(IllegalArgumentException.class, () ->
                    new PersistedStepCompletion(
                            request.planId(),
                            request.stepId(),
                            "owner",
                            1,
                            request.completionEvent(),
                            request.completedRevision(),
                            new VersionedCheckpoint(4, invalidPlanStateCheckpoint)));
        }
    }

    @Test
    void invalidRepositoryInputIsRejectedBeforeClockObservation() {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        clock.failOnObservation();

        PersistenceResult<PersistedStepCompletion> result =
                persistence.stepCompletions().complete(null);
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(PersistenceErrorCode.INVALID_ARGUMENT,
                result.failure().orElseThrow().code());
    }

    private static StepCompletionRequest request() {
        Plan plan = PersistenceFixtures.plan();
        CompletionFact fact = new CompletionFact(
                PersistenceFixtures.STEP_1,
                "outcome-opaque",
                PersistenceFixtures.T0.plusSeconds(4),
                List.of(new ReceiptId("receipt-opaque")));
        Map<io.paperagent.v2.contracts.PlanStepId, CompletionFact> facts =
                new LinkedHashMap<>();
        facts.put(PersistenceFixtures.STEP_1, fact);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-completion-opaque"),
                plan.taskFrameId(),
                2,
                Optional.of(plan.latestRevision().id()),
                "complete opaque",
                PersistenceFixtures.T0.plusSeconds(4),
                plan.latestRevision().steps(),
                facts);
        Checkpoint checkpoint = new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                revision.id(),
                revision.number(),
                4,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.SUCCEEDED,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                List.of(new ReceiptId("receipt-opaque")),
                PersistenceFixtures.T0.plusSeconds(4));
        EventEnvelope event = PersistenceFixtures.event(
                "completion-event-opaque", plan.taskFrameId(), plan.id(), 4);
        return new StepCompletionRequest(
                plan.id(),
                "completion-token-opaque",
                1,
                plan.latestRevision().id(),
                1,
                3,
                3,
                PersistenceFixtures.STEP_1,
                fact,
                event,
                revision,
                checkpoint);
    }
}
