package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.OWNER;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.TOKEN;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.attempt;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.bootstrap;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.revisedReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRecoveryConcurrencyTest {
    @Test
    void sameTokenAndSameEventConvergeOnExactPersistedFact()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "same-token-same-event");
        FreshExecutionStartAttempt shared =
                attempt("same-token-same-event", OWNER, TOKEN);
        var barrierMaterializer = new FirstPreflightBarrierMaterializer();
        var leases = new AcquireOnlyLeaseRepository(persistence.leases());
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                barrierMaterializer,
                leases,
                persistence.executionStarts());

        List<ExecutionStartRecoveryOutcome> outcomes = runTogether(
                () -> recoverer.recover(request(bootstrap, shared)),
                () -> recoverer.recover(request(bootstrap, shared)));

        var first = assertInstanceOf(
                RecoveredExecutionStart.class, outcomes.get(0));
        var second = assertInstanceOf(
                RecoveredExecutionStart.class, outcomes.get(1));
        assertEquals(first.persistedStart(), second.persistedStart());
        assertEquals(
                shared.eventDraft().id(),
                first.persistedStart().startEvent().id());
        assertEquals(
                1,
                persistence.events().readAfter(
                        bootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
        assertEquals(0, leases.releaseCalls.get());
        assertEquals(0, leases.findCalls.get());
        assertEquals(0, leases.renewCalls.get());
        assertEquals(2, leases.acquireCalls.get());
    }

    @Test
    void sameTokenDifferentEventsConvergeOnOneWinnerWithoutRelease()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "shared-token");
        FreshExecutionStartAttempt first =
                attempt("shared-a", OWNER, TOKEN);
        FreshExecutionStartAttempt second =
                attempt("shared-b", OWNER, TOKEN);
        var barrierMaterializer = new FirstPreflightBarrierMaterializer();
        var leases = new AcquireOnlyLeaseRepository(persistence.leases());
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                barrierMaterializer,
                leases,
                persistence.executionStarts());

        List<ExecutionStartRecoveryOutcome> outcomes = runTogether(
                () -> recoverer.recover(request(bootstrap, first)),
                () -> recoverer.recover(request(bootstrap, second)));

        assertInstanceOf(RecoveredExecutionStart.class, outcomes.get(0));
        assertInstanceOf(RecoveredExecutionStart.class, outcomes.get(1));
        Set<?> candidateIds = Set.of(
                first.eventDraft().id(),
                second.eventDraft().id());
        var persisted = ((RecoveredExecutionStart) outcomes.get(0))
                .persistedStart();
        assertTrue(candidateIds.contains(persisted.startEvent().id()));
        assertEquals(
                persisted,
                ((RecoveredExecutionStart) outcomes.get(1))
                        .persistedStart());
        assertEquals(0, leases.releaseCalls.get());
        assertEquals(0, leases.findCalls.get());
        assertEquals(0, leases.renewCalls.get());
        assertTrue(leases.acquireCalls.get() >= 1);
        assertTrue(leases.acquireCalls.get() <= 2);
    }

    @Test
    void competitorCommitBetweenAcquireAndSecondInspectIsObserved()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "competitor-after-acquire");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(
                        bootstrap, bootstrap.plan());
        FreshExecutionStartAttempt recoveryAttempt =
                attempt("competitor-after-acquire");
        var competingLeaseRepository =
                new CommitDuringAcquireLeaseRepository(
                        persistence.leases(),
                        persistence.executionStarts(),
                        ready,
                        recoveryAttempt);
        var recoveryStarts =
                new RecordingStartRepository(persistence.executionStarts());
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                competingLeaseRepository,
                recoveryStarts);

        var outcome = assertInstanceOf(
                RecoveredExecutionStart.class,
                recoverer.recover(request(bootstrap, recoveryAttempt)));

        assertEquals(
                ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                outcome.resolution());
        assertEquals(
                competingLeaseRepository.persisted.get(),
                outcome.persistedStart());
        assertEquals(0, recoveryStarts.calls.get());
        assertEquals(1, competingLeaseRepository.acquireCalls.get());
        assertEquals(0, competingLeaseRepository.releaseCalls.get());
        assertEquals(
                1,
                persistence.events().readAfter(
                        bootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void competitorCommitBetweenSecondInspectAndStartReplaysExactProposal()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "competitor-before-start");
        FreshExecutionStartAttempt recoveryAttempt =
                attempt("competitor-before-start");
        LeaseRecord lease = persistence.leases().acquire(
                        bootstrap.plan().id(),
                        recoveryAttempt.leaseOwnerId(),
                        recoveryAttempt.leaseToken(),
                        recoveryAttempt.leaseExpiresAt())
                .value().orElseThrow();
        var materializer = new CommitDuringSecondMaterialization(
                persistence.executionStarts(), lease);
        var recoveryStarts =
                new RecordingStartRepository(persistence.executionStarts());
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                materializer,
                persistence.leases(),
                recoveryStarts);

        var outcome = assertInstanceOf(
                RecoveredExecutionStart.class,
                recoverer.recover(request(bootstrap, recoveryAttempt)));

        assertEquals(
                ExecutionStartRecoveryResolution.ATOMIC_START_REPLAYED,
                outcome.resolution());
        assertEquals(materializer.persisted.get(), outcome.persistedStart());
        assertEquals(1, recoveryStarts.calls.get());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                recoveryStarts.result.get().outcome());
        assertEquals(
                materializer.request.get(),
                recoveryStarts.request.get());
        assertEquals(
                1,
                persistence.events().readAfter(
                        bootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void successorActivationAfterOwnStartBeforeThirdInspectIsAdvanced()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "successor-after-start");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(
                        bootstrap, bootstrap.plan());
        FreshExecutionStartAttempt recoveryAttempt =
                attempt("successor-after-start");
        var appendingStarts = new ActivateStepAfterStartRepository(
                persistence.executionStarts(),
                persistence);
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                persistence.leases(),
                appendingStarts);

        var outcome = assertInstanceOf(
                ExecutionStartRecoveryAdvancedUnsupported.class,
                recoverer.recover(request(bootstrap, recoveryAttempt)));

        assertEquals(
                ExecutionStartRecoveryStage.POST_START_INSPECT,
                outcome.stage());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                outcome.leaseDisposition());
        assertEquals(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                outcome.failure().code());
        assertEquals(1, appendingStarts.calls.get());
        assertEquals(PersistenceOutcome.APPLIED,
                appendingStarts.result.get().outcome());
        assertEquals(
                2,
                persistence.events().readAfter(
                        bootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void revisionAppendedAfterSecondMaterializationRejectsStaleStart()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "revision-after-p2");
        PersistedExecutionStartReady ready =
                new PersistedExecutionStartReady(
                        bootstrap, bootstrap.plan());
        FreshExecutionStartAttempt recoveryAttempt =
                attempt("revision-after-p2");
        var materializer = new AppendRevisionDuringSecondMaterialization(
                persistence,
                revisedReady(ready, "revision-after-p2"));
        var recoveryStarts =
                new RecordingStartRepository(persistence.executionStarts());
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                materializer,
                persistence.leases(),
                recoveryStarts);

        var outcome = assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                recoverer.recover(request(bootstrap, recoveryAttempt)));

        assertEquals(ExecutionStartRecoveryStage.ATOMIC_START,
                outcome.stage());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                outcome.leaseDisposition());
        assertEquals(
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                outcome.failure().code());
        assertEquals(
                "request.startedCheckpoint",
                outcome.failure().path());
        assertEquals(1, recoveryStarts.calls.get());
        assertEquals(
                PersistenceOutcome.REJECTED,
                recoveryStarts.result.get().outcome());
        assertEquals(
                0,
                persistence.events().readAfter(
                        bootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
        assertEquals(
                2,
                persistence.plans().find(bootstrap.plan().id())
                        .value().orElseThrow().latestRevision().number());
    }

    @Test
    void takeoverAfterSecondInspectRejectsOldFenceWithoutEvent()
            throws Exception {
        MutableClock clock = new MutableClock(T0.plusSeconds(10));
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "takeover-after-i2");
        FreshExecutionStartAttempt recoveryAttempt =
                new FreshExecutionStartAttempt(
                        "owner-fence-one",
                        "token-fence-one",
                        T0.plusSeconds(20),
                        attempt("takeover-after-i2").eventDraft(),
                        T0.plusSeconds(4));
        FreshExecutionStartAttempt takeoverAttempt =
                new FreshExecutionStartAttempt(
                        "owner-fence-two",
                        "token-fence-two",
                        T0.plusSeconds(60),
                        attempt("takeover-fence-two").eventDraft(),
                        T0.plusSeconds(22));
        var materializer = new TakeoverDuringSecondMaterialization(
                persistence.leases(), clock, takeoverAttempt);
        var recoveryStarts =
                new RecordingStartRepository(persistence.executionStarts());
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                materializer,
                persistence.leases(),
                recoveryStarts);

        var outcome = assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                recoverer.recover(request(bootstrap, recoveryAttempt)));

        assertEquals(ExecutionStartRecoveryStage.ATOMIC_START,
                outcome.stage());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                outcome.leaseDisposition());
        assertEquals(
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                outcome.failure().code());
        assertEquals(1, recoveryStarts.calls.get());
        assertEquals(
                PersistenceOutcome.REJECTED,
                recoveryStarts.result.get().outcome());
        LeaseRecord takeoverLease = materializer.takeoverLease.get();
        assertEquals(2, takeoverLease.fencingToken());
        assertEquals(
                takeoverAttempt.leaseOwnerId(), takeoverLease.ownerId());
        assertEquals(
                takeoverAttempt.leaseToken(), takeoverLease.leaseToken());
        assertEquals(
                0,
                persistence.events().readAfter(
                        bootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void concurrentIndependentPlansDoNotShareLeaseEventOrCheckpoint()
            throws Exception {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap firstBootstrap =
                persistBootstrap(persistence, "plan-a");
        PersistedPlanBootstrap secondBootstrap =
                persistBootstrap(persistence, "plan-b");
        FreshExecutionStartAttempt first =
                attempt("plan-a", "owner-a", "token-a");
        FreshExecutionStartAttempt second =
                attempt("plan-b", "owner-b", "token-b");
        var recoverer = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                persistence.leases(),
                persistence.executionStarts());

        List<ExecutionStartRecoveryOutcome> outcomes = runTogether(
                () -> recoverer.recover(request(firstBootstrap, first)),
                () -> recoverer.recover(request(secondBootstrap, second)));

        var firstResult = assertInstanceOf(
                RecoveredExecutionStart.class, outcomes.get(0));
        var secondResult = assertInstanceOf(
                RecoveredExecutionStart.class, outcomes.get(1));
        assertEquals(firstBootstrap.plan().id(), firstResult.planId());
        assertEquals(secondBootstrap.plan().id(), secondResult.planId());
        assertEquals(
                first.eventDraft().id(),
                firstResult.persistedStart().startEvent().id());
        assertEquals(
                second.eventDraft().id(),
                secondResult.persistedStart().startEvent().id());
        assertEquals(
                1,
                persistence.events().readAfter(
                        firstBootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
        assertEquals(
                1,
                persistence.events().readAfter(
                        secondBootstrap.plan().id(), 0)
                        .value().orElseThrow().size());
    }

    @Test
    void expiredIndeterminateLeaseIsTakenOverWithHigherFence()
            throws Exception {
        MutableClock clock = new MutableClock(T0.plusSeconds(10));
        InMemoryPersistence persistence = new InMemoryPersistence(clock);
        PersistedPlanBootstrap bootstrap =
                persistBootstrap(persistence, "takeover");
        FreshExecutionStartAttempt first = new FreshExecutionStartAttempt(
                "owner-expired",
                "token-expired",
                T0.plusSeconds(20),
                attempt("takeover-first").eventDraft(),
                T0.plusSeconds(4));
        var responseLoss = new CommitThenNullLeaseRepository(
                persistence.leases());
        var uncertain = new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                responseLoss,
                persistence.executionStarts());

        var failure = assertThrows(
                ExecutionStartRecoveryProtocolException.class,
                () -> uncertain.recover(request(bootstrap, first)));
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                failure.leaseDisposition());

        clock.set(T0.plusSeconds(21));
        FreshExecutionStartAttempt takeover = new FreshExecutionStartAttempt(
                "owner-takeover",
                "token-takeover",
                T0.plusSeconds(60),
                attempt("takeover-second").eventDraft(),
                T0.plusSeconds(22));
        var recovered = assertInstanceOf(
                RecoveredExecutionStart.class,
                new DefaultExecutionStartRecoverer(
                        persistence.executionStartRecovery(),
                        new DeterministicRecoveryReadyExecutionStartMaterializer(),
                        persistence.leases(),
                        persistence.executionStarts())
                        .recover(request(bootstrap, takeover)));

        assertEquals("owner-takeover",
                recovered.persistedStart().leaseOwnerId());
        assertEquals(2, recovered.persistedStart().fencingToken());
        assertEquals(
                takeover.eventDraft().id(),
                recovered.persistedStart().startEvent().id());
        assertEquals(0, responseLoss.releaseCalls.get());
    }

    private static InMemoryPersistence persistence() {
        return new InMemoryPersistence(Clock.fixed(
                T0.plusSeconds(10), ZoneOffset.UTC));
    }

    private static PersistedPlanBootstrap persistBootstrap(
            InMemoryPersistence persistence,
            String suffix) {
        PersistedPlanBootstrap input = bootstrap(suffix);
        return persistence.planBootstraps().bootstrap(
                input.taskFrame(),
                input.plan(),
                input.initialCheckpoint().checkpoint())
                .value().orElseThrow();
    }

    private static ExecutionStartRecoveryRequest request(
            PersistedPlanBootstrap bootstrap,
            FreshExecutionStartAttempt attempt) {
        return new ExecutionStartRecoveryRequest(
                bootstrap.plan().id(),
                Optional.of(attempt));
    }

    private static ExecutionStartRequest startRequest(
            LeaseRecord lease,
            MaterializedExecutionStart materialized) {
        return new ExecutionStartRequest(
                lease.planId(),
                lease.leaseToken(),
                lease.fencingToken(),
                materialized.startEvent(),
                materialized.startedCheckpoint());
    }

    private static void appendRevision(
            InMemoryPersistence persistence,
            PersistedExecutionStartReady revised) {
        PersistenceResult<?> result = persistence.plans().appendRevision(
                revised.planId(),
                revised.currentPlan().latestRevision().number() - 1,
                revised.currentPlan().latestRevision());
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError(
                    "revision append failed: " + result.outcome());
        }
    }

    @SafeVarargs
    private static List<ExecutionStartRecoveryOutcome> runTogether(
            java.util.concurrent.Callable<ExecutionStartRecoveryOutcome>
                    ... actions)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(
                actions.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            @SuppressWarnings("unchecked")
            Future<ExecutionStartRecoveryOutcome>[] futures =
                    new Future[actions.length];
            for (int index = 0; index < actions.length; index++) {
                var action = actions[index];
                futures[index] = executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("start barrier timed out");
                    }
                    return action.call();
                });
            }
            start.countDown();
            var results =
                    new java.util.ArrayList<ExecutionStartRecoveryOutcome>();
            for (Future<ExecutionStartRecoveryOutcome> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class FirstPreflightBarrierMaterializer
            implements RecoveryReadyExecutionStartMaterializer {
        private final RecoveryReadyExecutionStartMaterializer delegate =
                new DeterministicRecoveryReadyExecutionStartMaterializer();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstTwo = new CountDownLatch(2);

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest request) {
            int call = calls.incrementAndGet();
            if (call <= 2) {
                firstTwo.countDown();
                try {
                    if (!firstTwo.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "preflight barrier timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return delegate.materialize(request);
        }
    }

    private static final class AcquireOnlyLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final AtomicInteger acquireCalls = new AtomicInteger();
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();
        private final AtomicInteger findCalls = new AtomicInteger();

        private AcquireOnlyLeaseRepository(LeaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            acquireCalls.incrementAndGet();
            return delegate.acquire(planId, ownerId, leaseToken, expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            renewCalls.incrementAndGet();
            throw new AssertionError("renew must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId planId,
                String leaseToken) {
            releaseCalls.incrementAndGet();
            throw new AssertionError("release must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            findCalls.incrementAndGet();
            throw new AssertionError("find must not be called");
        }
    }

    private static final class CommitThenNullLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final AtomicInteger releaseCalls = new AtomicInteger();

        private CommitThenNullLeaseRepository(LeaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            delegate.acquire(planId, ownerId, leaseToken, expiresAt);
            return null;
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            throw new AssertionError("renew must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId planId,
                String leaseToken) {
            releaseCalls.incrementAndGet();
            throw new AssertionError("release must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            throw new AssertionError("find must not be called");
        }
    }

    private static final class RecordingStartRepository
            implements ExecutionStartRepository {
        private final ExecutionStartRepository delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ExecutionStartRequest> request =
                new AtomicReference<>();
        private final AtomicReference<
                PersistenceResult<PersistedExecutionStart>> result =
                new AtomicReference<>();

        private RecordingStartRepository(
                ExecutionStartRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest value) {
            calls.incrementAndGet();
            request.set(value);
            PersistenceResult<PersistedExecutionStart> observed =
                    delegate.start(value);
            result.set(observed);
            return observed;
        }
    }

    private static final class CommitDuringAcquireLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final ExecutionStartRepository competitorStarts;
        private final PersistedExecutionStartReady ready;
        private final FreshExecutionStartAttempt attempt;
        private final AtomicInteger acquireCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();
        private final AtomicReference<PersistedExecutionStart> persisted =
                new AtomicReference<>();

        private CommitDuringAcquireLeaseRepository(
                LeaseRepository delegate,
                ExecutionStartRepository competitorStarts,
                PersistedExecutionStartReady ready,
                FreshExecutionStartAttempt attempt) {
            this.delegate = delegate;
            this.competitorStarts = competitorStarts;
            this.ready = ready;
            this.attempt = attempt;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            acquireCalls.incrementAndGet();
            PersistenceResult<LeaseRecord> acquired =
                    delegate.acquire(
                            planId, ownerId, leaseToken, expiresAt);
            LeaseRecord lease = acquired.value().orElseThrow();
            MaterializedExecutionStart materialized =
                    ExecutionStartRecoveryTestFixtures.materialized(
                            ready, attempt);
            PersistenceResult<PersistedExecutionStart> committed =
                    competitorStarts.start(startRequest(
                            lease, materialized));
            if (committed.outcome() != PersistenceOutcome.APPLIED) {
                throw new AssertionError(
                        "competitor start failed: "
                                + committed.outcome());
            }
            persisted.set(committed.value().orElseThrow());
            return acquired;
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            throw new AssertionError("renew must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId planId,
                String leaseToken) {
            releaseCalls.incrementAndGet();
            throw new AssertionError("release must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            throw new AssertionError("find must not be called");
        }
    }

    private static final class CommitDuringSecondMaterialization
            implements RecoveryReadyExecutionStartMaterializer {
        private final RecoveryReadyExecutionStartMaterializer delegate =
                new DeterministicRecoveryReadyExecutionStartMaterializer();
        private final ExecutionStartRepository competitorStarts;
        private final LeaseRecord lease;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ExecutionStartRequest> request =
                new AtomicReference<>();
        private final AtomicReference<PersistedExecutionStart> persisted =
                new AtomicReference<>();

        private CommitDuringSecondMaterialization(
                ExecutionStartRepository competitorStarts,
                LeaseRecord lease) {
            this.competitorStarts = competitorStarts;
            this.lease = lease;
        }

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest value) {
            MaterializedExecutionStart materialized =
                    delegate.materialize(value);
            if (calls.incrementAndGet() == 2) {
                ExecutionStartRequest competing =
                        startRequest(lease, materialized);
                PersistenceResult<PersistedExecutionStart> committed =
                        competitorStarts.start(competing);
                if (committed.outcome() != PersistenceOutcome.APPLIED) {
                    throw new AssertionError(
                            "competitor start failed: "
                                    + committed.outcome());
                }
                request.set(competing);
                persisted.set(committed.value().orElseThrow());
            }
            return materialized;
        }
    }

    private static final class ActivateStepAfterStartRepository
            implements ExecutionStartRepository {
        private final ExecutionStartRepository delegate;
        private final InMemoryPersistence persistence;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<
                PersistenceResult<PersistedExecutionStart>> result =
                new AtomicReference<>();

        private ActivateStepAfterStartRepository(
                ExecutionStartRepository delegate,
                InMemoryPersistence persistence) {
            this.delegate = delegate;
            this.persistence = persistence;
        }

        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest request) {
            calls.incrementAndGet();
            PersistenceResult<PersistedExecutionStart> observed =
                    delegate.start(request);
            result.set(observed);
            PersistedExecutionStart start = observed.value().orElseThrow();
            Checkpoint source = start.startedCheckpoint().checkpoint();
            PlanStepId target = persistence.plans()
                    .find(request.planId())
                    .value().orElseThrow()
                    .latestRevision()
                    .steps().stream()
                    .filter(step -> step.dependencies().isEmpty())
                    .findFirst().orElseThrow()
                    .id();
            EventEnvelope event = new EventEnvelope(
                    new EventId("activation-after-start"),
                    source.taskFrameId(),
                    source.planId(),
                    3,
                    source.createdAt().plusSeconds(1),
                    request.startEvent().type(),
                    Optional.of(request.startEvent().id()),
                    request.startEvent().correlationId(),
                    request.startEvent().payload());
            var states = new LinkedHashMap<>(source.stepStates());
            states.put(target, StepExecutionState.ACTIVE);
            Checkpoint activated = new Checkpoint(
                    source.taskFrameId(),
                    source.planId(),
                    source.revisionId(),
                    source.revisionNumber(),
                    event.sequence(),
                    source.planState(),
                    states,
                    source.receiptReferences(),
                    source.createdAt().plusSeconds(1));
            PersistenceResult<?> activation =
                    persistence.stepActivations().activate(
                            new StepActivationRequest(
                                    request.planId(),
                                    request.leaseToken(),
                                    start.fencingToken(),
                                    source.revisionId(),
                                    source.revisionNumber(),
                                    start.startedCheckpoint().version(),
                                    request.startEvent().sequence(),
                                    target,
                                    event,
                                    activated));
            if (activation.outcome() != PersistenceOutcome.APPLIED) {
                throw new AssertionError(
                        "activation failed: " + activation.outcome());
            }
            return observed;
        }
    }

    private static final class AppendRevisionDuringSecondMaterialization
            implements RecoveryReadyExecutionStartMaterializer {
        private final RecoveryReadyExecutionStartMaterializer delegate =
                new DeterministicRecoveryReadyExecutionStartMaterializer();
        private final InMemoryPersistence persistence;
        private final PersistedExecutionStartReady revised;
        private final AtomicInteger calls = new AtomicInteger();

        private AppendRevisionDuringSecondMaterialization(
                InMemoryPersistence persistence,
                PersistedExecutionStartReady revised) {
            this.persistence = persistence;
            this.revised = revised;
        }

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest request) {
            MaterializedExecutionStart materialized =
                    delegate.materialize(request);
            if (calls.incrementAndGet() == 2) {
                appendRevision(persistence, revised);
            }
            return materialized;
        }
    }

    private static final class TakeoverDuringSecondMaterialization
            implements RecoveryReadyExecutionStartMaterializer {
        private final RecoveryReadyExecutionStartMaterializer delegate =
                new DeterministicRecoveryReadyExecutionStartMaterializer();
        private final LeaseRepository leases;
        private final MutableClock clock;
        private final FreshExecutionStartAttempt takeoverAttempt;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<LeaseRecord> takeoverLease =
                new AtomicReference<>();

        private TakeoverDuringSecondMaterialization(
                LeaseRepository leases,
                MutableClock clock,
                FreshExecutionStartAttempt takeoverAttempt) {
            this.leases = leases;
            this.clock = clock;
            this.takeoverAttempt = takeoverAttempt;
        }

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest request) {
            MaterializedExecutionStart materialized =
                    delegate.materialize(request);
            if (calls.incrementAndGet() == 2) {
                clock.set(T0.plusSeconds(21));
                PersistenceResult<LeaseRecord> result = leases.acquire(
                        request.ready().planId(),
                        takeoverAttempt.leaseOwnerId(),
                        takeoverAttempt.leaseToken(),
                        takeoverAttempt.leaseExpiresAt());
                if (result.outcome() != PersistenceOutcome.APPLIED) {
                    throw new AssertionError(
                            "takeover failed: " + result.outcome());
                }
                takeoverLease.set(result.value().orElseThrow());
            }
            return materialized;
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException(
                        "only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
