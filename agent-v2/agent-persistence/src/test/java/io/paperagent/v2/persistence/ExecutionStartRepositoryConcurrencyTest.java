package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRepositoryConcurrencyTest {
    private static final String OWNER = "worker-a";
    private static final String TOKEN = "lease-token-a";

    @Test
    void concurrentIdenticalRequestsHaveOneAppliedAndAllOtherReplayed()
            throws Exception {
        Setup setup = setup("concurrent-exact", 30);
        int participants = 8;
        CyclicBarrier barrier = new CyclicBarrier(participants);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        try {
            List<Future<PersistenceResult<PersistedExecutionStart>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                futures.add(executor.submit(
                        () -> startAfterBarrier(
                                setup.persistence(), barrier, setup.request())));
            }
            List<PersistenceResult<PersistedExecutionStart>> results =
                    collect(futures);

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    participants - 1,
                    countOutcome(results, PersistenceOutcome.REPLAYED));
            assertEquals(2, setup.clock().observationCount());
            assertCommitted(setup.persistence(), setup.request());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentDifferentRequestsHaveOneAppliedAndOneConflict()
            throws Exception {
        Setup setup = setup("concurrent-a", 30);
        ExecutionStartRequest competing =
                PersistenceFixtures.executionStartRequest(
                        setup.plan(), TOKEN, 1, "concurrent-b");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<PersistenceResult<PersistedExecutionStart>> results = collect(List.of(
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, setup.request())),
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, competing))));

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    1,
                    countFailure(
                            results,
                            PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.planId"));
            assertEquals(2, setup.clock().observationCount());
            ExecutionStartRequest winner = results.get(0).outcome()
                    == PersistenceOutcome.APPLIED
                    ? setup.request()
                    : competing;
            assertCommitted(setup.persistence(), winner);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void startAndReleaseSerializeWithoutPartialCommit() throws Exception {
        Setup setup = setup("release-race", 30);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedExecutionStart>> start =
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, setup.request()));
            Future<PersistenceResult<LeaseRecord>> release =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return setup.persistence().leases().release(
                                setup.plan().id(), TOKEN);
                    });

            PersistenceResult<PersistedExecutionStart> startResult =
                    start.get(5, TimeUnit.SECONDS);
            PersistenceResult<LeaseRecord> releaseResult =
                    release.get(5, TimeUnit.SECONDS);
            assertEquals(PersistenceOutcome.APPLIED, releaseResult.outcome());
            if (startResult.outcome() == PersistenceOutcome.APPLIED) {
                assertCommitted(setup.persistence(), setup.request());
            } else {
                assertFailure(
                        startResult,
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId");
                assertUnstarted(setup);
            }
            assertEquals(3, setup.clock().observationCount());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void expiryTakeoverAlwaysFencesOldStartAndNewFenceCanStart()
            throws Exception {
        Setup setup = setup("takeover-race", 10);
        setup.clock().set(PersistenceFixtures.T0.plusSeconds(10));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedExecutionStart>> staleStart =
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, setup.request()));
            Future<PersistenceResult<LeaseRecord>> takeover =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return setup.persistence().leases().acquire(
                                setup.plan().id(),
                                "worker-b",
                                "lease-token-b",
                                PersistenceFixtures.T0.plusSeconds(30));
                    });

            PersistenceResult<PersistedExecutionStart> staleResult =
                    staleStart.get(5, TimeUnit.SECONDS);
            LeaseRecord current = takeover.get(5, TimeUnit.SECONDS)
                    .value().orElseThrow();
            assertEquals(PersistenceOutcome.REJECTED, staleResult.outcome());
            assertTrue(
                    staleResult.failure()
                            .map(failure -> failure.code()
                                    == PersistenceErrorCode.LEASE_EXPIRED
                                    || failure.code()
                                    == PersistenceErrorCode.LEASE_TOKEN_INVALID)
                            .orElse(false));
            assertEquals(2, current.fencingToken());
            assertUnstarted(setup);

            ExecutionStartRequest wrongFence = new ExecutionStartRequest(
                    setup.plan().id(),
                    "lease-token-b",
                    1,
                    setup.request().startEvent(),
                    setup.request().startedCheckpoint());
            assertFailure(
                    setup.persistence().executionStarts().start(wrongFence),
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
            ExecutionStartRequest currentRequest = new ExecutionStartRequest(
                    setup.plan().id(),
                    "lease-token-b",
                    2,
                    setup.request().startEvent(),
                    setup.request().startedCheckpoint());
            assertEquals(
                    PersistenceOutcome.APPLIED,
                    setup.persistence().executionStarts().start(currentRequest).outcome());
            assertCommitted(setup.persistence(), currentRequest);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void startAndSequenceOneAppendSerializeAtSharedMonitor() throws Exception {
        Setup setup = setup("event-race", 30);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedExecutionStart>> start =
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, setup.request()));
            Future<PersistenceResult<EventEnvelope>> append =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return setup.persistence().events()
                                .append(setup.request().startEvent());
                    });

            PersistenceResult<PersistedExecutionStart> startResult =
                    start.get(5, TimeUnit.SECONDS);
            PersistenceResult<EventEnvelope> appendResult =
                    append.get(5, TimeUnit.SECONDS);
            if (startResult.outcome() == PersistenceOutcome.APPLIED) {
                assertEquals(PersistenceOutcome.REPLAYED, appendResult.outcome());
                assertCommitted(setup.persistence(), setup.request());
            } else {
                assertFailure(
                        startResult,
                        PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                        "executionStart");
                assertEquals(PersistenceOutcome.APPLIED, appendResult.outcome());
                assertEquals(
                        setup.source(),
                        setup.persistence().checkpoints()
                                .find(setup.plan().id()).value().orElseThrow());
            }
            assertEquals(
                    List.of(setup.request().startEvent()),
                    setup.persistence().events()
                            .readAfter(setup.plan().id(), 0).value().orElseThrow());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void startAndExpectedVersionOneCheckpointSaveSerialize()
            throws Exception {
        Setup setup = setup("checkpoint-race", 30);
        Checkpoint ordinary = new Checkpoint(
                setup.plan().taskFrameId(),
                setup.plan().id(),
                setup.plan().latestRevision().id(),
                setup.plan().latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                setup.source().checkpoint().stepStates(),
                List.of(),
                PersistenceFixtures.T0.plusSeconds(1));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedExecutionStart>> start =
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, setup.request()));
            Future<PersistenceResult<VersionedCheckpoint>> save =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return setup.persistence().checkpoints().save(1, ordinary);
                    });

            PersistenceResult<PersistedExecutionStart> startResult =
                    start.get(5, TimeUnit.SECONDS);
            PersistenceResult<VersionedCheckpoint> saveResult =
                    save.get(5, TimeUnit.SECONDS);
            if (startResult.outcome() == PersistenceOutcome.APPLIED) {
                assertFailure(
                        saveResult,
                        PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                        "checkpoint.planId");
                assertCommitted(setup.persistence(), setup.request());
            } else {
                assertFailure(
                        startResult,
                        PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                        "executionStart");
                assertEquals(PersistenceOutcome.APPLIED, saveResult.outcome());
                assertEquals(
                        new VersionedCheckpoint(2, ordinary),
                        setup.persistence().checkpoints()
                                .find(setup.plan().id()).value().orElseThrow());
                assertTrue(setup.persistence().events()
                        .readAfter(setup.plan().id(), 0).value().orElseThrow().isEmpty());
            }
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void startAndRevisionAppendSerializeAgainstLatestRevision()
            throws Exception {
        Setup setup = setup("revision-race", 30);
        PlanRevision revision =
                PersistenceFixtures.revision2("revision-race-2", "race");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<PersistedExecutionStart>> start =
                    executor.submit(() -> startAfterBarrier(
                            setup.persistence(), barrier, setup.request()));
            Future<PersistenceResult<Plan>> append =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return setup.persistence().plans().appendRevision(
                                setup.plan().id(), 1, revision);
                    });

            PersistenceResult<PersistedExecutionStart> startResult =
                    start.get(5, TimeUnit.SECONDS);
            PersistenceResult<Plan> appendResult =
                    append.get(5, TimeUnit.SECONDS);
            if (startResult.outcome() == PersistenceOutcome.APPLIED) {
                assertFailure(
                        appendResult,
                        PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                        "planId");
                assertCommitted(setup.persistence(), setup.request());
                assertEquals(
                        1,
                        setup.persistence().plans()
                                .find(setup.plan().id())
                                .value().orElseThrow()
                                .latestRevision().number());
            } else {
                assertEquals(PersistenceOutcome.APPLIED, appendResult.outcome());
                assertFailure(
                        startResult,
                        PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                        "request.startedCheckpoint");
                assertUnstarted(setup);
                assertEquals(
                        2,
                        setup.persistence().plans()
                                .find(setup.plan().id())
                                .value().orElseThrow()
                                .latestRevision().number());
            }
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void releaseHasDeterministicStartFirstAndContenderFirstOutcomes() {
        Setup startFirst = setup("release-start-first", 30);
        assertEquals(
                PersistenceOutcome.APPLIED,
                startFirst.persistence().executionStarts()
                        .start(startFirst.request()).outcome());
        assertEquals(
                PersistenceOutcome.APPLIED,
                startFirst.persistence().leases()
                        .release(startFirst.plan().id(), TOKEN).outcome());
        assertCommitted(startFirst.persistence(), startFirst.request());

        Setup contenderFirst = setup("release-contender-first", 30);
        assertEquals(
                PersistenceOutcome.APPLIED,
                contenderFirst.persistence().leases()
                        .release(contenderFirst.plan().id(), TOKEN).outcome());
        assertFailure(
                contenderFirst.persistence().executionStarts()
                        .start(contenderFirst.request()),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "request.planId");
        assertUnstarted(contenderFirst);
    }

    @Test
    void eventAppendHasDeterministicStartFirstAndContenderFirstOutcomes() {
        Setup startFirst = setup("event-start-first", 30);
        assertEquals(
                PersistenceOutcome.APPLIED,
                startFirst.persistence().executionStarts()
                        .start(startFirst.request()).outcome());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                startFirst.persistence().events()
                        .append(startFirst.request().startEvent()).outcome());
        assertCommitted(startFirst.persistence(), startFirst.request());

        Setup contenderFirst = setup("event-contender-first", 30);
        assertEquals(
                PersistenceOutcome.APPLIED,
                contenderFirst.persistence().events()
                        .append(contenderFirst.request().startEvent()).outcome());
        assertFailure(
                contenderFirst.persistence().executionStarts()
                        .start(contenderFirst.request()),
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(
                contenderFirst.source(),
                contenderFirst.persistence().checkpoints()
                        .find(contenderFirst.plan().id()).value().orElseThrow());
        assertEquals(
                List.of(contenderFirst.request().startEvent()),
                contenderFirst.persistence().events()
                        .readAfter(contenderFirst.plan().id(), 0)
                        .value().orElseThrow());
    }

    @Test
    void checkpointSaveHasDeterministicStartFirstAndContenderFirstOutcomes() {
        Setup startFirst = setup("checkpoint-start-first", 30);
        Checkpoint startFirstCandidate = ordinaryCheckpoint(startFirst);
        assertEquals(
                PersistenceOutcome.APPLIED,
                startFirst.persistence().executionStarts()
                        .start(startFirst.request()).outcome());
        assertFailure(
                startFirst.persistence().checkpoints()
                        .save(1, startFirstCandidate),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "checkpoint.planId");
        assertCommitted(startFirst.persistence(), startFirst.request());

        Setup contenderFirst = setup("checkpoint-contender-first", 30);
        Checkpoint contenderFirstCandidate = ordinaryCheckpoint(contenderFirst);
        assertEquals(
                PersistenceOutcome.APPLIED,
                contenderFirst.persistence().checkpoints()
                        .save(1, contenderFirstCandidate).outcome());
        assertFailure(
                contenderFirst.persistence().executionStarts()
                        .start(contenderFirst.request()),
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(
                new VersionedCheckpoint(2, contenderFirstCandidate),
                contenderFirst.persistence().checkpoints()
                        .find(contenderFirst.plan().id()).value().orElseThrow());
        assertTrue(contenderFirst.persistence().events()
                .readAfter(contenderFirst.plan().id(), 0)
                .value().orElseThrow().isEmpty());
    }

    @Test
    void revisionAppendHasDeterministicStartFirstAndContenderFirstOutcomes() {
        Setup startFirst = setup("revision-start-first", 30);
        PlanRevision startFirstRevision = PersistenceFixtures.revision2(
                "revision-start-first-2", "start first");
        assertEquals(
                PersistenceOutcome.APPLIED,
                startFirst.persistence().executionStarts()
                        .start(startFirst.request()).outcome());
        assertFailure(
                startFirst.persistence().plans()
                        .appendRevision(
                                startFirst.plan().id(),
                                1,
                                startFirstRevision),
                PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                "planId");
        assertCommitted(startFirst.persistence(), startFirst.request());
        assertEquals(
                1,
                startFirst.persistence().plans()
                        .find(startFirst.plan().id())
                        .value().orElseThrow()
                        .latestRevision().number());

        Setup contenderFirst = setup("revision-contender-first", 30);
        PlanRevision contenderFirstRevision = PersistenceFixtures.revision2(
                "revision-contender-first-2", "contender first");
        assertEquals(
                PersistenceOutcome.APPLIED,
                contenderFirst.persistence().plans()
                        .appendRevision(
                                contenderFirst.plan().id(),
                                1,
                                contenderFirstRevision)
                        .outcome());
        assertFailure(
                contenderFirst.persistence().executionStarts()
                        .start(contenderFirst.request()),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint");
        assertUnstarted(contenderFirst);
        assertEquals(
                2,
                contenderFirst.persistence().plans()
                        .find(contenderFirst.plan().id())
                        .value().orElseThrow()
                        .latestRevision().number());
    }

    @Test
    void differentPlansCanStartIndependently() throws Exception {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        TaskFrame firstTask = PersistenceFixtures.taskFrame();
        Plan firstPlan = PersistenceFixtures.plan();
        TaskFrameId secondTaskId = new TaskFrameId("task-independent");
        TaskFrame secondTask =
                PersistenceFixtures.taskFrame(secondTaskId, "Independent task");
        Plan secondPlan = PersistenceFixtures.plan(
                new PlanId("plan-independent"), secondTaskId, "independent");
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.planBootstraps().bootstrap(
                        firstTask,
                        firstPlan,
                        PersistenceFixtures.initialCheckpoint(firstPlan)).outcome());
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.planBootstraps().bootstrap(
                        secondTask,
                        secondPlan,
                        PersistenceFixtures.initialCheckpoint(secondPlan)).outcome());
        LeaseRecord firstLease = persistence.leases().acquire(
                        firstPlan.id(), "worker-a", "token-a",
                        PersistenceFixtures.T0.plusSeconds(30))
                .value().orElseThrow();
        LeaseRecord secondLease = persistence.leases().acquire(
                        secondPlan.id(), "worker-b", "token-b",
                        PersistenceFixtures.T0.plusSeconds(30))
                .value().orElseThrow();
        ExecutionStartRequest firstRequest =
                PersistenceFixtures.executionStartRequest(
                        firstPlan, "token-a", firstLease.fencingToken(), "start-a");
        ExecutionStartRequest secondRequest =
                PersistenceFixtures.executionStartRequest(
                        secondPlan, "token-b", secondLease.fencingToken(), "start-b");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<PersistenceResult<PersistedExecutionStart>> results = collect(List.of(
                    executor.submit(() -> startAfterBarrier(
                            persistence, barrier, firstRequest)),
                    executor.submit(() -> startAfterBarrier(
                            persistence, barrier, secondRequest))));
            assertEquals(2, countOutcome(results, PersistenceOutcome.APPLIED));
            assertCommitted(persistence, firstRequest);
            assertCommitted(persistence, secondRequest);
        } finally {
            shutdown(executor);
        }
    }

    private static Setup setup(String eventId, long leaseSeconds) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence persistence =
                PersistenceFixtures.bootstrappedPersistence(clock);
        Plan plan = PersistenceFixtures.plan();
        LeaseRecord lease = persistence.leases().acquire(
                        plan.id(),
                        OWNER,
                        TOKEN,
                        PersistenceFixtures.T0.plusSeconds(leaseSeconds))
                .value().orElseThrow();
        return new Setup(
                clock,
                persistence,
                plan,
                persistence.checkpoints().find(plan.id()).value().orElseThrow(),
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), eventId));
    }

    private static Checkpoint ordinaryCheckpoint(Setup setup) {
        return new Checkpoint(
                setup.plan().taskFrameId(),
                setup.plan().id(),
                setup.plan().latestRevision().id(),
                setup.plan().latestRevision().number(),
                0,
                PlanExecutionState.NOT_STARTED,
                setup.source().checkpoint().stepStates(),
                List.of(),
                PersistenceFixtures.T0.plusSeconds(1));
    }

    private static PersistenceResult<PersistedExecutionStart> startAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            ExecutionStartRequest request) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.executionStarts().start(request);
    }

    private static List<PersistenceResult<PersistedExecutionStart>> collect(
            List<Future<PersistenceResult<PersistedExecutionStart>>> futures)
            throws Exception {
        List<PersistenceResult<PersistedExecutionStart>> results =
                new ArrayList<>();
        for (Future<PersistenceResult<PersistedExecutionStart>> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        return List.copyOf(results);
    }

    private static long countOutcome(
            List<PersistenceResult<PersistedExecutionStart>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }

    private static long countFailure(
            List<PersistenceResult<PersistedExecutionStart>> results,
            PersistenceErrorCode code,
            String path) {
        return results.stream()
                .filter(result -> result.failure()
                        .map(failure ->
                                failure.code() == code && failure.path().equals(path))
                        .orElse(false))
                .count();
    }

    private static void assertCommitted(
            InMemoryPersistence persistence,
            ExecutionStartRequest request) {
        assertEquals(
                List.of(request.startEvent()),
                persistence.events()
                        .readAfter(request.planId(), 0).value().orElseThrow());
        assertEquals(
                new VersionedCheckpoint(2, request.startedCheckpoint()),
                persistence.checkpoints()
                        .find(request.planId()).value().orElseThrow());
    }

    private static void assertUnstarted(Setup setup) {
        assertTrue(setup.persistence().events()
                .readAfter(setup.plan().id(), 0).value().orElseThrow().isEmpty());
        assertEquals(
                setup.source(),
                setup.persistence().checkpoints()
                        .find(setup.plan().id()).value().orElseThrow());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private static void shutdown(ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private record Setup(
            PersistenceFixtures.MutableCountingClock clock,
            InMemoryPersistence persistence,
            Plan plan,
            VersionedCheckpoint source,
            ExecutionStartRequest request) {
    }
}
