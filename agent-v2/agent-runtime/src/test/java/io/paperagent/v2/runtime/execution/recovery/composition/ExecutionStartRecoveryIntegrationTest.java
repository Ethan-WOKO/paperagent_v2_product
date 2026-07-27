package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.attempt;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.bootstrap;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStartRecoveryIntegrationTest {
    @Test
    void realReadyRecoveryCommitsAtomicallyAndRepeatedCallObservesFact() {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "real-success");
        FreshExecutionStartAttempt attempt = attempt("real-success");
        var recoverer = recoverer(persistence);

        var first = assertInstanceOf(
                RecoveredExecutionStart.class,
                recoverer.recover(request(bootstrap, attempt)));
        assertEquals(
                ExecutionStartRecoveryResolution.ATOMIC_START_APPLIED,
                first.resolution());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                first.leaseDisposition());

        var second = assertInstanceOf(
                RecoveredExecutionStart.class,
                recoverer.recover(new ExecutionStartRecoveryRequest(
                        bootstrap.plan().id(),
                        Optional.empty())));
        assertEquals(
                ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                second.resolution());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                second.leaseDisposition());
        assertEquals(first.persistedStart(), second.persistedStart());

        var event = persistence.events().readAfter(
                bootstrap.plan().id(), 0);
        assertEquals(PersistenceOutcome.FOUND, event.outcome());
        assertEquals(1, event.value().orElseThrow().size());
        assertEquals(
                2,
                persistence.checkpoints()
                        .find(bootstrap.plan().id())
                        .value().orElseThrow().version());
    }

    @Test
    void preStartRevisionUsesOnlyCurrentRevisionAndCurrentSteps() {
        InMemoryPersistence persistence = persistence();
        PersistedPlanBootstrap bootstrap = persistBootstrap(
                persistence, "revision");
        PlanRevision previous = bootstrap.plan().latestRevision();
        var first = step("step-r2-first", Set.of());
        var second = step("step-r2-second", Set.of(first.id()));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-two"),
                bootstrap.taskFrame().id(),
                2,
                Optional.of(previous.id()),
                "pre-start revision",
                T0.plusSeconds(3),
                List.of(first, second),
                Map.of());
        var leases = new RevisionAppendingLeaseRepository(
                persistence,
                revision);
        var materializer = new RecordingMaterializer();

        var recovered = assertInstanceOf(
                RecoveredExecutionStart.class,
                new DefaultExecutionStartRecoverer(
                        persistence.executionStartRecovery(),
                        materializer,
                        leases,
                        persistence.executionStarts())
                        .recover(request(bootstrap, attempt("revision"))));
        var checkpoint =
                recovered.persistedStart().startedCheckpoint().checkpoint();
        assertEquals(revision.id(), checkpoint.revisionId());
        assertEquals(2, checkpoint.revisionNumber());
        assertEquals(
                Set.of(first.id(), second.id()),
                checkpoint.stepStates().keySet());
        assertTrue(checkpoint.stepStates().keySet().stream()
                .noneMatch(previousStep -> previous.steps().stream()
                        .anyMatch(old -> old.id().equals(previousStep))));
        assertEquals(2, materializer.proposals.size());
        MaterializedExecutionStart p1 = materializer.proposals.get(0);
        MaterializedExecutionStart p2 = materializer.proposals.get(1);
        assertEquals(previous.id(),
                p1.startedCheckpoint().revisionId());
        assertEquals(revision.id(),
                p2.startedCheckpoint().revisionId());
        assertTrue(!p1.equals(p2));
        assertEquals(p2.startEvent(),
                recovered.persistedStart().startEvent());
        assertEquals(
                p2.startedCheckpoint(),
                recovered.persistedStart()
                        .startedCheckpoint().checkpoint());
        assertEquals(1, leases.calls.get());
    }

    @Test
    void startCommitThenThrowOrNullReconcilesFromThirdInspection() {
        for (LossMode mode : LossMode.values()) {
            InMemoryPersistence persistence = persistence();
            PersistedPlanBootstrap bootstrap = persistBootstrap(
                    persistence, "start-loss-" + mode);
            FreshExecutionStartAttempt attempt =
                    attempt("start-loss-" + mode);
            var wrapper = new StartResponseLossRepository(
                    persistence.executionStarts(), mode);
            var recoverer = new DefaultExecutionStartRecoverer(
                    persistence.executionStartRecovery(),
                    new DeterministicRecoveryReadyExecutionStartMaterializer(),
                    persistence.leases(),
                    wrapper);

            var recovered = assertInstanceOf(
                    RecoveredExecutionStart.class,
                    recoverer.recover(request(bootstrap, attempt)));
            assertEquals(
                    ExecutionStartRecoveryResolution
                            .RECONCILED_AFTER_RESPONSE_LOSS,
                    recovered.resolution());
            assertEquals(1, wrapper.calls.get());
            assertEquals(
                    recovered.persistedStart(),
                    persistence.executionStartRecovery()
                            .inspect(bootstrap.plan().id())
                            .value().map(snapshot ->
                                    ((io.paperagent.v2.persistence
                                            .PersistedExecutionStartCommitted)
                                            snapshot).executionStart())
                            .orElseThrow());
        }
    }

    @Test
    void acquireCommitThenThrowOrNullIsIndeterminateThenExactReplayConverges() {
        for (LossMode mode : LossMode.values()) {
            InMemoryPersistence persistence = persistence();
            PersistedPlanBootstrap bootstrap = persistBootstrap(
                    persistence, "acquire-loss-" + mode);
            FreshExecutionStartAttempt attempt =
                    attempt("acquire-loss-" + mode);
            var wrapper = new AcquireResponseLossRepository(
                    persistence.leases(), mode);
            var firstMaterializer = new CountingMaterializer();
            var firstStarts = new CountingStartRepository(
                    persistence.executionStarts());
            var firstRecoverer = new DefaultExecutionStartRecoverer(
                    persistence.executionStartRecovery(),
                    firstMaterializer,
                    wrapper,
                    firstStarts);

            var failure = assertThrows(
                    ExecutionStartRecoveryProtocolException.class,
                    () -> firstRecoverer.recover(
                            request(bootstrap, attempt)));
            assertEquals(
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    failure.stage());
            assertEquals(
                    mode == LossMode.THROW
                            ? ExecutionStartRecoveryProtocolCode
                                    .COLLABORATOR_EXCEPTION
                            : ExecutionStartRecoveryProtocolCode
                                    .NULL_COLLABORATOR_RESULT,
                    failure.code());
            assertEquals(
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    failure.leaseDisposition());
            assertEquals(1, wrapper.calls.get());
            assertEquals(1, firstMaterializer.calls.get());
            assertEquals(0, firstStarts.calls.get());
            assertEquals(0, wrapper.releaseCalls.get());
            assertEquals(0, persistence.events()
                    .readAfter(bootstrap.plan().id(), 0)
                    .value().orElseThrow().size());

            var observingLeases = new ObservingLeaseRepository(
                    persistence.leases());
            var recovered = assertInstanceOf(
                    RecoveredExecutionStart.class,
                    new DefaultExecutionStartRecoverer(
                            persistence.executionStartRecovery(),
                            new DeterministicRecoveryReadyExecutionStartMaterializer(),
                            observingLeases,
                            persistence.executionStarts())
                            .recover(request(bootstrap, attempt)));
            assertEquals(
                    ExecutionStartRecoveryResolution.ATOMIC_START_APPLIED,
                    recovered.resolution());
            assertEquals(1, observingLeases.calls.get());
            assertEquals(
                    PersistenceOutcome.REPLAYED,
                    observingLeases.result.outcome());
            LeaseRecord replayed =
                    observingLeases.result.value().orElseThrow();
            assertEquals(bootstrap.plan().id(), replayed.planId());
            assertEquals(attempt.leaseOwnerId(), replayed.ownerId());
            assertEquals(attempt.leaseToken(), replayed.leaseToken());
            assertEquals(attempt.leaseExpiresAt(), replayed.expiresAt());
            assertEquals(1, replayed.fencingToken());
            assertEquals(
                    attempt.eventDraft().id(),
                    recovered.persistedStart().startEvent().id());
        }
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

    private static DefaultExecutionStartRecoverer recoverer(
            InMemoryPersistence persistence) {
        return new DefaultExecutionStartRecoverer(
                persistence.executionStartRecovery(),
                new DeterministicRecoveryReadyExecutionStartMaterializer(),
                persistence.leases(),
                persistence.executionStarts());
    }

    private enum LossMode {
        THROW,
        NULL
    }

    private static final class StartResponseLossRepository
            implements ExecutionStartRepository {
        private final ExecutionStartRepository delegate;
        private final LossMode mode;
        private final AtomicInteger calls = new AtomicInteger();

        private StartResponseLossRepository(
                ExecutionStartRepository delegate,
                LossMode mode) {
            this.delegate = delegate;
            this.mode = mode;
        }

        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest request) {
            calls.incrementAndGet();
            delegate.start(request);
            if (mode == LossMode.THROW) {
                throw new IllegalStateException(
                        "SECRET-start-response-loss");
            }
            return null;
        }
    }

    private static final class AcquireResponseLossRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final LossMode mode;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();

        private AcquireResponseLossRepository(
                LeaseRepository delegate,
                LossMode mode) {
            this.delegate = delegate;
            this.mode = mode;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                io.paperagent.v2.contracts.PlanId planId,
                String ownerId,
                String leaseToken,
                java.time.Instant expiresAt) {
            calls.incrementAndGet();
            delegate.acquire(planId, ownerId, leaseToken, expiresAt);
            if (mode == LossMode.THROW) {
                throw new IllegalStateException(
                        "SECRET-acquire-response-loss");
            }
            return null;
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken,
                java.time.Instant expiresAt) {
            throw new AssertionError("renew must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken) {
            releaseCalls.incrementAndGet();
            throw new AssertionError("release must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(
                io.paperagent.v2.contracts.PlanId planId) {
            throw new AssertionError("find must not be called");
        }
    }

    private static final class ObservingLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private PersistenceResult<LeaseRecord> result;

        private ObservingLeaseRepository(LeaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                io.paperagent.v2.contracts.PlanId planId,
                String ownerId,
                String leaseToken,
                java.time.Instant expiresAt) {
            calls.incrementAndGet();
            result = delegate.acquire(
                    planId, ownerId, leaseToken, expiresAt);
            return result;
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken,
                java.time.Instant expiresAt) {
            throw new AssertionError("renew must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken) {
            throw new AssertionError("release must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(
                io.paperagent.v2.contracts.PlanId planId) {
            throw new AssertionError("find must not be called");
        }
    }

    private static final class CountingMaterializer
            implements RecoveryReadyExecutionStartMaterializer {
        private final RecoveryReadyExecutionStartMaterializer delegate =
                new DeterministicRecoveryReadyExecutionStartMaterializer();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest request) {
            calls.incrementAndGet();
            return delegate.materialize(request);
        }
    }

    private static final class CountingStartRepository
            implements ExecutionStartRepository {
        private final ExecutionStartRepository delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingStartRepository(
                ExecutionStartRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest request) {
            calls.incrementAndGet();
            return delegate.start(request);
        }
    }

    private static final class RevisionAppendingLeaseRepository
            implements LeaseRepository {
        private final InMemoryPersistence persistence;
        private final PlanRevision revision;
        private final AtomicInteger calls = new AtomicInteger();

        private RevisionAppendingLeaseRepository(
                InMemoryPersistence persistence,
                PlanRevision revision) {
            this.persistence = persistence;
            this.revision = revision;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                io.paperagent.v2.contracts.PlanId planId,
                String ownerId,
                String leaseToken,
                java.time.Instant expiresAt) {
            calls.incrementAndGet();
            persistence.plans().appendRevision(planId, 1, revision)
                    .value().orElseThrow();
            return persistence.leases().acquire(
                    planId, ownerId, leaseToken, expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken,
                java.time.Instant expiresAt) {
            throw new AssertionError("renew must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                io.paperagent.v2.contracts.PlanId planId,
                String leaseToken) {
            throw new AssertionError("release must not be called");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(
                io.paperagent.v2.contracts.PlanId planId) {
            throw new AssertionError("find must not be called");
        }
    }

    private static final class RecordingMaterializer
            implements RecoveryReadyExecutionStartMaterializer {
        private final RecoveryReadyExecutionStartMaterializer delegate =
                new DeterministicRecoveryReadyExecutionStartMaterializer();
        private final List<MaterializedExecutionStart> proposals =
                new ArrayList<>();

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest request) {
            MaterializedExecutionStart proposal =
                    delegate.materialize(request);
            proposals.add(proposal);
            return proposal;
        }
    }
}
