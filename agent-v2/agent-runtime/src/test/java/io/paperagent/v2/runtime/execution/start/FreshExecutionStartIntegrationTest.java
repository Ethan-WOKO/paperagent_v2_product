package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.DeterministicFreshExecutionGate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.runtime.execution.start.FreshExecutionStartTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.start.FreshExecutionStartTestFixtures.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshExecutionStartIntegrationTest {

    @Test
    void realFreshStartAndCachedAppliedRetryAreAtomicAndReplayable() {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(persistence, "real-replay");
        FreshExecutionStartAttempt attempt =
                FreshExecutionStartTestFixtures.attempt("real-replay");
        DefaultFreshExecutionStarter starter = starter(
                persistence,
                persistence.executionStarts());
        FreshExecutionStartRequest request =
                FreshExecutionStartTestFixtures.request(
                        scenario.applied(),
                        attempt);

        FreshExecutionStarted first =
                (FreshExecutionStarted) starter.start(request);
        FreshExecutionStarted replay =
                (FreshExecutionStarted) starter.start(request);

        assertEquals(PersistenceOutcome.APPLIED, first.startOutcome());
        assertEquals(PersistenceOutcome.REPLAYED, replay.startOutcome());
        assertEquals(first.persistedStart(), replay.persistedStart());
        assertEquals(1, first.persistedStart().startEvent().sequence());
        assertEquals(
                2,
                first.persistedStart().startedCheckpoint().version());
        assertEquals(
                first.persistedStart().startEvent(),
                persistence.events()
                        .readAfter(scenario.bootstrap().plan().id(), 0)
                        .value().orElseThrow().get(0));
        assertEquals(
                first.persistedStart().startedCheckpoint(),
                persistence.checkpoints()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow());
        assertEquals(
                first.persistedStart().fencingToken(),
                persistence.leases()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow().fencingToken());
    }

    @Test
    void bootstrapReplayHandsOffWithoutFreshWrites() {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(persistence, "bootstrap-replay");
        PersistenceResult<PersistedPlanBootstrap> replay =
                persistence.planBootstraps().bootstrap(
                        scenario.bootstrap().taskFrame(),
                        scenario.bootstrap().plan(),
                        scenario.bootstrap().initialCheckpoint().checkpoint());
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());

        FreshExecutionStartOutcome outcome = starter(
                persistence,
                persistence.executionStarts()).start(
                        new FreshExecutionStartRequest(
                                replay,
                                Optional.of(
                                        FreshExecutionStartTestFixtures
                                                .attempt(
                                                        "ignored-valid"))));

        assertEquals(
                new FreshExecutionRecoveryRequired(
                        scenario.bootstrap().plan().id()),
                outcome);
        assertTrue(persistence.events()
                .readAfter(scenario.bootstrap().plan().id(), 0)
                .value().orElseThrow().isEmpty());
        assertEquals(
                1,
                persistence.checkpoints()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow().version());
    }

    @Test
    void committedResponseLossThenBootstrapReplayHandsOffToRecovery() {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(persistence, "response-loss");
        AtomicBoolean first = new AtomicBoolean(true);
        IllegalStateException sentinel =
                new IllegalStateException("response lost after commit");
        ExecutionStartRepository responseLosing = request -> {
            var committed = persistence.executionStarts().start(request);
            if (first.getAndSet(false)) {
                throw sentinel;
            }
            return committed;
        };
        DefaultFreshExecutionStarter starter =
                starter(persistence, responseLosing);

        FreshExecutionStartProtocolException failure = assertThrows(
                FreshExecutionStartProtocolException.class,
                () -> starter.start(FreshExecutionStartTestFixtures.request(
                        scenario.applied(),
                        FreshExecutionStartTestFixtures.attempt(
                                "response-loss"))));
        assertNotSame(sentinel, failure.getCause());
        assertEquals(
                "collaborator exception details redacted [type="
                        + sentinel.getClass().getName()
                        + "]",
                failure.getCause().getMessage());
        assertNull(failure.getCause().getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertEquals(
                FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                failure.leaseDisposition());
        assertEquals(
                1,
                persistence.events()
                        .readAfter(scenario.bootstrap().plan().id(), 0)
                        .value().orElseThrow().size());

        PersistenceResult<PersistedPlanBootstrap> bootstrapReplay =
                persistence.planBootstraps().bootstrap(
                        scenario.bootstrap().taskFrame(),
                        scenario.bootstrap().plan(),
                        scenario.bootstrap().initialCheckpoint().checkpoint());
        FreshExecutionStartOutcome recovery =
                starter(persistence, persistence.executionStarts()).start(
                        new FreshExecutionStartRequest(
                                bootstrapReplay,
                                Optional.empty()));
        assertEquals(
                new FreshExecutionRecoveryRequired(
                        scenario.bootstrap().plan().id()),
                recovery);
        assertEquals(
                1,
                persistence.events()
                        .readAfter(scenario.bootstrap().plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void identicalConcurrentCallersHaveOneApplyAndBoundedReplays()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(persistence, "concurrent-same");
        FreshExecutionStartRequest request =
                FreshExecutionStartTestFixtures.request(
                        scenario.applied(),
                        FreshExecutionStartTestFixtures.attempt(
                                "concurrent-same"));
        BarrierExecutionStartRepository barrierStarts =
                new BarrierExecutionStartRepository(
                        persistence.executionStarts(),
                        8);
        DefaultFreshExecutionStarter starter =
                starter(persistence, barrierStarts);

        List<FreshExecutionStartOutcome> outcomes = concurrently(
                8,
                () -> starter.start(request));

        long applied = outcomes.stream()
                .map(FreshExecutionStarted.class::cast)
                .filter(value ->
                        value.startOutcome() == PersistenceOutcome.APPLIED)
                .count();
        long replayed = outcomes.stream()
                .map(FreshExecutionStarted.class::cast)
                .filter(value ->
                        value.startOutcome() == PersistenceOutcome.REPLAYED)
                .count();
        assertEquals(1, applied);
        assertEquals(7, replayed);
        assertEquals(8, barrierStarts.calls());
        assertEquals(
                1,
                persistence.events()
                        .readAfter(scenario.bootstrap().plan().id(), 0)
                        .value().orElseThrow().size());
        assertEquals(
                2,
                persistence.checkpoints()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow().version());
    }

    @Test
    void sharedLeaseDifferentEventsKeepsWinnerLeaseForRecovery()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(persistence, "shared-lease");
        BarrierExecutionStartRepository barrierStarts =
                new BarrierExecutionStartRepository(
                        persistence.executionStarts(),
                        2);
        DefaultFreshExecutionStarter starter =
                starter(persistence, barrierStarts);
        FreshExecutionStartRequest first =
                FreshExecutionStartTestFixtures.request(
                        scenario.applied(),
                        FreshExecutionStartTestFixtures.attempt(
                                "shared-event-a"));
        FreshExecutionStartRequest second =
                FreshExecutionStartTestFixtures.request(
                        scenario.applied(),
                        FreshExecutionStartTestFixtures.attempt(
                                "shared-event-b"));

        List<FreshExecutionStartOutcome> outcomes = concurrently(
                List.of(
                        () -> starter.start(first),
                        () -> starter.start(second)));

        assertEquals(
                1,
                outcomes.stream()
                        .filter(FreshExecutionStarted.class::isInstance)
                        .count());
        assertEquals(2, barrierStarts.calls());
        assertEquals(
                1,
                outcomes.stream()
                        .filter(FreshAtomicExecutionStartRejected.class
                                ::isInstance)
                        .count());
        FreshAtomicExecutionStartRejected loser = outcomes.stream()
                .filter(FreshAtomicExecutionStartRejected.class::isInstance)
                .map(FreshAtomicExecutionStartRejected.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(
                FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                loser.leaseDisposition());
        assertEquals(
                TOKEN,
                persistence.leases()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow().leaseToken());
        assertEquals(
                1,
                persistence.events()
                        .readAfter(scenario.bootstrap().plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void materializeThenRevisionAppendBeforeAtomicStartRejectsWithoutPartialState() {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(persistence, "revision-race");
        AtomicBoolean appended = new AtomicBoolean();
        ExecutionStartRepository racingStart = request -> {
            if (appended.compareAndSet(false, true)) {
                assertEquals(
                        PersistenceOutcome.APPLIED,
                        persistence.plans()
                                .appendRevision(
                                        scenario.bootstrap().plan().id(),
                                        1,
                                        nextRevision(
                                                scenario.bootstrap().plan(),
                                                "before-start"))
                                .outcome());
            }
            return persistence.executionStarts().start(request);
        };

        FreshExecutionStartOutcome outcome = starter(
                persistence,
                racingStart).start(
                        FreshExecutionStartTestFixtures.request(
                                scenario.applied(),
                                FreshExecutionStartTestFixtures.attempt(
                                        "revision-race")));

        assertTrue(outcome instanceof FreshAtomicExecutionStartRejected);
        assertEquals(
                FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                ((FreshAtomicExecutionStartRejected) outcome)
                        .leaseDisposition());
        assertTrue(persistence.events()
                .readAfter(scenario.bootstrap().plan().id(), 0)
                .value().orElseThrow().isEmpty());
        assertEquals(
                1,
                persistence.checkpoints()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow().version());
    }

    @Test
    void atomicStartLinearizesBeforeRevisionAppendReturnsPersistedFact() {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario scenario = seed(
                persistence,
                "start-before-revision");
        AtomicInteger startCalls = new AtomicInteger();
        ExecutionStartRepository startThenAppend = request -> {
            if (startCalls.incrementAndGet() != 1) {
                throw new AssertionError("atomic start must not be retried");
            }
            var persisted =
                    persistence.executionStarts().start(request);
            assertEquals(PersistenceOutcome.APPLIED, persisted.outcome());
            PersistenceResult<Plan> guarded =
                    persistence.plans().appendRevision(
                            scenario.bootstrap().plan().id(),
                            1,
                            nextRevision(
                                    scenario.bootstrap().plan(),
                                    "after-start"));
            assertEquals(PersistenceOutcome.REJECTED, guarded.outcome());
            assertEquals(
                    PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                    guarded.failure().orElseThrow().code());
            assertEquals("planId", guarded.failure().orElseThrow().path());
            return persisted;
        };

        FreshExecutionStartOutcome outcome = starter(
                persistence,
                startThenAppend).start(
                        FreshExecutionStartTestFixtures.request(
                                scenario.applied(),
                                FreshExecutionStartTestFixtures.attempt(
                                        "start-before-revision")));

        FreshExecutionStarted started =
                (FreshExecutionStarted) outcome;
        assertEquals(PersistenceOutcome.APPLIED, started.startOutcome());
        assertEquals(1, startCalls.get());
        assertEquals(
                started.persistedStart().startEvent(),
                persistence.events()
                        .readAfter(scenario.bootstrap().plan().id(), 0)
                        .value().orElseThrow().get(0));
        assertEquals(
                started.persistedStart().startedCheckpoint(),
                persistence.checkpoints()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow());
        assertEquals(
                1,
                persistence.plans()
                        .find(scenario.bootstrap().plan().id())
                        .value().orElseThrow()
                        .revisions().size());
    }

    @Test
    void twoPlansStartIndependently() {
        InMemoryPersistence persistence = persistence();
        BootstrapScenario first = seed(persistence, "independent-a");
        BootstrapScenario second = seed(persistence, "independent-b");
        DefaultFreshExecutionStarter starter =
                starter(persistence, persistence.executionStarts());

        FreshExecutionStarted firstResult =
                (FreshExecutionStarted) starter.start(
                        FreshExecutionStartTestFixtures.request(
                                first.applied(),
                                FreshExecutionStartTestFixtures.attempt(
                                        "independent-a")));
        FreshExecutionStarted secondResult =
                (FreshExecutionStarted) starter.start(
                        FreshExecutionStartTestFixtures.request(
                                second.applied(),
                                independentAttempt("independent-b")));

        assertEquals(PersistenceOutcome.APPLIED, firstResult.startOutcome());
        assertEquals(PersistenceOutcome.APPLIED, secondResult.startOutcome());
        assertTrue(!firstResult.persistedStart().planId().equals(
                secondResult.persistedStart().planId()));
        assertEquals(
                1,
                persistence.events().readAfter(
                        first.bootstrap().plan().id(), 0)
                        .value().orElseThrow().size());
        assertEquals(
                1,
                persistence.events().readAfter(
                        second.bootstrap().plan().id(), 0)
                        .value().orElseThrow().size());
    }

    private static InMemoryPersistence persistence() {
        return new InMemoryPersistence(
                Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static FreshExecutionStartAttempt independentAttempt(
            String suffix) {
        FreshExecutionStartAttempt base =
                FreshExecutionStartTestFixtures.attempt(suffix);
        return new FreshExecutionStartAttempt(
                base.leaseOwnerId(),
                base.leaseToken() + "-" + suffix,
                base.leaseExpiresAt(),
                base.eventDraft(),
                base.checkpointCreatedAt());
    }

    private static BootstrapScenario seed(
            InMemoryPersistence persistence,
            String suffix) {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap(suffix);
        PersistenceResult<PersistedPlanBootstrap> applied =
                persistence.planBootstraps().bootstrap(
                        bootstrap.taskFrame(),
                        bootstrap.plan(),
                        bootstrap.initialCheckpoint().checkpoint());
        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        return new BootstrapScenario(bootstrap, applied);
    }

    private static DefaultFreshExecutionStarter starter(
            InMemoryPersistence persistence,
            ExecutionStartRepository starts) {
        return new DefaultFreshExecutionStarter(
                new DeterministicFreshExecutionGate(),
                new DeterministicExecutionStartMaterializer(),
                persistence.leases(),
                starts);
    }

    private static PlanRevision nextRevision(
            Plan plan,
            String suffix) {
        PlanRevision latest = plan.latestRevision();
        return new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                plan.taskFrameId(),
                latest.number() + 1,
                Optional.of(latest.id()),
                "deterministic TOCTOU " + suffix,
                T0.plusSeconds(5),
                latest.steps(),
                Map.of());
    }

    private static <T> List<T> concurrently(
            int count,
            Callable<T> task) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tasks.add(task);
        }
        return concurrently(tasks);
    }

    private static <T> List<T> concurrently(
            List<Callable<T>> tasks) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = executor.invokeAll(
                    tasks,
                    5,
                    TimeUnit.SECONDS);
            List<T> values = new ArrayList<>();
            for (Future<T> future : futures) {
                assertTrue(!future.isCancelled());
                values.add(future.get(5, TimeUnit.SECONDS));
            }
            return List.copyOf(values);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private record BootstrapScenario(
            PersistedPlanBootstrap bootstrap,
            PersistenceResult<PersistedPlanBootstrap> applied) {
    }

    private static final class BarrierExecutionStartRepository
            implements ExecutionStartRepository {
        private final ExecutionStartRepository delegate;
        private final CyclicBarrier barrier;
        private final AtomicInteger calls = new AtomicInteger();

        private BarrierExecutionStartRepository(
                ExecutionStartRepository delegate,
                int parties) {
            this.delegate = delegate;
            barrier = new CyclicBarrier(parties);
        }

        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest request) {
            calls.incrementAndGet();
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "atomic-start barrier interrupted",
                        exception);
            } catch (BrokenBarrierException
                    | java.util.concurrent.TimeoutException exception) {
                throw new IllegalStateException(
                        "atomic-start barrier failed",
                        exception);
            }
            return delegate.start(request);
        }

        private int calls() {
            return calls.get();
        }
    }
}
