package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepRecoveryCompositionTest {

    @Test
    void appliedLeaseReturnsOnlyThePostLeaseSourceBackedObservation() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("applied");
        PersistedStepRecoveryActive initial = StepRecoveryCompositionTestFixtures.active(
                "applied", "initial", true);
        PersistedStepRecoveryActive postLease = StepRecoveryCompositionTestFixtures.active(
                "applied", "post-lease", true);
        var recoveryRepository = StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository.found(initial, postLease);
        LeaseRecord lease = StepRecoveryCompositionTestFixtures.matchingLease(request, 3);
        var leaseRepository = new StepRecoveryCompositionTestFixtures.ScriptedLeaseRepository(
                PersistenceResult.applied(lease));

        StepRecoveryCompositionOutcome outcome = new DefaultStepRecoverer(
                recoveryRepository, leaseRepository).recover(request);

        RecoveredActiveStep recovered = assertInstanceOf(
                RecoveredActiveStep.class, outcome);
        assertSame(postLease, recovered.recovery());
        assertSame(lease, recovered.lease());
        assertEquals(StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                recovered.leaseDisposition());
        assertEquals(2, recoveryRepository.inspectCalls());
        assertEquals(1, leaseRepository.acquireCalls());
        assertEquals(1, recovered.recovery().executionContext().stream().count());
    }

    @Test
    void replayedLeaseReturnsOnlyThePostLeaseSourceLessObservation() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("replayed");
        PersistedStepRecoveryActive initial = StepRecoveryCompositionTestFixtures.active(
                "replayed", "initial", false);
        PersistedStepRecoveryActive postLease = StepRecoveryCompositionTestFixtures.active(
                "replayed", "post-lease", false);
        var recoveryRepository = StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository.found(initial, postLease);
        LeaseRecord lease = StepRecoveryCompositionTestFixtures.matchingLease(request, 7);
        var leaseRepository = new StepRecoveryCompositionTestFixtures.ScriptedLeaseRepository(
                PersistenceResult.replayed(lease));

        RecoveredActiveStep recovered = assertInstanceOf(
                RecoveredActiveStep.class,
                new DefaultStepRecoverer(recoveryRepository, leaseRepository)
                        .recover(request));

        assertSame(postLease, recovered.recovery());
        assertSame(lease, recovered.lease());
        assertEquals(PersistenceOutcome.REPLAYED,
                PersistenceResult.replayed(lease).outcome());
        assertEquals(0, recovered.recovery().executionContext().stream().count());
        assertEquals(2, recoveryRepository.inspectCalls());
        assertEquals(1, leaseRepository.acquireCalls());
    }

    @Test
    void initialNotEligibleIsTypedBeforeAnyLeaseOperation() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("not-eligible");
        var recoveryRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository(List.of(PersistenceResult.rejected(
                        PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                        "stepRecovery")));
        var leaseRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedLeaseRepository(null);

        StepRecoveryPersistenceRejected rejected = assertInstanceOf(
                StepRecoveryPersistenceRejected.class,
                new DefaultStepRecoverer(recoveryRepository, leaseRepository)
                        .recover(request));

        assertEquals(StepRecoveryStage.INITIAL_INSPECT, rejected.stage());
        assertEquals(PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                rejected.failure().code());
        assertEquals(StepRecoveryLeaseDisposition.NO_LEASE_ACTION,
                rejected.leaseDisposition());
        assertEquals(1, recoveryRepository.inspectCalls());
        assertEquals(0, leaseRepository.acquireCalls());
    }

    @Test
    void rejectedLeaseIsTypedAndDoesNotReinspect() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("lease-rejected");
        PersistedStepRecoveryActive initial = StepRecoveryCompositionTestFixtures.active(
                "lease-rejected", "initial", false);
        var recoveryRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository(List.of(PersistenceResult.found(initial)));
        var leaseRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedLeaseRepository(PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD, "planId"));

        StepRecoveryLeaseRejected rejected = assertInstanceOf(
                StepRecoveryLeaseRejected.class,
                new DefaultStepRecoverer(recoveryRepository, leaseRepository)
                        .recover(request));

        assertEquals(PersistenceErrorCode.LEASE_HELD, rejected.failure().code());
        assertEquals(StepRecoveryLeaseDisposition.NOT_ACQUIRED,
                rejected.leaseDisposition());
        assertEquals(1, recoveryRepository.inspectCalls());
        assertEquals(1, leaseRepository.acquireCalls());
    }

    @Test
    void postLeasePartialStateIsTypedWithRetainedDisposition() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("post-partial");
        PersistedStepRecoveryActive initial = StepRecoveryCompositionTestFixtures.active(
                "post-partial", "initial", false);
        var recoveryRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository(List.of(
                        PersistenceResult.found(initial),
                        PersistenceResult.rejected(
                                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                                "stepRecovery")));
        var leaseRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedLeaseRepository(PersistenceResult.applied(
                        StepRecoveryCompositionTestFixtures.matchingLease(request, 2)));

        StepRecoveryPersistenceRejected rejected = assertInstanceOf(
                StepRecoveryPersistenceRejected.class,
                new DefaultStepRecoverer(recoveryRepository, leaseRepository)
                        .recover(request));

        assertEquals(StepRecoveryStage.POST_LEASE_INSPECT, rejected.stage());
        assertEquals(PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                rejected.failure().code());
        assertEquals(StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                rejected.leaseDisposition());
        assertEquals(2, recoveryRepository.inspectCalls());
        assertEquals(1, leaseRepository.acquireCalls());
    }

    @Test
    void mismatchingAcquiredLeaseFailsClosed() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("wrong-lease");
        PersistedStepRecoveryActive initial = StepRecoveryCompositionTestFixtures.active(
                "wrong-lease", "initial", false);
        var recoveryRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository(List.of(PersistenceResult.found(initial)));
        LeaseRecord mismatching = new LeaseRecord(
                new PlanId("plan-other"),
                request.leaseAttempt().leaseOwnerId(),
                request.leaseAttempt().leaseToken(),
                1,
                StepRecoveryCompositionTestFixtures.T0,
                request.leaseAttempt().leaseExpiresAt());
        var leaseRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedLeaseRepository(PersistenceResult.applied(mismatching));

        StepRecoveryProtocolException exception = assertThrows(
                StepRecoveryProtocolException.class,
                () -> new DefaultStepRecoverer(recoveryRepository, leaseRepository)
                        .recover(request));

        assertEquals(StepRecoveryStage.LEASE_ACQUIRE, exception.stage());
        assertEquals(StepRecoveryProtocolCode.INCONSISTENT_LEASE_AUTHORITY,
                exception.code());
        assertEquals(StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                exception.leaseDisposition());
        assertEquals(1, recoveryRepository.inspectCalls());
        assertEquals(1, leaseRepository.acquireCalls());
    }

    @Test
    void malformedInitialInspectionFailsClosedWithSanitizedThrowable() {
        StepRecoveryRequest request = StepRecoveryCompositionTestFixtures.request("sanitize");
        var recoveryRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedStepRecoveryRepository(List.<PersistenceResult<StepRecoverySnapshot>>of());
        recoveryRepository.throwWith(new IllegalStateException(
                "owner-sanitize token-sanitize opaque-persistence-value"));
        var leaseRepository = new StepRecoveryCompositionTestFixtures
                .ScriptedLeaseRepository(null);

        StepRecoveryProtocolException exception = assertThrows(
                StepRecoveryProtocolException.class,
                () -> new DefaultStepRecoverer(recoveryRepository, leaseRepository)
                        .recover(request));

        assertEquals(StepRecoveryStage.INITIAL_INSPECT, exception.stage());
        assertEquals(StepRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                exception.code());
        assertEquals(StepRecoveryLeaseDisposition.NO_LEASE_ACTION,
                exception.leaseDisposition());
        assertEquals("io.paperagent.v2.runtime.execution.recovery.composition"
                        + ".StepRecoveryProtocolException$SanitizedCollaboratorException",
                exception.getCause().getClass().getName());
        assertEquals(null, exception.getCause().getCause());
        assertEquals(false, exception.toString().contains("token-sanitize"));
        assertEquals(false, exception.getCause().toString().contains(
                "opaque-persistence-value"));
        assertEquals(1, recoveryRepository.inspectCalls());
        assertEquals(0, leaseRepository.acquireCalls());
    }
}
