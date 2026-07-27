package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializationRequest;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.MaterializedStepActivation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultStepActivationComposerTest {
    @Test
    void materializesAndAcquiresAndActivatesAtMostOnce() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-applied", false);
        AtomicInteger materializeCalls = new AtomicInteger();
        AtomicInteger acquireCalls = new AtomicInteger();
        AtomicInteger activateCalls = new AtomicInteger();
        CountingLeaseRepository leases = new CountingLeaseRepository(
                countingLeases(seeded.persistence().leases(), acquireCalls));
        StepActivationRepository activations = countingActivations(
                seeded.persistence().stepActivations(), activateCalls);
        CommittedStepActivationMaterializer materializer = request -> {
            materializeCalls.incrementAndGet();
            return new DeterministicCommittedStepActivationMaterializer().materialize(request);
        };

        StepActivationCompositionOutcome outcome =
                new DefaultStepActivationComposer(materializer, leases, activations)
                        .compose(seeded.request());

        StepActivationCommitted committed = assertInstanceOf(
                StepActivationCommitted.class, outcome);
        assertEquals(io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                committed.activationOutcome());
        assertEquals(1, materializeCalls.get());
        assertEquals(1, acquireCalls.get());
        assertEquals(1, activateCalls.get());
        assertEquals(StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                committed.leaseDisposition());
        assertEquals(0, leases.renewCalls.get());
        assertEquals(0, leases.releaseCalls.get());
        assertEquals(0, leases.findCalls.get());
    }

    @Test
    void exactRetryIsReplayedWithOneCallPerCollaborator() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-replay", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());

        StepActivationCommitted first = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));
        StepActivationCommitted second = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));

        assertEquals(io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                first.activationOutcome());
        assertEquals(io.paperagent.v2.persistence.PersistenceOutcome.REPLAYED,
                second.activationOutcome());
        assertEquals(first.persistedActivation(), second.persistedActivation());
    }

    @Test
    void nullCandidateIsProtocolFailureBeforeLease() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-null", false);
        AtomicInteger acquireCalls = new AtomicInteger();
        LeaseRepository leases = countingLeases(seeded.persistence().leases(), acquireCalls);
        StepActivationCompositionProtocolException failure = assertThrows(
                StepActivationCompositionProtocolException.class,
                () -> new DefaultStepActivationComposer(
                        request -> null, leases, seeded.persistence().stepActivations())
                        .compose(seeded.request()));
        assertEquals(StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                failure.code());
        assertEquals(StepActivationCompositionStage.MATERIALIZE, failure.stage());
        assertEquals(0, acquireCalls.get());
    }

    @Test
    void rejectedLeaseIsReturnedWithoutActivation() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-lease-rejected", false);
        PersistenceResult<LeaseRecord> rejection = PersistenceResult.rejected(
                PersistenceErrorCode.LEASE_HELD, "planId");
        AtomicInteger activationCalls = new AtomicInteger();
        StepActivationCompositionOutcome outcome = new DefaultStepActivationComposer(
                new DeterministicCommittedStepActivationMaterializer(),
                fixedLease(rejection),
                countingActivations(seeded.persistence().stepActivations(), activationCalls))
                .compose(seeded.request());
        StepActivationLeaseRejected rejected = assertInstanceOf(
                StepActivationLeaseRejected.class, outcome);
        assertEquals(PersistenceErrorCode.LEASE_HELD, rejected.failure().code());
        assertEquals(StepActivationLeaseDisposition.NOT_ACQUIRED,
                rejected.leaseDisposition());
        assertEquals(0, activationCalls.get());
    }

    @Test
    void collaboratorExceptionIsSanitized() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-throw", false);
        StepActivationCompositionProtocolException failure = assertThrows(
                StepActivationCompositionProtocolException.class,
                () -> new DefaultStepActivationComposer(
                        new DeterministicCommittedStepActivationMaterializer(),
                        throwingLease(),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()));
        assertEquals(StepActivationCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                failure.code());
        assertTrue(failure.getCause().getMessage().contains(IllegalStateException.class.getName()));
        assertFalse(failure.getCause().getMessage().contains("secret collaborator details"));
        assertEquals(StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE,
                failure.leaseDisposition());
    }

    @Test
    void nonNullMismatchedMaterializationIsRejectedBeforeLease() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-mismatch", false);
        MaterializedStepActivation valid = materialized(seeded.request());
        EventEnvelope wrongEvent = new EventEnvelope(
                valid.activationEvent().id(),
                valid.activationEvent().taskFrameId(),
                valid.activationEvent().planId(),
                3,
                valid.activationEvent().occurredAt(),
                valid.activationEvent().type(),
                valid.activationEvent().causationId(),
                valid.activationEvent().correlationId(),
                valid.activationEvent().payload());
        AtomicInteger acquireCalls = new AtomicInteger();
        assertProtocol(
                () -> new DefaultStepActivationComposer(
                        request -> new MaterializedStepActivation(
                                wrongEvent, valid.activatedCheckpoint()),
                        countingLeases(seeded.persistence().leases(), acquireCalls),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()),
                StepActivationCompositionStage.MATERIALIZE,
                StepActivationCompositionProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "stepActivationComposition.materializeResult.value",
                StepActivationLeaseDisposition.NO_LEASE_ACTION);
        assertEquals(0, acquireCalls.get());

        Checkpoint wrongCheckpoint = new Checkpoint(
                valid.activatedCheckpoint().taskFrameId(),
                valid.activatedCheckpoint().planId(),
                valid.activatedCheckpoint().revisionId(),
                valid.activatedCheckpoint().revisionNumber(),
                valid.activatedCheckpoint().lastEventSequence(),
                valid.activatedCheckpoint().planState(),
                valid.activatedCheckpoint().stepStates(),
                valid.activatedCheckpoint().receiptReferences(),
                valid.activatedCheckpoint().createdAt().plusSeconds(1));
        assertProtocol(
                () -> new DefaultStepActivationComposer(
                        request -> new MaterializedStepActivation(
                                valid.activationEvent(), wrongCheckpoint),
                        countingLeases(seeded.persistence().leases(), acquireCalls),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()),
                StepActivationCompositionStage.MATERIALIZE,
                StepActivationCompositionProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "stepActivationComposition.materializeResult.value",
                StepActivationLeaseDisposition.NO_LEASE_ACTION);
        assertEquals(0, acquireCalls.get());
    }

    @Test
    void leaseNullFoundAndMismatchedAuthoritiesAreStableProtocolResults() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-lease-protocol", false);
        assertProtocol(
                () -> new DefaultStepActivationComposer(
                        new DeterministicCommittedStepActivationMaterializer(),
                        fixedLease(null),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()),
                StepActivationCompositionStage.LEASE_ACQUIRE,
                StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                "stepActivationComposition.leaseAcquireResult",
                StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE);

        LeaseRecord validLease = validLease(seeded.request());
        assertProtocol(
                () -> new DefaultStepActivationComposer(
                        new DeterministicCommittedStepActivationMaterializer(),
                        fixedLease(PersistenceResult.found(validLease)),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()),
                StepActivationCompositionStage.LEASE_ACQUIRE,
                StepActivationCompositionProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                "stepActivationComposition.leaseAcquireResult.outcome",
                StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE);

        LeaseRecord wrongLease = new LeaseRecord(
                validLease.planId(),
                "wrong-owner",
                validLease.leaseToken(),
                validLease.fencingToken(),
                validLease.acquiredAt(),
                validLease.expiresAt());
        assertProtocol(
                () -> new DefaultStepActivationComposer(
                        new DeterministicCommittedStepActivationMaterializer(),
                        fixedLease(PersistenceResult.applied(wrongLease)),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()),
                StepActivationCompositionStage.LEASE_ACQUIRE,
                StepActivationCompositionProtocolCode.INCONSISTENT_LEASE_AUTHORITY,
                "stepActivationComposition.leaseAcquireResult.value",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    @Test
    void leaseRejectionIsReturnedUnchangedAndLifecyclePortsRemainUnused() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-lease-reject", false);
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_HELD, "planId");
        CountingLeaseRepository leases = new CountingLeaseRepository(
                fixedLease(PersistenceResult.rejected(
                        failure.code(), failure.path())));
        StepActivationLeaseRejected rejected = assertInstanceOf(
                StepActivationLeaseRejected.class,
                new DefaultStepActivationComposer(
                        new DeterministicCommittedStepActivationMaterializer(),
                        leases,
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()));
        assertEquals(failure, rejected.failure());
        assertEquals(StepActivationLeaseDisposition.NOT_ACQUIRED,
                rejected.leaseDisposition());
        assertEquals(0, leases.renewCalls.get());
        assertEquals(0, leases.releaseCalls.get());
        assertEquals(0, leases.findCalls.get());
    }

    @Test
    void activationNullFoundThrowRejectedAndMismatchedResultsAreClassified() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-activation-protocol", false);
        LeaseRecord lease = validLease(seeded.request());

        assertProtocol(
                () -> composeWithActivation(seeded, lease, request -> null),
                StepActivationCompositionStage.ATOMIC_ACTIVATION,
                StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                "stepActivationComposition.activationResult",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
        assertProtocol(
                () -> composeWithActivation(
                        seeded,
                        lease,
                        request -> PersistenceResult.found(
                                matching(request, lease))),
                StepActivationCompositionStage.ATOMIC_ACTIVATION,
                StepActivationCompositionProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                "stepActivationComposition.activationResult.outcome",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
        StepActivationCompositionProtocolException activationThrown = assertProtocol(
                () -> composeWithActivation(
                        seeded,
                        lease,
                        request -> { throw new IllegalStateException("activation secret"); }),
                StepActivationCompositionStage.ATOMIC_ACTIVATION,
                StepActivationCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                "stepActivationComposition.activationResult",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
        assertTrue(activationThrown.getCause().getMessage()
                .contains(IllegalStateException.class.getName()));
        assertFalse(activationThrown.getCause().getMessage()
                .contains("activation secret"));

        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");
        StepActivationPersistenceRejected rejected = assertInstanceOf(
                StepActivationPersistenceRejected.class,
                composeWithActivation(
                        seeded,
                        lease,
                        request -> PersistenceResult.rejected(
                                failure.code(), failure.path())));
        assertEquals(failure, rejected.failure());
        assertEquals(StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                rejected.leaseDisposition());

        PersistedStepActivation wrong = new PersistedStepActivation(
                seeded.committed().planId(),
                seeded.request().stepId(),
                "wrong-owner",
                lease.fencingToken(),
                materialized(seeded.request()).activationEvent(),
                new VersionedCheckpoint(
                        3,
                        materialized(seeded.request()).activatedCheckpoint()));
        assertProtocol(
                () -> composeWithActivation(
                        seeded,
                        lease,
                        request -> PersistenceResult.applied(wrong)),
                StepActivationCompositionStage.ATOMIC_ACTIVATION,
                StepActivationCompositionProtocolCode.INCONSISTENT_ACTIVATION_RESULT,
                "stepActivationComposition.activationResult.value",
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    @Test
    void activationRequestUsesOnlyCommittedH0AndReturnedFence() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-request", false);
        LeaseRecord lease = validLease(seeded.request());
        AtomicReference<StepActivationRequest> captured = new AtomicReference<>();
        StepActivationCommitted outcome = assertInstanceOf(
                StepActivationCommitted.class,
                composeWithActivation(
                        seeded,
                        lease,
                        request -> {
                            captured.set(request);
                            return PersistenceResult.applied(matching(request, lease));
                        }));
        StepActivationRequest request = captured.get();
        Checkpoint h0 = seeded.committed().executionStart()
                .startedCheckpoint().checkpoint();
        assertEquals(seeded.committed().planId(), request.planId());
        assertEquals(lease.leaseToken(), request.leaseToken());
        assertEquals(lease.fencingToken(), request.fencingToken());
        assertEquals(h0.revisionId(), request.expectedRevisionId());
        assertEquals(h0.revisionNumber(), request.expectedRevisionNumber());
        assertEquals(2, request.expectedCheckpointVersion());
        assertEquals(1, request.expectedEventHeadSequence());
        assertEquals(seeded.request().stepId(), request.stepId());
        MaterializedStepActivation expected = materialized(seeded.request());
        assertEquals(expected.activationEvent(), request.activationEvent());
        assertEquals(expected.activatedCheckpoint(), request.activatedCheckpoint());
        assertEquals(PersistenceOutcome.APPLIED, outcome.activationOutcome());
    }

    private static MaterializedStepActivation materialized(
            StepActivationCompositionRequest request) {
        return new DeterministicCommittedStepActivationMaterializer().materialize(
                new CommittedStepActivationMaterializationRequest(
                        request.committedStart(),
                        request.stepId(),
                        request.attempt().eventDraft(),
                        request.attempt().checkpointCreatedAt()));
    }

    private static LeaseRecord validLease(
            StepActivationCompositionRequest request) {
        return new LeaseRecord(
                request.committedStart().planId(),
                request.attempt().leaseOwnerId(),
                request.attempt().leaseToken(),
                7,
                StepActivationCompositionTestFixtures.T0,
                request.attempt().leaseExpiresAt());
    }

    private static PersistedStepActivation matching(
            StepActivationRequest request,
            LeaseRecord lease) {
        return new PersistedStepActivation(
                request.planId(),
                request.stepId(),
                lease.ownerId(),
                lease.fencingToken(),
                request.activationEvent(),
                new VersionedCheckpoint(3, request.activatedCheckpoint()));
    }

    private static StepActivationCompositionOutcome composeWithActivation(
            StepActivationCompositionTestFixtures.Seeded seeded,
            LeaseRecord lease,
            java.util.function.Function<StepActivationRequest,
                    PersistenceResult<PersistedStepActivation>> activation) {
        return new DefaultStepActivationComposer(
                new DeterministicCommittedStepActivationMaterializer(),
                fixedLease(PersistenceResult.applied(lease)),
                activation::apply)
                .compose(seeded.request());
    }

    private static StepActivationCompositionProtocolException assertProtocol(
            org.junit.jupiter.api.function.Executable action,
            StepActivationCompositionStage stage,
            StepActivationCompositionProtocolCode code,
            String path,
            StepActivationLeaseDisposition disposition) {
        StepActivationCompositionProtocolException failure = assertThrows(
                StepActivationCompositionProtocolException.class,
                action);
        assertEquals(stage, failure.stage());
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        assertEquals(disposition, failure.leaseDisposition());
        return failure;
    }

    private static LeaseRepository fixedLease(PersistenceResult<LeaseRecord> result) {
        return new LeaseRepository() {
            public PersistenceResult<LeaseRecord> acquire(PlanId planId, String owner, String token, Instant expiry) {
                return result;
            }
            public PersistenceResult<LeaseRecord> renew(PlanId p, String t, Instant e) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> release(PlanId p, String t) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> find(PlanId p) { throw new AssertionError(); }
        };
    }

    private static LeaseRepository throwingLease() {
        return new LeaseRepository() {
            public PersistenceResult<LeaseRecord> acquire(PlanId p, String o, String t, Instant e) {
                throw new IllegalStateException("secret collaborator details");
            }
            public PersistenceResult<LeaseRecord> renew(PlanId p, String t, Instant e) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> release(PlanId p, String t) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> find(PlanId p) { throw new AssertionError(); }
        };
    }

    private static LeaseRepository countingLeases(
            LeaseRepository delegate,
            AtomicInteger calls) {
        return new LeaseRepository() {
            public PersistenceResult<LeaseRecord> acquire(PlanId p, String o, String t, Instant e) {
                calls.incrementAndGet(); return delegate.acquire(p, o, t, e);
            }
            public PersistenceResult<LeaseRecord> renew(PlanId p, String t, Instant e) { return delegate.renew(p, t, e); }
            public PersistenceResult<LeaseRecord> release(PlanId p, String t) { return delegate.release(p, t); }
            public PersistenceResult<LeaseRecord> find(PlanId p) { return delegate.find(p); }
        };
    }

    private static final class CountingLeaseRepository
            implements LeaseRepository {
        private final LeaseRepository delegate;
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();
        private final AtomicInteger findCalls = new AtomicInteger();

        private CountingLeaseRepository(LeaseRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            return delegate.acquire(planId, ownerId, leaseToken, expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            renewCalls.incrementAndGet();
            return delegate.renew(planId, leaseToken, expiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId planId,
                String leaseToken) {
            releaseCalls.incrementAndGet();
            return delegate.release(planId, leaseToken);
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            findCalls.incrementAndGet();
            return delegate.find(planId);
        }
    }

    private static StepActivationRepository countingActivations(
            StepActivationRepository delegate,
            AtomicInteger calls) {
        return request -> { calls.incrementAndGet(); return delegate.activate(request); };
    }
}
