package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanBootstrapRepositoryTest {
    @Test
    void virginBootstrapAppliesCompleteTupleAndLostResponseRetryReplaysVersionOne() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);

        PersistenceResult<PersistedPlanBootstrap> first =
                persistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint);

        PersistedPlanBootstrap expected = new PersistedPlanBootstrap(
                taskFrame, plan, new VersionedCheckpoint(1, checkpoint));
        assertEquals(PersistenceOutcome.APPLIED, first.outcome());
        assertEquals(expected, first.value().orElseThrow());
        assertEquals(taskFrame, persistence.taskFrames().find(taskFrame.id()).value().orElseThrow());
        assertEquals(plan, persistence.plans().find(plan.id()).value().orElseThrow());
        assertEquals(
                expected.initialCheckpoint(),
                persistence.checkpoints().find(plan.id()).value().orElseThrow());

        PersistenceResult<PersistedPlanBootstrap> retryAfterDiscardedResponse =
                persistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint);

        assertEquals(PersistenceOutcome.REPLAYED, retryAfterDiscardedResponse.outcome());
        assertEquals(expected, retryAfterDiscardedResponse.value().orElseThrow());
        assertEquals(
                1,
                persistence.checkpoints().find(plan.id()).value().orElseThrow().version());
    }

    @Test
    void markerRejectsEachConflictingSuppliedValueWithoutChangingStoredTuple() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);
        persistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint);

        TaskFrame changedTaskFrame =
                PersistenceFixtures.taskFrame(PersistenceFixtures.TASK_ID, "Changed objective");
        Plan changedPlan = initialPlan(
                PersistenceFixtures.PLAN_ID,
                PersistenceFixtures.TASK_ID,
                "changed",
                PersistenceFixtures.revision1().steps());
        Checkpoint changedCheckpoint = initialCheckpoint(
                plan, checkpoint.createdAt().plusSeconds(1));

        assertFailure(
                persistence.planBootstraps().bootstrap(changedTaskFrame, plan, checkpoint),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");
        assertFailure(
                persistence.planBootstraps().bootstrap(taskFrame, changedPlan, checkpoint),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");
        assertFailure(
                persistence.planBootstraps().bootstrap(taskFrame, plan, changedCheckpoint),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");
        assertStoredTuple(persistence, taskFrame, plan, checkpoint, 1);
    }

    @Test
    void taskOnlyPartialStateIsNeverAdoptedOrCompleted() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.taskFrames().create(taskFrame).outcome());

        assertPartialTwice(persistence, taskFrame, plan, checkpoint);

        assertEquals(taskFrame, persistence.taskFrames().find(taskFrame.id()).value().orElseThrow());
        assertFailure(
                persistence.plans().find(plan.id()),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertFailure(
                persistence.checkpoints().find(plan.id()),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
    }

    @Test
    void taskAndPlanPartialStateIsNeverCompleted() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);
        persistence.taskFrames().create(taskFrame);
        persistence.plans().create(plan);

        assertPartialTwice(persistence, taskFrame, plan, checkpoint);

        assertEquals(plan, persistence.plans().find(plan.id()).value().orElseThrow());
        assertFailure(
                persistence.checkpoints().find(plan.id()),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
    }

    @Test
    void completeTupleWithoutMarkerRemainsPartialAndDoesNotGainReplayAuthority() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);
        persistence.taskFrames().create(taskFrame);
        persistence.plans().create(plan);
        persistence.checkpoints().save(0, checkpoint);

        assertPartialTwice(persistence, taskFrame, plan, checkpoint);

        assertStoredTuple(persistence, taskFrame, plan, checkpoint, 1);
    }

    @Test
    void exactExistingTaskFrameCannotBootstrapASecondPlan() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan secondPlan = initialPlan(
                new PlanId("plan-2"),
                taskFrame.id(),
                "plan-2",
                PersistenceFixtures.revision1().steps());
        Checkpoint secondCheckpoint = initialCheckpoint(secondPlan);
        persistence.taskFrames().create(taskFrame);

        assertPartialTwice(persistence, taskFrame, secondPlan, secondCheckpoint);

        assertFailure(
                persistence.plans().find(secondPlan.id()),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertFailure(
                persistence.checkpoints().find(secondPlan.id()),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
    }

    @Test
    void validationFailuresAreStableAndWriteNothingBeforeValidRetry() {
        TaskFrame validTaskFrame = PersistenceFixtures.taskFrame();
        Plan validPlan = PersistenceFixtures.plan();
        Checkpoint validCheckpoint = initialCheckpoint(validPlan);

        InMemoryPersistence mismatchPersistence = new InMemoryPersistence();
        TaskFrameId otherTaskId = new TaskFrameId("task-other");
        Plan otherTaskPlan = initialPlan(
                validPlan.id(),
                otherTaskId,
                "other-task",
                validPlan.latestRevision().steps());
        assertRejectedThenValid(
                mismatchPersistence,
                mismatchPersistence.planBootstraps().bootstrap(
                        validTaskFrame, otherTaskPlan, initialCheckpoint(otherTaskPlan)),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "plan.taskFrameId",
                validTaskFrame,
                validPlan,
                validCheckpoint);

        InMemoryPersistence canonicalPersistence = new InMemoryPersistence();
        Checkpoint wrongRevision = checkpoint(
                validPlan,
                new PlanRevisionId("wrong-revision"),
                validPlan.latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                notStartedStates(validPlan),
                List.of(),
                validCheckpoint.createdAt());
        assertRejectedThenValid(
                canonicalPersistence,
                canonicalPersistence.planBootstraps().bootstrap(
                        validTaskFrame, validPlan, wrongRevision),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint",
                validTaskFrame,
                validPlan,
                validCheckpoint);

        InMemoryPersistence activePersistence = new InMemoryPersistence();
        Map<PlanStepId, StepExecutionState> activeStates = notStartedStates(validPlan);
        activeStates.put(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE);
        Checkpoint active = checkpoint(
                validPlan,
                validPlan.latestRevision().id(),
                validPlan.latestRevision().number(),
                0,
                PlanExecutionState.ACTIVE,
                activeStates,
                List.of(),
                validCheckpoint.createdAt());
        assertRejectedThenValid(
                activePersistence,
                activePersistence.planBootstraps().bootstrap(
                        validTaskFrame, validPlan, active),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint",
                validTaskFrame,
                validPlan,
                validCheckpoint);

        InMemoryPersistence cursorPersistence = new InMemoryPersistence();
        Checkpoint nonzeroCursor = checkpoint(
                validPlan,
                validPlan.latestRevision().id(),
                validPlan.latestRevision().number(),
                1,
                PlanExecutionState.NOT_STARTED,
                notStartedStates(validPlan),
                List.of(),
                validCheckpoint.createdAt());
        assertRejectedThenValid(
                cursorPersistence,
                cursorPersistence.planBootstraps().bootstrap(
                        validTaskFrame, validPlan, nonzeroCursor),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint",
                validTaskFrame,
                validPlan,
                validCheckpoint);

        InMemoryPersistence receiptPersistence = new InMemoryPersistence();
        Checkpoint withReceipt = checkpoint(
                validPlan,
                validPlan.latestRevision().id(),
                validPlan.latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                notStartedStates(validPlan),
                List.of(new ReceiptId("receipt-1")),
                validCheckpoint.createdAt());
        assertRejectedThenValid(
                receiptPersistence,
                receiptPersistence.planBootstraps().bootstrap(
                        validTaskFrame, validPlan, withReceipt),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint",
                validTaskFrame,
                validPlan,
                validCheckpoint);

        InMemoryPersistence stepStatePersistence = new InMemoryPersistence();
        Map<PlanStepId, StepExecutionState> nonInitialStates = notStartedStates(validPlan);
        nonInitialStates.put(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE);
        Checkpoint nonInitialStep = checkpoint(
                validPlan,
                validPlan.latestRevision().id(),
                validPlan.latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                nonInitialStates,
                List.of(),
                validCheckpoint.createdAt());
        assertRejectedThenValid(
                stepStatePersistence,
                stepStatePersistence.planBootstraps().bootstrap(
                        validTaskFrame, validPlan, nonInitialStep),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint",
                validTaskFrame,
                validPlan,
                validCheckpoint);
    }

    @Test
    void markerAndOccupancyChecksPrecedeTupleValidation() {
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);

        InMemoryPersistence markerPersistence = new InMemoryPersistence();
        markerPersistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint);
        Plan wrongTaskPlan = initialPlan(
                plan.id(),
                new TaskFrameId("task-other"),
                "wrong-task",
                plan.latestRevision().steps());
        assertFailure(
                markerPersistence.planBootstraps().bootstrap(
                        taskFrame, wrongTaskPlan, initialCheckpoint(wrongTaskPlan)),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");

        InMemoryPersistence occupancyPersistence = new InMemoryPersistence();
        occupancyPersistence.taskFrames().create(taskFrame);
        assertFailure(
                occupancyPersistence.planBootstraps().bootstrap(
                        taskFrame, wrongTaskPlan, initialCheckpoint(wrongTaskPlan)),
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                "bootstrap");

        InMemoryState contextState = new InMemoryState(
                java.time.Clock.systemUTC());
        var spec = PersistenceFixtures.workspaceSpec(
                "orphan-bootstrap");
        contextState.workspaceOwners.put(
                spec.workspaceId(),
                new InMemoryState.WorkspaceOwner(plan.id(), spec));
        assertFailure(
                new InMemoryPlanBootstrapRepository(contextState).bootstrap(
                        taskFrame, plan, checkpoint),
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                "bootstrap");
    }

    @Test
    void emptyAndAlreadyRevisedPlansCanStartFromTheirLatestRevision() {
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan emptyPlan = initialPlan(
                new PlanId("empty-plan"),
                taskFrame.id(),
                "empty",
                List.of());
        InMemoryPersistence emptyPersistence = new InMemoryPersistence();
        Checkpoint emptyCheckpoint = initialCheckpoint(emptyPlan);

        PersistenceResult<PersistedPlanBootstrap> emptyResult =
                emptyPersistence.planBootstraps().bootstrap(
                        taskFrame, emptyPlan, emptyCheckpoint);

        assertEquals(PersistenceOutcome.APPLIED, emptyResult.outcome());
        assertEquals(PlanExecutionState.NOT_STARTED, emptyCheckpoint.planState());
        assertTrue(emptyCheckpoint.stepStates().isEmpty());

        Plan revisedPlan = new Plan(
                PersistenceFixtures.PLAN_ID,
                PersistenceFixtures.TASK_ID,
                List.of(
                        PersistenceFixtures.revision1(),
                        PersistenceFixtures.revision2("revision-2", "revised before execution")));
        Checkpoint revisedCheckpoint = initialCheckpoint(revisedPlan);
        InMemoryPersistence revisedPersistence = new InMemoryPersistence();

        PersistenceResult<PersistedPlanBootstrap> revisedResult =
                revisedPersistence.planBootstraps().bootstrap(
                        taskFrame, revisedPlan, revisedCheckpoint);

        assertEquals(PersistenceOutcome.APPLIED, revisedResult.outcome());
        assertEquals(
                revisedPlan.latestRevision().id(),
                revisedResult.value().orElseThrow()
                        .initialCheckpoint().checkpoint().revisionId());
    }

    @Test
    void replayReturnsOriginalMarkerAfterPlanAndCheckpointAdvanceWithoutRollback() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan originalPlan = PersistenceFixtures.plan();
        Checkpoint originalCheckpoint = initialCheckpoint(originalPlan);
        PersistedPlanBootstrap originalBootstrap = persistence.planBootstraps()
                .bootstrap(taskFrame, originalPlan, originalCheckpoint)
                .value().orElseThrow();
        PlanRevision revision2 =
                PersistenceFixtures.revision2("revision-2", "advance execution plan");
        Plan advancedPlan = persistence.plans()
                .appendRevision(originalPlan.id(), 1, revision2)
                .value().orElseThrow();
        Checkpoint advancedCheckpoint = initialCheckpoint(
                advancedPlan, originalCheckpoint.createdAt().plusSeconds(10));

        PersistenceResult<VersionedCheckpoint> saved =
                persistence.checkpoints().save(1, advancedCheckpoint);
        PersistenceResult<PersistedPlanBootstrap> replay =
                persistence.planBootstraps().bootstrap(
                        taskFrame, originalPlan, originalCheckpoint);

        assertEquals(PersistenceOutcome.APPLIED, saved.outcome());
        assertEquals(2, saved.value().orElseThrow().version());
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(originalBootstrap, replay.value().orElseThrow());
        assertEquals(1, replay.value().orElseThrow().plan().latestRevision().number());
        assertEquals(1, replay.value().orElseThrow().initialCheckpoint().version());
        assertEquals(
                advancedPlan,
                persistence.plans().find(originalPlan.id()).value().orElseThrow());
        assertEquals(
                new VersionedCheckpoint(2, advancedCheckpoint),
                persistence.checkpoints().find(originalPlan.id()).value().orElseThrow());
    }

    @Test
    void concurrentIdenticalTupleHasOneAppliedAndAllOtherResultsReplayed() throws Exception {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);
        int participants = 8;
        CyclicBarrier barrier = new CyclicBarrier(participants);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        try {
            List<Future<PersistenceResult<PersistedPlanBootstrap>>> futures = new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                futures.add(executor.submit(
                        () -> bootstrapAfterBarrier(
                                persistence, barrier, taskFrame, plan, checkpoint)));
            }

            List<PersistenceResult<PersistedPlanBootstrap>> results =
                    collect(futures);

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(participants - 1, countOutcome(results, PersistenceOutcome.REPLAYED));
            assertStoredTuple(persistence, taskFrame, plan, checkpoint, 1);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentConflictingTupleHasOneAppliedAndTheOtherConflictingReplay()
            throws Exception {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame firstTaskFrame = PersistenceFixtures.taskFrame();
        TaskFrame secondTaskFrame =
                PersistenceFixtures.taskFrame(PersistenceFixtures.TASK_ID, "Competing objective");
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedPlanBootstrap>> first = executor.submit(
                    () -> bootstrapAfterBarrier(
                            persistence, barrier, firstTaskFrame, plan, checkpoint));
            Future<PersistenceResult<PersistedPlanBootstrap>> second = executor.submit(
                    () -> bootstrapAfterBarrier(
                            persistence, barrier, secondTaskFrame, plan, checkpoint));

            List<PersistenceResult<PersistedPlanBootstrap>> results =
                    collect(List.of(first, second));

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    1,
                    countFailure(results, PersistenceErrorCode.CONFLICTING_REPLAY, "plan.id"));
            PersistedPlanBootstrap winner = results.stream()
                    .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                    .findFirst().orElseThrow()
                    .value().orElseThrow();
            assertStoredTuple(
                    persistence,
                    winner.taskFrame(),
                    winner.plan(),
                    winner.initialCheckpoint().checkpoint(),
                    1);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentPlansSharingTaskFrameHaveOneAppliedAndOnePartialWithoutMixedTuple()
            throws Exception {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan firstPlan = initialPlan(
                new PlanId("plan-a"),
                taskFrame.id(),
                "plan-a",
                PersistenceFixtures.revision1().steps());
        Plan secondPlan = initialPlan(
                new PlanId("plan-b"),
                taskFrame.id(),
                "plan-b",
                PersistenceFixtures.revision1().steps());
        Checkpoint firstCheckpoint = initialCheckpoint(firstPlan);
        Checkpoint secondCheckpoint = initialCheckpoint(secondPlan);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedPlanBootstrap>> first = executor.submit(
                    () -> bootstrapAfterBarrier(
                            persistence, barrier, taskFrame, firstPlan, firstCheckpoint));
            Future<PersistenceResult<PersistedPlanBootstrap>> second = executor.submit(
                    () -> bootstrapAfterBarrier(
                            persistence, barrier, taskFrame, secondPlan, secondCheckpoint));

            List<PersistenceResult<PersistedPlanBootstrap>> results =
                    collect(List.of(first, second));

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    1,
                    countFailure(
                            results,
                            PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                            "bootstrap"));
            PersistedPlanBootstrap winner = results.stream()
                    .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                    .findFirst().orElseThrow()
                    .value().orElseThrow();
            Plan loser = winner.plan().id().equals(firstPlan.id()) ? secondPlan : firstPlan;
            Checkpoint loserCheckpoint = loser.id().equals(firstPlan.id())
                    ? firstCheckpoint
                    : secondCheckpoint;
            assertStoredTuple(
                    persistence,
                    taskFrame,
                    winner.plan(),
                    winner.initialCheckpoint().checkpoint(),
                    1);
            assertFailure(
                    persistence.plans().find(loser.id()),
                    PersistenceErrorCode.NOT_FOUND,
                    "planId");
            assertFailure(
                    persistence.checkpoints().find(loser.id()),
                    PersistenceErrorCode.NOT_FOUND,
                    "planId");
            assertFailure(
                    persistence.planBootstraps().bootstrap(
                            taskFrame, loser, loserCheckpoint),
                    PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                    "bootstrap");
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void nullInputsAndPersistedSnapshotConstraintsAreStable() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame taskFrame = PersistenceFixtures.taskFrame();
        Plan plan = PersistenceFixtures.plan();
        Checkpoint checkpoint = initialCheckpoint(plan);

        assertFailure(
                persistence.planBootstraps().bootstrap(null, null, null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "taskFrame");
        assertFailure(
                persistence.planBootstraps().bootstrap(taskFrame, null, null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "plan");
        assertFailure(
                persistence.planBootstraps().bootstrap(taskFrame, plan, null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "checkpoint");

        VersionedCheckpoint versionOne = new VersionedCheckpoint(1, checkpoint);
        assertThrows(
                NullPointerException.class,
                () -> new PersistedPlanBootstrap(null, plan, versionOne));
        assertThrows(
                NullPointerException.class,
                () -> new PersistedPlanBootstrap(taskFrame, null, versionOne));
        assertThrows(
                NullPointerException.class,
                () -> new PersistedPlanBootstrap(taskFrame, plan, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersistedPlanBootstrap(
                        taskFrame, plan, new VersionedCheckpoint(2, checkpoint)));

        PersistedPlanBootstrap snapshot = persistence.planBootstraps()
                .bootstrap(taskFrame, plan, checkpoint)
                .value().orElseThrow();
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.taskFrame().targets().add("other"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.plan().revisions().add(PersistenceFixtures.revision1()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.initialCheckpoint().checkpoint()
                        .stepStates().put(
                                PersistenceFixtures.STEP_1,
                                StepExecutionState.ACTIVE));
    }

    private static void assertRejectedThenValid(
            InMemoryPersistence persistence,
            PersistenceResult<?> rejected,
            PersistenceErrorCode expectedCode,
            String expectedPath,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        assertFailure(rejected, expectedCode, expectedPath);
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.planBootstraps()
                        .bootstrap(taskFrame, plan, checkpoint)
                        .outcome());
    }

    private static void assertPartialTwice(
            InMemoryPersistence persistence,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        assertFailure(
                persistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint),
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                "bootstrap");
        assertFailure(
                persistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint),
                PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE,
                "bootstrap");
    }

    private static void assertStoredTuple(
            InMemoryPersistence persistence,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint,
            long checkpointVersion) {
        assertEquals(
                taskFrame,
                persistence.taskFrames().find(taskFrame.id()).value().orElseThrow());
        assertEquals(
                plan,
                persistence.plans().find(plan.id()).value().orElseThrow());
        assertEquals(
                new VersionedCheckpoint(checkpointVersion, checkpoint),
                persistence.checkpoints().find(plan.id()).value().orElseThrow());
    }

    private static Checkpoint initialCheckpoint(Plan plan) {
        return initialCheckpoint(plan, PersistenceFixtures.T0);
    }

    private static Checkpoint initialCheckpoint(Plan plan, Instant createdAt) {
        return checkpoint(
                plan,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                notStartedStates(plan),
                List.of(),
                createdAt);
    }

    private static Map<PlanStepId, StepExecutionState> notStartedStates(Plan plan) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        plan.latestRevision().steps().forEach(
                step -> states.put(step.id(), StepExecutionState.NOT_STARTED));
        return states;
    }

    private static Checkpoint checkpoint(
            Plan plan,
            PlanRevisionId revisionId,
            long revisionNumber,
            long eventSequence,
            PlanExecutionState planState,
            Map<PlanStepId, StepExecutionState> stepStates,
            List<ReceiptId> receiptReferences,
            Instant createdAt) {
        return new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                revisionId,
                revisionNumber,
                eventSequence,
                planState,
                stepStates,
                receiptReferences,
                createdAt);
    }

    private static Plan initialPlan(
            PlanId planId,
            TaskFrameId taskFrameId,
            String suffix,
            List<PlanStep> steps) {
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                PersistenceFixtures.T0,
                steps,
                Map.of());
        return new Plan(planId, taskFrameId, List.of(revision));
    }

    private static PersistenceResult<PersistedPlanBootstrap> bootstrapAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.planBootstraps().bootstrap(taskFrame, plan, checkpoint);
    }

    private static List<PersistenceResult<PersistedPlanBootstrap>> collect(
            List<Future<PersistenceResult<PersistedPlanBootstrap>>> futures)
            throws Exception {
        List<PersistenceResult<PersistedPlanBootstrap>> results = new ArrayList<>();
        for (Future<PersistenceResult<PersistedPlanBootstrap>> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        return List.copyOf(results);
    }

    private static long countOutcome(
            List<PersistenceResult<PersistedPlanBootstrap>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }

    private static long countFailure(
            List<PersistenceResult<PersistedPlanBootstrap>> results,
            PersistenceErrorCode code,
            String path) {
        return results.stream()
                .filter(result -> result.failure()
                        .map(failure -> failure.code() == code && failure.path().equals(path))
                        .orElse(false))
                .count();
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode,
            String expectedPath) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertTrue(result.value().isEmpty());
        assertEquals(expectedCode, result.failure().orElseThrow().code());
        assertEquals(expectedPath, result.failure().orElseThrow().path());
    }
}
