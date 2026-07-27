package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRecoveryRepositoryConcurrencyTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void inspectionRacingBootstrapSeesOnlyNotFoundOrReady()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            InMemoryPersistence persistence = new InMemoryPersistence(
                    new PersistenceFixtures.MutableCountingClock(
                            PersistenceFixtures.T0));
            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<PersistedPlanBootstrap>> race = race(
                    () -> persistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> persistence.planBootstraps().bootstrap(
                            PersistenceFixtures.taskFrame(),
                            PersistenceFixtures.plan(),
                            PersistenceFixtures.initialCheckpoint(
                                    PersistenceFixtures.plan())));

            assertEquals(PersistenceOutcome.APPLIED, race.second().outcome());
            assertNotFoundOrSnapshot(
                    race.first(), PersistedExecutionStartReady.class);
        }
    }

    @Test
    void inspectionRacingAtomicStartSeesOnlyReadyOrCommitted()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            InMemoryPersistence persistence = bootstrapped();
            String token = "start-race-" + iteration;
            LeaseRecord lease = requireApplied(persistence.leases().acquire(
                    PersistenceFixtures.PLAN_ID,
                    "owner",
                    token,
                    PersistenceFixtures.T0.plusSeconds(30)));
            ExecutionStartRequest request =
                    PersistenceFixtures.executionStartRequest(
                            PersistenceFixtures.plan(),
                            token,
                            lease.fencingToken(),
                            "start-event-" + iteration);

            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<PersistedExecutionStart>> race = race(
                    () -> persistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> persistence.executionStarts().start(request));

            assertEquals(PersistenceOutcome.APPLIED, race.second().outcome());
            assertSnapshotOneOf(
                    race.first(),
                    PersistedExecutionStartReady.class,
                    PersistedExecutionStartCommitted.class);
        }
    }

    @Test
    void inspectionRacingRevisionAppendSeesRealOldOrNewCuts()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            InMemoryPersistence readyPersistence = bootstrapped();
            PlanRevision revision = PersistenceFixtures.revision2(
                    "ready-revision-2", "ready race");
            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<Plan>> readyRace = race(
                    () -> readyPersistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> readyPersistence.plans().appendRevision(
                            PersistenceFixtures.PLAN_ID, 1, revision));
            assertEquals(PersistenceOutcome.APPLIED, readyRace.second().outcome());
            PersistedExecutionStartReady ready =
                    (PersistedExecutionStartReady) readyRace.first()
                            .value()
                            .orElseThrow();
            assertTrue(
                    ready.currentPlan().revisions().size() == 1
                            || ready.currentPlan().equals(
                                    readyRace.second().value().orElseThrow()));

            InMemoryPersistence committedPersistence = bootstrapped();
            start(
                    committedPersistence,
                    PersistenceFixtures.plan(),
                    "committed-token-" + iteration,
                    "committed-event-" + iteration);
            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<Plan>> committedRace = race(
                    () -> committedPersistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> committedPersistence.plans().appendRevision(
                            PersistenceFixtures.PLAN_ID,
                            1,
                            PersistenceFixtures.revision2(
                                    "committed-revision-2",
                                    "committed race")));
            assertEquals(
                    PersistenceOutcome.REJECTED,
                    committedRace.second().outcome());
            assertEquals(
                    PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                    committedRace.second().failure().orElseThrow().code());
            assertSnapshot(
                    committedRace.first(),
                    PersistedExecutionStartCommitted.class);
        }
    }

    @Test
    void leaseReleaseExpiryAndTakeoverRacesRemainCommitted()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            InMemoryPersistence releasePersistence = bootstrapped();
            String releaseToken = "release-token-" + iteration;
            start(
                    releasePersistence,
                    PersistenceFixtures.plan(),
                    releaseToken,
                    "release-event-" + iteration);
            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<LeaseRecord>> releaseRace = race(
                    () -> releasePersistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> releasePersistence.leases().release(
                            PersistenceFixtures.PLAN_ID, releaseToken));
            assertEquals(PersistenceOutcome.APPLIED, releaseRace.second().outcome());
            assertSnapshot(
                    releaseRace.first(), PersistedExecutionStartCommitted.class);

            PersistenceFixtures.MutableCountingClock takeoverClock =
                    new PersistenceFixtures.MutableCountingClock(
                            PersistenceFixtures.T0);
            InMemoryPersistence takeoverPersistence =
                    bootstrapped(takeoverClock);
            start(
                    takeoverPersistence,
                    PersistenceFixtures.plan(),
                    "expired-token-" + iteration,
                    "takeover-event-" + iteration);
            takeoverClock.set(PersistenceFixtures.T0.plusSeconds(70));
            String takeoverToken = "takeover-token-" + iteration;
            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<LeaseRecord>> takeoverRace = race(
                    () -> takeoverPersistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> takeoverPersistence.leases().acquire(
                            PersistenceFixtures.PLAN_ID,
                            "takeover-owner",
                            takeoverToken,
                            PersistenceFixtures.T0.plusSeconds(100)));
            assertEquals(
                    PersistenceOutcome.APPLIED, takeoverRace.second().outcome());
            assertSnapshot(
                    takeoverRace.first(), PersistedExecutionStartCommitted.class);

            InMemoryPersistence advancedPersistence = bootstrapped();
            String advancedToken = "advanced-lease-token-" + iteration;
            PersistedExecutionStart advancedStart = start(
                    advancedPersistence,
                    PersistenceFixtures.plan(),
                    advancedToken,
                    "advanced-lease-event-" + iteration);
            PersistenceFixtures.confirmExecutionContext(
                    advancedPersistence.planExecutionContexts(),
                    PersistenceFixtures.plan(),
                    advancedToken,
                    advancedStart.fencingToken(),
                    PersistenceFixtures.workspaceSpec(
                            "advanced-" + iteration));
            requireApplied(advancedPersistence.stepActivations().activate(
                    PersistenceFixtures.stepActivationRequest(
                            PersistenceFixtures.plan(),
                            advancedToken,
                            advancedStart.fencingToken(),
                            "advanced-progress-" + iteration)));
            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    PersistenceResult<LeaseRecord>> advancedRace = race(
                    () -> advancedPersistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> advancedPersistence.leases().release(
                            PersistenceFixtures.PLAN_ID, advancedToken));
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    advancedRace.second().outcome());
            assertAdvanced(advancedRace.first());
        }
    }

    @Test
    void legalProgressRacingCanonicalCommittedNeverLooksPartial()
            throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            InMemoryPersistence persistence = bootstrapped();
            PersistedExecutionStart progressStart = start(
                    persistence,
                    PersistenceFixtures.plan(),
                    "progress-token-" + iteration,
                    "progress-start-" + iteration);
            PersistenceFixtures.confirmExecutionContext(
                    persistence.planExecutionContexts(),
                    PersistenceFixtures.plan(),
                    "progress-token-" + iteration,
                    progressStart.fencingToken(),
                    PersistenceFixtures.workspaceSpec(
                            "progress-" + iteration));
            int suffix = iteration;

            RaceResult<
                    PersistenceResult<ExecutionStartRecoverySnapshot>,
                    Boolean> race = race(
                    () -> persistence.executionStartRecovery()
                            .inspect(PersistenceFixtures.PLAN_ID),
                    () -> {
                        requireApplied(
                                persistence.stepActivations().activate(
                                        PersistenceFixtures
                                                .stepActivationRequest(
                                                        PersistenceFixtures.plan(),
                                                        "progress-token-" + suffix,
                                                        progressStart.fencingToken(),
                                                        "progress-event-" + suffix)));
                        return true;
                    });

            assertTrue(race.second());
            assertCommittedOrAdvanced(race.first());
            assertAdvanced(persistence.executionStartRecovery()
                    .inspect(PersistenceFixtures.PLAN_ID));
        }
    }

    @Test
    void manyInspectionsHaveNoHiddenStateAndPlansRemainIndependent()
            throws Exception {
        InMemoryPersistence persistence = bootstrapped();
        start(
                persistence,
                PersistenceFixtures.plan(),
                "many-token",
                "many-event");

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<PersistenceResult<ExecutionStartRecoverySnapshot>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                futures.add(executor.submit(() ->
                        persistence.executionStartRecovery()
                                .inspect(PersistenceFixtures.PLAN_ID)));
            }
            PersistenceResult<ExecutionStartRecoverySnapshot> expected =
                    futures.get(0).get(
                            TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            for (Future<PersistenceResult<ExecutionStartRecoverySnapshot>> future :
                    futures) {
                assertEquals(
                        expected,
                        future.get(
                                TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            }
            assertSnapshot(
                    expected, PersistedExecutionStartCommitted.class);
        } finally {
            terminate(executor);
        }

        TaskFrameId secondTaskId = new TaskFrameId("task-2");
        PlanId secondPlanId = new PlanId("plan-2");
        TaskFrame secondTask =
                PersistenceFixtures.taskFrame(secondTaskId, "Second independent task");
        Plan secondPlan = PersistenceFixtures.plan(
                secondPlanId, secondTaskId, "second");
        requireApplied(persistence.planBootstraps().bootstrap(
                secondTask,
                secondPlan,
                PersistenceFixtures.initialCheckpoint(secondPlan)));

        RaceResult<
                PersistenceResult<ExecutionStartRecoverySnapshot>,
                PersistenceResult<ExecutionStartRecoverySnapshot>> independent =
                race(
                        () -> persistence.executionStartRecovery()
                                .inspect(PersistenceFixtures.PLAN_ID),
                        () -> persistence.executionStartRecovery()
                                .inspect(secondPlanId));
        assertSnapshot(
                independent.first(), PersistedExecutionStartCommitted.class);
        assertSnapshot(
                independent.second(), PersistedExecutionStartReady.class);
        assertEquals(
                PersistenceFixtures.PLAN_ID,
                independent.first().value().orElseThrow().planId());
        assertEquals(
                secondPlanId,
                independent.second().value().orElseThrow().planId());
    }

    private static Checkpoint progressedCheckpoint(Plan plan, long sequence) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        plan.latestRevision().steps().forEach(step ->
                states.put(step.id(), StepExecutionState.NOT_STARTED));
        states.put(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE);
        return new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                sequence,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                PersistenceFixtures.T0.plusSeconds(10));
    }

    private static PersistedExecutionStart start(
            InMemoryPersistence persistence,
            Plan plan,
            String leaseToken,
            String eventId) {
        LeaseRecord lease = requireApplied(persistence.leases().acquire(
                plan.id(),
                "owner",
                leaseToken,
                PersistenceFixtures.T0.plusSeconds(60)));
        return requireApplied(persistence.executionStarts().start(
                PersistenceFixtures.executionStartRequest(
                        plan,
                        leaseToken,
                        lease.fencingToken(),
                        eventId)));
    }

    private static InMemoryPersistence bootstrapped() {
        return bootstrapped(new PersistenceFixtures.MutableCountingClock(
                PersistenceFixtures.T0));
    }

    private static InMemoryPersistence bootstrapped(java.time.Clock clock) {
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        requireApplied(persistence.planBootstraps().bootstrap(
                PersistenceFixtures.taskFrame(),
                PersistenceFixtures.plan(),
                PersistenceFixtures.initialCheckpoint(
                        PersistenceFixtures.plan())));
        return persistence;
    }

    private static void assertNotFoundOrSnapshot(
            PersistenceResult<ExecutionStartRecoverySnapshot> result,
            Class<? extends ExecutionStartRecoverySnapshot> snapshotType) {
        if (result.outcome() == PersistenceOutcome.REJECTED) {
            assertEquals(
                    PersistenceErrorCode.NOT_FOUND,
                    result.failure().orElseThrow().code());
            assertEquals("planId", result.failure().orElseThrow().path());
        } else {
            assertSnapshot(result, snapshotType);
        }
    }

    @SafeVarargs
    private static void assertSnapshotOneOf(
            PersistenceResult<ExecutionStartRecoverySnapshot> result,
            Class<? extends ExecutionStartRecoverySnapshot>... allowed) {
        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        ExecutionStartRecoverySnapshot snapshot = result.value().orElseThrow();
        assertTrue(
                List.of(allowed).stream().anyMatch(type -> type.isInstance(snapshot)),
                snapshot.toString());
    }

    private static void assertSnapshot(
            PersistenceResult<ExecutionStartRecoverySnapshot> result,
            Class<? extends ExecutionStartRecoverySnapshot> snapshotType) {
        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        assertTrue(snapshotType.isInstance(result.value().orElseThrow()));
    }

    private static void assertCommittedOrAdvanced(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        if (result.outcome() == PersistenceOutcome.FOUND) {
            assertSnapshot(result, PersistedExecutionStartCommitted.class);
        } else {
            assertAdvanced(result);
        }
    }

    private static void assertAdvanced(
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                result.failure().orElseThrow().code());
        assertEquals(
                "executionRecovery", result.failure().orElseThrow().path());
    }

    private static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    private static <A, B> RaceResult<A, B> race(
            Callable<A> first,
            Callable<B> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<A> firstFuture = executor.submit(
                () -> awaitAndCall(ready, start, first));
        Future<B> secondFuture = executor.submit(
                () -> awaitAndCall(ready, start, second));
        try {
            assertTrue(
                    ready.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "workers did not become ready");
            start.countDown();
            return new RaceResult<>(
                    firstFuture.get(
                            TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    secondFuture.get(
                            TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } finally {
            start.countDown();
            terminate(executor);
        }
    }

    private static <T> T awaitAndCall(
            CountDownLatch ready,
            CountDownLatch start,
            Callable<T> task)
            throws Exception {
        ready.countDown();
        if (!start.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("race start timed out");
        }
        return task.call();
    }

    private static void terminate(ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();
        assertTrue(
                executor.awaitTermination(
                        TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                "executor did not terminate");
    }

    private record RaceResult<A, B>(A first, B second) {
    }
}
