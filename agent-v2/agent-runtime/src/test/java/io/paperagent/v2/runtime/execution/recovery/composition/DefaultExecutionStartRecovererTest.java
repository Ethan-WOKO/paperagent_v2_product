package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.ContractViolation;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ViolationCode;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.NULL;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.attempt;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.committed;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.lease;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.materialized;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.persisted;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.ready;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.request;
import static io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryTestFixtures.revisedReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultExecutionStartRecovererTest {
    @Test
    void constructorRequestAndOutcomeInvariantsUseStablePaths() {
        var trace = new ArrayList<String>();
        var ready = ready("validation");
        var attempt = attempt("validation");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, candidate);
        var inspector = inspector(
                trace,
                PersistenceResult.found(ready));
        var materializer = materializer(trace, candidate);
        var leases = leases(trace, PersistenceResult.applied(lease));
        var starts = starts(
                trace,
                PersistenceResult.applied(persisted));

        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.recoveryRepository",
                () -> new DefaultExecutionStartRecoverer(
                        null, materializer, leases, starts));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.materializer",
                () -> new DefaultExecutionStartRecoverer(
                        inspector, null, leases, starts));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.leaseRepository",
                () -> new DefaultExecutionStartRecoverer(
                        inspector, materializer, null, starts));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.executionStartRepository",
                () -> new DefaultExecutionStartRecoverer(
                        inspector, materializer, leases, null));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.request.planId",
                () -> new ExecutionStartRecoveryRequest(
                        null, Optional.empty()));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.request.attempt",
                () -> new ExecutionStartRecoveryRequest(
                        ready.planId(), null));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecovery.request",
                () -> recoverer(inspector, materializer, leases, starts)
                        .recover(null));

        var recoveryRequest = request(ready, attempt);
        assertEquals(
                "ExecutionStartRecoveryRequest"
                        + "[planId=<provided>, attempt=<provided>]",
                recoveryRequest.toString());
        assertFalse(recoveryRequest.toString().contains(attempt.leaseToken()));

        assertValidation(
                "recoveredExecutionStart.leaseDisposition",
                () -> new RecoveredExecutionStart(
                        ExecutionStartRecoveryResolution
                                .ATOMIC_START_APPLIED,
                        persisted,
                        ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED));
        assertValidation(
                "executionStartRecoveryRetryRequired.leaseDisposition",
                () -> new ExecutionStartRecoveryRetryRequired(
                        ready.planId(),
                        ExecutionStartRecoveryLeaseDisposition
                                .ACQUISITION_INDETERMINATE));
        assertValidation(
                "executionStartRecoveryRejected.failure",
                () -> new ExecutionStartRecoveryRejected(
                        ready.planId(),
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode.LEASE_HELD,
                                "lease"),
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                "executionStartRecoveryAdvancedUnsupported.failure",
                () -> new ExecutionStartRecoveryAdvancedUnsupported(
                        ready.planId(),
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_PARTIAL_STATE,
                                "executionRecovery"),
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
    }

    @Test
    void outcomeConstructorsCoverTheCompleteFrozenStateSpace() {
        var ready = ready("constructor-matrix");
        var attempt = attempt("constructor-matrix");
        var persisted = persisted(
                ready,
                lease(ready, attempt, 1),
                materialized(ready, attempt));
        var notFound = new PersistenceFailure(
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        var advanced = new PersistenceFailure(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");

        for (ExecutionStartRecoveryResolution resolution
                : ExecutionStartRecoveryResolution.values()) {
            for (ExecutionStartRecoveryLeaseDisposition disposition
                    : ExecutionStartRecoveryLeaseDisposition.values()) {
                boolean valid = resolution
                        == ExecutionStartRecoveryResolution
                                .OBSERVED_COMMITTED
                        || disposition
                                == ExecutionStartRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY;
                assertConstructorValidity(
                        valid,
                        "recoveredExecutionStart.leaseDisposition",
                        () -> new RecoveredExecutionStart(
                                resolution, persisted, disposition));
            }
        }

        List<PersistenceFailure> failures =
                allPersistenceFailures();
        for (ExecutionStartRecoveryStage stage
                : ExecutionStartRecoveryStage.values()) {
            for (ExecutionStartRecoveryLeaseDisposition disposition
                    : ExecutionStartRecoveryLeaseDisposition.values()) {
                for (PersistenceFailure failure : failures) {
                    boolean valid = validRejectedCombination(
                            stage, disposition, failure);
                    assertConstructorValidity(
                            valid,
                            invalidRejectedPath(
                                    stage, disposition, failure),
                            () -> new ExecutionStartRecoveryRejected(
                                    ready.planId(),
                                    stage,
                                    failure,
                                    disposition));
                }
            }
        }

        for (ExecutionStartRecoveryStage stage
                : ExecutionStartRecoveryStage.values()) {
            for (ExecutionStartRecoveryLeaseDisposition disposition
                    : ExecutionStartRecoveryLeaseDisposition.values()) {
                for (PersistenceFailure failure : failures) {
                    boolean valid = validAdvancedCombination(
                            stage, disposition, failure);
                    assertConstructorValidity(
                            valid,
                            invalidAdvancedPath(
                                    stage, disposition, failure),
                            () ->
                                    new ExecutionStartRecoveryAdvancedUnsupported(
                                            ready.planId(),
                                            stage,
                                            failure,
                                            disposition));
                }
            }
        }

        for (ExecutionStartRecoveryLeaseDisposition disposition
                : ExecutionStartRecoveryLeaseDisposition.values()) {
            assertConstructorValidity(
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY,
                    "executionStartRecoveryRetryRequired.leaseDisposition",
                    () -> new ExecutionStartRecoveryRetryRequired(
                            ready.planId(), disposition));
        }

        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "recoveredExecutionStart.resolution",
                () -> new RecoveredExecutionStart(
                        null,
                        persisted,
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "recoveredExecutionStart.persistedStart",
                () -> new RecoveredExecutionStart(
                        ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                        null,
                        ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "recoveredExecutionStart.leaseDisposition",
                () -> new RecoveredExecutionStart(
                        ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                        persisted,
                        null));
        assertRejectedRequiredValues(
                ready.planId(), notFound);
        assertAdvancedRequiredValues(
                ready.planId(), advanced);
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryRetryRequired.planId",
                () -> new ExecutionStartRecoveryRetryRequired(
                        null,
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryRetryRequired.leaseDisposition",
                () -> new ExecutionStartRecoveryRetryRequired(
                        ready.planId(), null));
    }

    @Test
    void initialInspectionClassifiesAllAuthoritativeStatesBeforeAttempt() {
        var ready = ready("initial");
        var attempt = attempt("initial");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, candidate);
        var committed = committed(ready, persisted);
        var noAttempt = new ExecutionStartRecoveryRequest(
                ready.planId(), Optional.empty());

        var observed = recoverWithInitial(
                noAttempt,
                PersistenceResult.found(committed));
        var recovered = assertInstanceOf(
                RecoveredExecutionStart.class, observed);
        assertEquals(
                ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                recovered.resolution());
        assertEquals(
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                recovered.leaseDisposition());

        var advancedFailure = new PersistenceFailure(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");
        var advanced = assertInstanceOf(
                ExecutionStartRecoveryAdvancedUnsupported.class,
                recoverWithInitial(
                        noAttempt,
                        PersistenceResult.rejected(
                                advancedFailure.code(),
                                advancedFailure.path())));
        assertSame(advancedFailure.code(), advanced.failure().code());
        assertEquals("executionRecovery", advanced.failure().path());

        var partial = assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                recoverWithInitial(
                        noAttempt,
                        PersistenceResult.rejected(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_PARTIAL_STATE,
                                "executionRecovery")));
        assertEquals(
                ExecutionStartRecoveryStage.INITIAL_INSPECT,
                partial.stage());

        var notFound = assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                recoverWithInitial(
                        noAttempt,
                        PersistenceResult.rejected(
                                PersistenceErrorCode.NOT_FOUND,
                                "planId")));
        assertEquals(PersistenceErrorCode.NOT_FOUND,
                notFound.failure().code());

        assertValidation(
                "executionStartRecovery.request.attempt",
                () -> recoverWithInitial(
                        noAttempt,
                        PersistenceResult.found(ready)));
    }

    @Test
    void happyPathUsesExactTraceFreshP2AndRepositoryFence() {
        var trace = new ArrayList<String>();
        var first = ready("happy");
        var attempt = attempt("happy");
        var p1 = materialized(first, attempt);
        var second = ready("happy");
        var p2 = materialized(second, attempt);
        var lease = lease(second, attempt, 7);
        var persisted = persisted(second, lease, p2);
        var committed = committed(second, persisted);
        var inspector = inspector(
                trace,
                PersistenceResult.found(first),
                PersistenceResult.found(second),
                PersistenceResult.found(committed));
        var materializer = materializer(trace, p1, p2);
        var leases = leases(trace, PersistenceResult.applied(lease));
        var starts = starts(
                trace,
                PersistenceResult.applied(persisted));

        var result = assertInstanceOf(
                RecoveredExecutionStart.class,
                recoverer(inspector, materializer, leases, starts)
                        .recover(request(first, attempt)));

        assertEquals(
                List.of("I1", "P1", "A", "I2", "P2", "S", "I3"),
                trace);
        assertEquals(
                ExecutionStartRecoveryResolution.ATOMIC_START_APPLIED,
                result.resolution());
        assertSame(persisted, result.persistedStart());
        assertEquals(2, materializer.requests.size());
        assertSame(first, materializer.requests.get(0).ready());
        assertSame(second, materializer.requests.get(1).ready());
        assertNotSame(p1, p2);
        assertEquals(7, starts.request.fencingToken());
        assertEquals(attempt.leaseToken(), starts.request.leaseToken());
        assertEquals(p2.startEvent(), starts.request.startEvent());
        assertEquals(
                p2.startedCheckpoint(),
                starts.request.startedCheckpoint());
        leases.assertOnlyAcquireWasUsed();
    }

    @Test
    void stalePreflightCannotReplaceFreshPostLeaseMaterialization() {
        var trace = new ArrayList<String>();
        var revisionOne = ready("fresh-p2");
        var revisionTwo = revisedReady(revisionOne, "fresh-p2");
        var attempt = attempt("fresh-p2");
        var stale = materialized(revisionOne, attempt);
        var lease = lease(revisionTwo, attempt, 3);
        var inspector = inspector(
                trace,
                PersistenceResult.found(revisionOne),
                PersistenceResult.found(revisionTwo));
        var materializer = materializer(trace, stale, stale);
        var leases = leases(trace, PersistenceResult.applied(lease));
        var starts = starts(trace, NULL);

        var protocol = assertProtocol(
                ExecutionStartRecoveryStage.POST_LEASE_MATERIALIZE,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "executionStartRecovery"
                        + ".postLeaseMaterializedStart.startedCheckpoint",
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                () -> recoverer(inspector, materializer, leases, starts)
                        .recover(request(revisionOne, attempt)));

        assertEquals(
                List.of("I1", "P1", "A", "I2", "P2"),
                trace);
        assertSame(revisionOne, materializer.requests.get(0).ready());
        assertSame(revisionTwo, materializer.requests.get(1).ready());
        assertEquals(0, starts.calls.get());
        assertEquals(
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                protocol.code());
    }

    @Test
    void preflightNullMismatchAndThrowableFailBeforeAcquire() {
        var ready = ready("preflight");
        var attempt = attempt("preflight");
        var correct = materialized(ready, attempt);
        var other = materialized(
                ready("preflight-other"),
                attempt("preflight-other"));

        assertValidation(
                ExecutionStartRecoveryValidationCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "executionStartRecovery.materializedStart",
                () -> recoverWithP1(ready, attempt, NULL));
        assertValidation(
                ExecutionStartRecoveryValidationCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "executionStartRecovery.materializedStart.startEvent",
                () -> recoverWithP1(
                        ready,
                        attempt,
                        new MaterializedExecutionStart(
                                other.startEvent(),
                                correct.startedCheckpoint())));
        assertValidation(
                ExecutionStartRecoveryValidationCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "executionStartRecovery.materializedStart.startedCheckpoint",
                () -> recoverWithP1(
                        ready,
                        attempt,
                        new MaterializedExecutionStart(
                                correct.startEvent(),
                                other.startedCheckpoint())));

        String secret = "SECRET-preflight";
        var forged = new ContractViolationException(List.of(
                new ContractViolation(
                        ViolationCode.REQUIRED_VALUE_MISSING,
                        secret,
                        secret)));
        forged.addSuppressed(new IllegalStateException(secret));
        forged.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(
                        secret, secret, secret, 1)
        });
        var protocol = assertProtocol(
                ExecutionStartRecoveryStage.MATERIALIZE,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.materializedStart",
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                () -> recoverWithP1(ready, attempt, forged));
        assertSanitized(protocol, secret, forged);
    }

    @Test
    void acquireAlwaysInspectsAgainAndStateCanResolveUncertainty() {
        var ready = ready("acquire");
        var attempt = attempt("acquire");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, candidate);
        var committed = committed(ready, persisted);

        for (Object acquire : List.of(
                NULL,
                new IllegalStateException("SECRET-acquire"))) {
            var trace = new ArrayList<String>();
            var result = recoverer(
                    inspector(
                            trace,
                            PersistenceResult.found(ready),
                            PersistenceResult.found(committed)),
                    materializer(trace, candidate),
                    leases(trace, acquire),
                    starts(trace, NULL))
                    .recover(request(ready, attempt));
            var recovered = assertInstanceOf(
                    RecoveredExecutionStart.class, result);
            assertEquals(
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    recovered.leaseDisposition());
            assertEquals(List.of("I1", "P1", "A", "I2"), trace);
        }

        var failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_HELD,
                "lease");
        var leaseRejection = new PersistenceResult<LeaseRecord>(
                PersistenceOutcome.REJECTED,
                Optional.empty(),
                Optional.of(failure));
        var trace = new ArrayList<String>();
        var rejected = assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                recoverer(
                        inspector(
                                trace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(ready)),
                        materializer(trace, candidate),
                        leases(trace, leaseRejection),
                        starts(trace, NULL))
                        .recover(request(ready, attempt)));
        assertEquals(ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                rejected.stage());
        assertSame(failure, rejected.failure());
        assertEquals(List.of("I1", "P1", "A", "I2"), trace);

        for (Object acquire : List.of(
                NULL,
                new IllegalStateException("SECRET-acquire"))) {
            var indeterminateTrace = new ArrayList<String>();
            var protocol = assertThrows(
                    ExecutionStartRecoveryProtocolException.class,
                    () -> recoverer(
                            inspector(
                                    indeterminateTrace,
                                    PersistenceResult.found(ready),
                                    PersistenceResult.found(ready)),
                            materializer(indeterminateTrace, candidate),
                            leases(indeterminateTrace, acquire),
                            starts(indeterminateTrace, NULL))
                            .recover(request(ready, attempt)));
            assertEquals(
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    protocol.stage());
            assertEquals(
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    protocol.leaseDisposition());
            assertEquals(
                    List.of("I1", "P1", "A", "I2"),
                    indeterminateTrace);
        }
    }

    @Test
    void brokenAcquireIsNotMaskedButBrokenPostInspectTakesPriority() {
        var ready = ready("broken-acquire");
        var attempt = attempt("broken-acquire");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var committed = committed(
                ready,
                persisted(ready, lease, candidate));

        var foundTrace = new ArrayList<String>();
        var found = assertProtocol(
                ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                ExecutionStartRecoveryProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "executionStartRecovery.leaseAcquireResult.outcome",
                ExecutionStartRecoveryLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                () -> recoverer(
                        inspector(
                                foundTrace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(committed)),
                        materializer(foundTrace, candidate),
                        leases(foundTrace, PersistenceResult.found(lease)),
                        starts(foundTrace, NULL))
                        .recover(request(ready, attempt)));
        assertEquals(List.of("I1", "P1", "A", "I2"), foundTrace);
        assertEquals(
                ExecutionStartRecoveryProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                found.code());

        LeaseRecord wrongLease = new LeaseRecord(
                ready.planId(),
                attempt.leaseOwnerId(),
                "different-token",
                1,
                lease.acquiredAt(),
                attempt.leaseExpiresAt());
        var mismatchTrace = new ArrayList<String>();
        assertProtocol(
                ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_LEASE_AUTHORITY,
                "executionStartRecovery.leaseAcquireResult.value",
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                () -> recoverer(
                        inspector(
                                mismatchTrace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(committed)),
                        materializer(mismatchTrace, candidate),
                        leases(
                                mismatchTrace,
                                PersistenceResult.applied(wrongLease)),
                        starts(mismatchTrace, NULL))
                        .recover(request(ready, attempt)));

        var priorityTrace = new ArrayList<String>();
        assertProtocol(
                ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                ExecutionStartRecoveryProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                "executionStartRecovery.postLeaseInspectResult",
                ExecutionStartRecoveryLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                () -> recoverer(
                        inspector(
                                priorityTrace,
                                PersistenceResult.found(ready),
                                NULL),
                        materializer(priorityTrace, candidate),
                        leases(
                                priorityTrace,
                                PersistenceResult.found(lease)),
                        starts(priorityTrace, NULL))
                        .recover(request(ready, attempt)));
    }

    @Test
    void postLeaseMaterializationUsesProtocolAndNeverStarts() {
        var ready = ready("p2");
        var attempt = attempt("p2");
        var candidate = materialized(ready, attempt);
        var other = materialized(
                ready("p2-other"),
                attempt("p2-other"));
        var lease = lease(ready, attempt, 1);

        assertP2Failure(
                ready,
                attempt,
                candidate,
                lease,
                NULL,
                ExecutionStartRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                "executionStartRecovery.postLeaseMaterializedStart",
                null);

        var thrown = polluted(
                new IllegalArgumentException("SECRET-p2"));
        var thrownProtocol = assertP2Failure(
                ready,
                attempt,
                candidate,
                lease,
                thrown,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.postLeaseMaterializedStart",
                thrown);
        assertSanitized(thrownProtocol, "SECRET-p2", thrown);

        assertP2Failure(
                ready,
                attempt,
                candidate,
                lease,
                new MaterializedExecutionStart(
                        other.startEvent(),
                        candidate.startedCheckpoint()),
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "executionStartRecovery"
                        + ".postLeaseMaterializedStart.startEvent",
                null);
        assertP2Failure(
                ready,
                attempt,
                candidate,
                lease,
                new MaterializedExecutionStart(
                        candidate.startEvent(),
                        other.startedCheckpoint()),
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "executionStartRecovery"
                        + ".postLeaseMaterializedStart.startedCheckpoint",
                null);
    }

    @Test
    void startAlwaysInspectsAndReconcilesEveryLegalCategory() {
        var ready = ready("start");
        var attempt = attempt("start");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, candidate);
        var committed = committed(ready, persisted);

        var rejectedFailure = new PersistenceFailure(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "executionStart");
        var startRejection = new PersistenceResult<PersistedExecutionStart>(
                PersistenceOutcome.REJECTED,
                Optional.empty(),
                Optional.of(rejectedFailure));
        var rejected = runStart(
                ready,
                attempt,
                candidate,
                lease,
                startRejection,
                PersistenceResult.found(ready));
        var rejectedOutcome = assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                rejected.outcome());
        assertEquals(ExecutionStartRecoveryStage.ATOMIC_START,
                rejectedOutcome.stage());
        assertSame(rejectedFailure, rejectedOutcome.failure());

        var retry = runStart(
                ready,
                attempt,
                candidate,
                lease,
                NULL,
                PersistenceResult.found(ready));
        assertInstanceOf(
                ExecutionStartRecoveryRetryRequired.class,
                retry.outcome());

        var reconciled = runStart(
                ready,
                attempt,
                candidate,
                lease,
                new IllegalStateException("SECRET-start"),
                PersistenceResult.found(committed));
        var reconciledOutcome = assertInstanceOf(
                RecoveredExecutionStart.class,
                reconciled.outcome());
        assertEquals(
                ExecutionStartRecoveryResolution
                        .RECONCILED_AFTER_RESPONSE_LOSS,
                reconciledOutcome.resolution());

        var advanced = runStart(
                ready,
                attempt,
                candidate,
                lease,
                PersistenceResult.applied(persisted),
                PersistenceResult.rejected(
                        PersistenceErrorCode
                                .EXECUTION_RECOVERY_ADVANCED_STATE,
                        "executionRecovery"));
        assertInstanceOf(
                ExecutionStartRecoveryAdvancedUnsupported.class,
                advanced.outcome());

        var partial = runStart(
                ready,
                attempt,
                candidate,
                lease,
                PersistenceResult.replayed(persisted),
                PersistenceResult.rejected(
                        PersistenceErrorCode
                                .EXECUTION_RECOVERY_PARTIAL_STATE,
                        "executionRecovery"));
        assertInstanceOf(
                ExecutionStartRecoveryRejected.class,
                partial.outcome());

        for (StartRun run : List.of(
                rejected, retry, reconciled, advanced, partial)) {
            assertEquals(
                    List.of("I1", "P1", "A", "I2", "P2", "S", "I3"),
                    run.trace());
        }
    }

    @Test
    void brokenStartIsNotMaskedAndPostInspectFailureWins() {
        var ready = ready("broken-start");
        var attempt = attempt("broken-start");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, candidate);
        var committed = committed(ready, persisted);

        var found = assertThrows(
                ExecutionStartRecoveryProtocolException.class,
                () -> runStart(
                        ready,
                        attempt,
                        candidate,
                        lease,
                        PersistenceResult.found(persisted),
                        PersistenceResult.found(committed)));
        assertEquals(ExecutionStartRecoveryStage.ATOMIC_START, found.stage());
        assertEquals(
                ExecutionStartRecoveryProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                found.code());

        var otherReady = ready("other-start");
        var otherAttempt = attempt("other-start");
        var otherCandidate = materialized(otherReady, otherAttempt);
        var otherLease = lease(otherReady, otherAttempt, 9);
        var wrongPersisted = persisted(
                otherReady, otherLease, otherCandidate);
        var mismatch = assertThrows(
                ExecutionStartRecoveryProtocolException.class,
                () -> runStart(
                        ready,
                        attempt,
                        candidate,
                        lease,
                        PersistenceResult.applied(wrongPersisted),
                        PersistenceResult.found(committed)));
        assertEquals(
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_EXECUTION_START_RESULT,
                mismatch.code());

        PersistedExecutionStart different = new PersistedExecutionStart(
                persisted.planId(),
                persisted.leaseOwnerId(),
                persisted.fencingToken() + 1,
                persisted.startEvent(),
                persisted.startedCheckpoint());
        var differentCommitted = committed(ready, different);
        for (PersistenceResult<PersistedExecutionStart> success : List.of(
                PersistenceResult.applied(persisted),
                PersistenceResult.replayed(persisted))) {
            var contradictionTrace = new ArrayList<String>();
            var contradiction = assertProtocol(
                    ExecutionStartRecoveryStage.POST_START_INSPECT,
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_INSPECTION_RESULT,
                    "executionStartRecovery.postStartInspectResult.value",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    () -> recoverer(
                            inspector(
                                    contradictionTrace,
                                    PersistenceResult.found(ready),
                                    PersistenceResult.found(ready),
                                    PersistenceResult.found(
                                            differentCommitted)),
                            materializer(
                                    contradictionTrace,
                                    candidate,
                                    candidate),
                            leases(
                                    contradictionTrace,
                                    PersistenceResult.applied(lease)),
                            starts(contradictionTrace, success))
                            .recover(request(ready, attempt)));
            assertEquals(
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_INSPECTION_RESULT,
                    contradiction.code());
            assertEquals(
                    List.of(
                            "I1", "P1", "A", "I2",
                            "P2", "S", "I3"),
                    contradictionTrace);
        }

        var priorityTrace = new ArrayList<String>();
        var priority = assertThrows(
                ExecutionStartRecoveryProtocolException.class,
                () -> recoverer(
                        inspector(
                                priorityTrace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(ready),
                                NULL),
                        materializer(
                                priorityTrace, candidate, candidate),
                        leases(
                                priorityTrace,
                                PersistenceResult.applied(lease)),
                        starts(
                                priorityTrace,
                                PersistenceResult.found(persisted)))
                        .recover(request(ready, attempt)));
        assertEquals(
                ExecutionStartRecoveryStage.POST_START_INSPECT,
                priority.stage());
        assertEquals(
                ExecutionStartRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                priority.code());
    }

    @Test
    void malformedPostInspectionWinsOverEveryBrokenAdapterWithExactTrace() {
        for (boolean authorityMismatch : List.of(false, true)) {
            for (MalformedInspectionCategory category
                    : MalformedInspectionCategory.values()) {
                String suffix = "priority-acquire-"
                        + authorityMismatch + "-" + category;
                var ready = ready(suffix);
                var wrong = ready(suffix + "-wrong");
                var attempt = attempt(suffix);
                var proposal = materialized(ready, attempt);
                var lease = lease(ready, attempt, 1);
                Object brokenAcquire = authorityMismatch
                        ? PersistenceResult.applied(new LeaseRecord(
                                lease.planId(),
                                lease.ownerId(),
                                lease.leaseToken() + "-wrong",
                                lease.fencingToken(),
                                lease.acquiredAt(),
                                lease.expiresAt()))
                        : PersistenceResult.found(lease);
                RuntimeException inspectionFailure =
                        category == MalformedInspectionCategory.THROW
                        ? polluted(new SecondCollaboratorFailure(
                                "SECRET-priority-acquire-inspect"))
                        : null;
                Object malformed = malformedInspectionResult(
                        category, ready, wrong, inspectionFailure);
                var trace = new ArrayList<String>();
                var inspector = inspector(
                        trace,
                        PersistenceResult.found(ready),
                        malformed);
                var protocol = assertProtocol(
                        ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                        malformedInspectionCode(category),
                        malformedInspectionPath(
                                ExecutionStartRecoveryStage
                                        .POST_LEASE_INSPECT,
                                category),
                        authorityMismatch
                                ? ExecutionStartRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY
                                : ExecutionStartRecoveryLeaseDisposition
                                        .ACQUISITION_INDETERMINATE,
                        () -> recoverer(
                                inspector,
                                materializer(trace, proposal),
                                leases(trace, brokenAcquire),
                                starts(trace, NULL))
                                .recover(request(ready, attempt)));
                assertEquals(List.of("I1", "P1", "A", "I2"), trace);
                assertEquals(2, inspector.calls.get());
                if (inspectionFailure != null) {
                    assertSanitized(
                            protocol,
                            "SECRET-priority-acquire-inspect",
                            inspectionFailure);
                }
            }
        }

        for (boolean authorityMismatch : List.of(false, true)) {
            for (MalformedInspectionCategory category
                    : MalformedInspectionCategory.values()) {
                String suffix = "priority-start-"
                        + authorityMismatch + "-" + category;
                var ready = ready(suffix);
                var wrong = ready(suffix + "-wrong");
                var attempt = attempt(suffix);
                var proposal = materialized(ready, attempt);
                var lease = lease(ready, attempt, 1);
                var persisted = persisted(ready, lease, proposal);
                Object brokenStart = authorityMismatch
                        ? PersistenceResult.applied(
                                new PersistedExecutionStart(
                                        persisted.planId(),
                                        persisted.leaseOwnerId() + "-wrong",
                                        persisted.fencingToken(),
                                        persisted.startEvent(),
                                        persisted.startedCheckpoint()))
                        : PersistenceResult.found(persisted);
                RuntimeException inspectionFailure =
                        category == MalformedInspectionCategory.THROW
                        ? polluted(new SecondCollaboratorFailure(
                                "SECRET-priority-start-inspect"))
                        : null;
                Object malformed = malformedInspectionResult(
                        category, ready, wrong, inspectionFailure);
                var trace = new ArrayList<String>();
                var inspector = inspector(
                        trace,
                        PersistenceResult.found(ready),
                        PersistenceResult.found(ready),
                        malformed);
                var protocol = assertProtocol(
                        ExecutionStartRecoveryStage.POST_START_INSPECT,
                        malformedInspectionCode(category),
                        malformedInspectionPath(
                                ExecutionStartRecoveryStage
                                        .POST_START_INSPECT,
                                category),
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY,
                        () -> recoverer(
                                inspector,
                                materializer(
                                        trace, proposal, proposal),
                                leases(
                                        trace,
                                        PersistenceResult.applied(lease)),
                                starts(trace, brokenStart))
                                .recover(request(ready, attempt)));
                assertEquals(
                        List.of(
                                "I1", "P1", "A", "I2",
                                "P2", "S", "I3"),
                        trace);
                assertEquals(3, inspector.calls.get());
                if (inspectionFailure != null) {
                    assertSanitized(
                            protocol,
                            "SECRET-priority-start-inspect",
                            inspectionFailure);
                }
            }
        }
    }

    @Test
    void everyBrokenAuthorityDimensionStillRunsMandatoryPostInspect() {
        var ready = ready("broken-dimensions");
        var attempt = attempt("broken-dimensions");
        var proposal = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, proposal);
        var committed = committed(ready, persisted);
        var otherReady = ready("broken-dimensions-other");
        var otherAttempt = attempt("broken-dimensions-other");
        var otherProposal = materialized(otherReady, otherAttempt);

        List<LeaseRecord> badLeases = List.of(
                new LeaseRecord(
                        otherReady.planId(),
                        lease.ownerId(),
                        lease.leaseToken(),
                        lease.fencingToken(),
                        lease.acquiredAt(),
                        lease.expiresAt()),
                new LeaseRecord(
                        lease.planId(),
                        "wrong-owner",
                        lease.leaseToken(),
                        lease.fencingToken(),
                        lease.acquiredAt(),
                        lease.expiresAt()),
                new LeaseRecord(
                        lease.planId(),
                        lease.ownerId(),
                        "wrong-token",
                        lease.fencingToken(),
                        lease.acquiredAt(),
                        lease.expiresAt()),
                new LeaseRecord(
                        lease.planId(),
                        lease.ownerId(),
                        lease.leaseToken(),
                        lease.fencingToken(),
                        lease.acquiredAt(),
                        lease.expiresAt().plusSeconds(1)));
        for (int index = 0; index < badLeases.size(); index++) {
            LeaseRecord badLease = badLeases.get(index);
            PersistenceResult<LeaseRecord> badResult = index % 2 == 0
                    ? PersistenceResult.applied(badLease)
                    : PersistenceResult.replayed(badLease);
            var trace = new ArrayList<String>();
            var inspector = inspector(
                    trace,
                    PersistenceResult.found(ready),
                    PersistenceResult.found(committed));
            assertProtocol(
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_LEASE_AUTHORITY,
                    "executionStartRecovery.leaseAcquireResult.value",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    () -> recoverer(
                            inspector,
                            materializer(trace, proposal),
                            leases(trace, badResult),
                            starts(trace, NULL))
                            .recover(request(ready, attempt)));
            assertEquals(List.of("I1", "P1", "A", "I2"), trace);
            assertEquals(2, inspector.calls.get());
        }

        List<PersistedExecutionStart> badStarts = List.of(
                new PersistedExecutionStart(
                        otherReady.planId(),
                        persisted.leaseOwnerId(),
                        persisted.fencingToken(),
                        persisted.startEvent(),
                        persisted.startedCheckpoint()),
                new PersistedExecutionStart(
                        persisted.planId(),
                        "wrong-owner",
                        persisted.fencingToken(),
                        persisted.startEvent(),
                        persisted.startedCheckpoint()),
                new PersistedExecutionStart(
                        persisted.planId(),
                        persisted.leaseOwnerId(),
                        persisted.fencingToken() + 1,
                        persisted.startEvent(),
                        persisted.startedCheckpoint()),
                new PersistedExecutionStart(
                        persisted.planId(),
                        persisted.leaseOwnerId(),
                        persisted.fencingToken(),
                        otherProposal.startEvent(),
                        persisted.startedCheckpoint()),
                new PersistedExecutionStart(
                        persisted.planId(),
                        persisted.leaseOwnerId(),
                        persisted.fencingToken(),
                        persisted.startEvent(),
                        new VersionedCheckpoint(
                                2,
                                otherProposal.startedCheckpoint())));
        for (int index = 0; index < badStarts.size(); index++) {
            PersistedExecutionStart badStart = badStarts.get(index);
            PersistenceResult<PersistedExecutionStart> badResult =
                    index % 2 == 0
                            ? PersistenceResult.applied(badStart)
                            : PersistenceResult.replayed(badStart);
            var trace = new ArrayList<String>();
            var inspector = inspector(
                    trace,
                    PersistenceResult.found(ready),
                    PersistenceResult.found(ready),
                    PersistenceResult.found(committed));
            assertProtocol(
                    ExecutionStartRecoveryStage.ATOMIC_START,
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_EXECUTION_START_RESULT,
                    "executionStartRecovery.executionStartResult.value",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    () -> recoverer(
                            inspector,
                            materializer(trace, proposal, proposal),
                            leases(
                                    trace,
                                    PersistenceResult.applied(lease)),
                            starts(trace, badResult))
                            .recover(request(ready, attempt)));
            assertEquals(
                    List.of("I1", "P1", "A", "I2", "P2", "S", "I3"),
                    trace);
            assertEquals(3, inspector.calls.get());
        }
    }

    @Test
    void inspectionMalformedResultsHaveExactStageCodePathAndDisposition() {
        var ready = ready("inspect-malformed");
        var attempt = attempt("inspect-malformed");
        var candidate = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);

        assertProtocol(
                ExecutionStartRecoveryStage.INITIAL_INSPECT,
                ExecutionStartRecoveryProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "executionStartRecovery.initialInspectResult.outcome",
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                () -> recoverWithInitial(
                        request(ready, attempt),
                        PersistenceResult.applied(ready)));
        assertProtocol(
                ExecutionStartRecoveryStage.INITIAL_INSPECT,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_INSPECTION_RESULT,
                "executionStartRecovery.initialInspectResult.failure",
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                () -> recoverWithInitial(
                        request(ready, attempt),
                        PersistenceResult.rejected(
                                PersistenceErrorCode.NOT_FOUND,
                                "executionRecovery")));

        var wrong = ready("inspect-wrong");
        assertProtocol(
                ExecutionStartRecoveryStage.INITIAL_INSPECT,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_INSPECTION_RESULT,
                "executionStartRecovery.initialInspectResult.value",
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION,
                () -> recoverWithInitial(
                        request(ready, attempt),
                        PersistenceResult.found(wrong)));

        var trace = new ArrayList<String>();
        var exception = new IllegalStateException("SECRET-inspect");
        var protocol = assertProtocol(
                ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.postLeaseInspectResult",
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                () -> recoverer(
                        inspector(
                                trace,
                                PersistenceResult.found(ready),
                                exception),
                        materializer(trace, candidate),
                        leases(
                                trace,
                                PersistenceResult.applied(lease)),
                        starts(trace, NULL))
                        .recover(request(ready, attempt)));
        assertSanitized(protocol, "SECRET-inspect", exception);
    }

    @Test
    void everyInspectionStageRejectsEveryMalformedResultPrecisely() {
        for (ExecutionStartRecoveryStage stage : List.of(
                ExecutionStartRecoveryStage.INITIAL_INSPECT,
                ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                ExecutionStartRecoveryStage.POST_START_INSPECT)) {
            for (MalformedInspectionCategory category
                    : MalformedInspectionCategory.values()) {
                String suffix = "malformed-" + stage + "-" + category;
                var ready = ready(suffix);
                var wrong = ready(suffix + "-wrong");
                var attempt = attempt(suffix);
                var proposal = materialized(ready, attempt);
                var lease = lease(ready, attempt, 1);
                var persisted = persisted(ready, lease, proposal);
                RuntimeException thrown =
                        category == MalformedInspectionCategory.THROW
                                ? polluted(new InspectionPollutedException(
                                        "SECRET-inspection-" + stage))
                                : null;
                Object malformed = malformedInspectionResult(
                        category, ready, wrong, thrown);
                List<Object> inspections = new ArrayList<>();
                if (stage
                        != ExecutionStartRecoveryStage.INITIAL_INSPECT) {
                    inspections.add(PersistenceResult.found(ready));
                }
                if (stage
                        == ExecutionStartRecoveryStage.POST_START_INSPECT) {
                    inspections.add(PersistenceResult.found(ready));
                }
                inspections.add(malformed);
                var trace = new ArrayList<String>();
                var inspector =
                        new ExecutionStartRecoveryTestFixtures
                                .ScriptedRecoveryRepository(
                                        inspections, trace);
                Object[] proposals = switch (stage) {
                    case INITIAL_INSPECT -> new Object[] {};
                    case POST_LEASE_INSPECT ->
                            new Object[] {proposal};
                    case POST_START_INSPECT ->
                            new Object[] {proposal, proposal};
                    default -> throw new AssertionError(stage);
                };

                var protocol = assertProtocol(
                        stage,
                        malformedInspectionCode(category),
                        malformedInspectionPath(stage, category),
                        stage == ExecutionStartRecoveryStage.INITIAL_INSPECT
                                ? ExecutionStartRecoveryLeaseDisposition
                                        .NO_LEASE_ACTION
                                : ExecutionStartRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY,
                        () -> recoverer(
                                inspector,
                                materializer(trace, proposals),
                                leases(
                                        trace,
                                        PersistenceResult.applied(lease)),
                                starts(
                                        trace,
                                        PersistenceResult.applied(
                                                persisted)))
                                .recover(request(ready, attempt)));
                assertEquals(
                        switch (stage) {
                            case INITIAL_INSPECT -> List.of("I1");
                            case POST_LEASE_INSPECT ->
                                    List.of("I1", "P1", "A", "I2");
                            case POST_START_INSPECT ->
                                    List.of(
                                            "I1", "P1", "A", "I2",
                                            "P2", "S", "I3");
                            default -> throw new AssertionError(stage);
                        },
                        trace);
                if (thrown != null) {
                    assertSanitized(
                            protocol,
                            "SECRET-inspection-" + stage,
                            thrown);
                }
            }
        }
    }

    @Test
    void laterInspectionFailureWinsAndOnlyItsTypeSurvivesSanitization() {
        var ready = ready("double-failure");
        var attempt = attempt("double-failure");
        var proposal = materialized(ready, attempt);
        var lease = lease(ready, attempt, 1);
        var persisted = persisted(ready, lease, proposal);
        var committed = committed(ready, persisted);

        var acquireFailure = polluted(
                new FirstCollaboratorFailure("SECRET-first-acquire"));
        var directAcquireTrace = new ArrayList<String>();
        var directAcquire = assertProtocol(
                ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.leaseAcquireResult",
                ExecutionStartRecoveryLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                () -> recoverer(
                        inspector(
                                directAcquireTrace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(ready)),
                        materializer(directAcquireTrace, proposal),
                        leases(directAcquireTrace, acquireFailure),
                        starts(directAcquireTrace, NULL))
                        .recover(request(ready, attempt)));
        assertEquals(
                List.of("I1", "P1", "A", "I2"),
                directAcquireTrace);
        assertSanitized(
                directAcquire,
                "SECRET-first-acquire",
                acquireFailure);

        var secondInspectFailure = polluted(
                new SecondCollaboratorFailure("SECRET-second-inspect"));
        var acquireTrace = new ArrayList<String>();
        var postLease = assertProtocol(
                ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.postLeaseInspectResult",
                ExecutionStartRecoveryLeaseDisposition
                        .ACQUISITION_INDETERMINATE,
                () -> recoverer(
                        inspector(
                                acquireTrace,
                                PersistenceResult.found(ready),
                                secondInspectFailure),
                        materializer(acquireTrace, proposal),
                        leases(acquireTrace, acquireFailure),
                        starts(acquireTrace, NULL))
                        .recover(request(ready, attempt)));
        assertEquals(List.of("I1", "P1", "A", "I2"), acquireTrace);
        assertSanitized(
                postLease,
                "SECRET-second-inspect",
                secondInspectFailure);
        assertFalse(postLease.getCause().getMessage()
                .contains(FirstCollaboratorFailure.class.getName()));
        assertFalse(printed(postLease)
                .contains(FirstCollaboratorFailure.class.getName()));

        var startFailure = polluted(
                new FirstCollaboratorFailure("SECRET-first-start"));
        var thirdInspectFailure = polluted(
                new SecondCollaboratorFailure("SECRET-third-inspect"));
        var startTrace = new ArrayList<String>();
        var postStart = assertProtocol(
                ExecutionStartRecoveryStage.POST_START_INSPECT,
                ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                "executionStartRecovery.postStartInspectResult",
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                () -> recoverer(
                        inspector(
                                startTrace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(ready),
                                thirdInspectFailure),
                        materializer(startTrace, proposal, proposal),
                        leases(
                                startTrace,
                                PersistenceResult.applied(lease)),
                        starts(startTrace, startFailure))
                        .recover(request(ready, attempt)));
        assertEquals(
                List.of("I1", "P1", "A", "I2", "P2", "S", "I3"),
                startTrace);
        assertSanitized(
                postStart,
                "SECRET-third-inspect",
                thirdInspectFailure);
        assertFalse(postStart.getCause().getMessage()
                .contains(FirstCollaboratorFailure.class.getName()));
        assertFalse(printed(postStart)
                .contains(FirstCollaboratorFailure.class.getName()));

        var retry = assertInstanceOf(
                ExecutionStartRecoveryRetryRequired.class,
                runStart(
                        ready,
                        attempt,
                        proposal,
                        lease,
                        startFailure,
                        PersistenceResult.found(ready))
                        .outcome());
        assertFalse(retry.toString().contains("SECRET"));

        var reconciled = assertInstanceOf(
                RecoveredExecutionStart.class,
                runStart(
                        ready,
                        attempt,
                        proposal,
                        lease,
                        startFailure,
                        PersistenceResult.found(committed))
                        .outcome());
        assertEquals(
                ExecutionStartRecoveryResolution
                        .RECONCILED_AFTER_RESPONSE_LOSS,
                reconciled.resolution());
        assertFalse(reconciled.toString().contains("SECRET"));
    }

    @Test
    void acquireCategoryBySecondInspectionMatrixIsClosed() {
        for (AcquireCategory category : AcquireCategory.values()) {
            for (InspectionState state : InspectionState.values()) {
                String suffix = "acquire-matrix-"
                        + category + "-" + state;
                var ready = ready(suffix);
                var attempt = attempt(suffix);
                var proposal = materialized(ready, attempt);
                var lease = lease(ready, attempt, 1);
                var persisted = persisted(ready, lease, proposal);
                var committed = committed(ready, persisted);
                var trace = new ArrayList<String>();
                var failure = new PersistenceFailure(
                        PersistenceErrorCode.LEASE_HELD,
                        "lease");
                Object acquire = acquireResult(
                        category, lease, failure);
                List<Object> inspections = new ArrayList<>();
                inspections.add(PersistenceResult.found(ready));
                inspections.add(inspectionResult(
                        state, ready, committed));
                boolean continues = (category == AcquireCategory.APPLIED
                        || category == AcquireCategory.REPLAYED)
                        && state == InspectionState.READY;
                if (continues) {
                    inspections.add(PersistenceResult.found(ready));
                }
                var inspector =
                        new ExecutionStartRecoveryTestFixtures
                                .ScriptedRecoveryRepository(
                                        inspections, trace);
                var materializer = materializer(
                        trace,
                        continues
                                ? new Object[] {proposal, proposal}
                                : new Object[] {proposal});

                if (category == AcquireCategory.FOUND
                        || category
                                == AcquireCategory.AUTHORITY_MISMATCH
                        || state == InspectionState.READY
                                && (category == AcquireCategory.NULL
                                        || category
                                                == AcquireCategory.THROW)) {
                    var protocol = assertThrows(
                            ExecutionStartRecoveryProtocolException.class,
                            () -> recoverer(
                                    inspector,
                                    materializer,
                                    leases(trace, acquire),
                                    starts(trace, NULL))
                                    .recover(request(ready, attempt)));
                    assertEquals(
                            ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                            protocol.stage());
                    assertEquals(
                            category == AcquireCategory.FOUND
                                    ? ExecutionStartRecoveryProtocolCode
                                            .UNEXPECTED_PERSISTENCE_OUTCOME
                                    : category
                                            == AcquireCategory
                                                    .AUTHORITY_MISMATCH
                                            ? ExecutionStartRecoveryProtocolCode
                                                    .INCONSISTENT_LEASE_AUTHORITY
                                    : category == AcquireCategory.NULL
                                            ? ExecutionStartRecoveryProtocolCode
                                                    .NULL_COLLABORATOR_RESULT
                                            : ExecutionStartRecoveryProtocolCode
                                                    .COLLABORATOR_EXCEPTION,
                            protocol.code());
                    assertEquals(
                            category == AcquireCategory.FOUND
                                    ? "executionStartRecovery"
                                            + ".leaseAcquireResult.outcome"
                                    : category
                                            == AcquireCategory
                                                    .AUTHORITY_MISMATCH
                                            ? "executionStartRecovery"
                                                    + ".leaseAcquireResult"
                                                    + ".value"
                                    : "executionStartRecovery"
                                            + ".leaseAcquireResult",
                            protocol.path());
                    assertEquals(
                            category
                                    == AcquireCategory.AUTHORITY_MISMATCH
                                    ? ExecutionStartRecoveryLeaseDisposition
                                            .RETAINED_FOR_RECOVERY
                                    : ExecutionStartRecoveryLeaseDisposition
                                            .ACQUISITION_INDETERMINATE,
                            protocol.leaseDisposition());
                } else {
                    ExecutionStartRecoveryOutcome outcome = recoverer(
                            inspector,
                            materializer,
                            leases(trace, acquire),
                            starts(trace, NULL))
                            .recover(request(ready, attempt));
                    if (continues) {
                        assertInstanceOf(
                                ExecutionStartRecoveryRetryRequired.class,
                                outcome);
                    } else if (state == InspectionState.READY) {
                        var rejected = assertInstanceOf(
                                ExecutionStartRecoveryRejected.class,
                                outcome);
                        assertEquals(
                                ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                                rejected.stage());
                        assertSame(failure, rejected.failure());
                        assertEquals(
                                ExecutionStartRecoveryLeaseDisposition
                                        .NOT_ACQUIRED,
                                rejected.leaseDisposition());
                    } else {
                        assertInspectionStateOutcome(
                                outcome,
                                state,
                                ExecutionStartRecoveryStage
                                        .POST_LEASE_INSPECT,
                                acquireDisposition(category));
                    }
                }
                assertEquals(
                        continues
                                ? List.of(
                                        "I1", "P1", "A", "I2",
                                        "P2", "S", "I3")
                                : List.of("I1", "P1", "A", "I2"),
                        trace);
                assertEquals(continues ? 3 : 2, inspector.calls.get());
            }
        }
    }

    @Test
    void startCategoryByThirdInspectionMatrixIsClosed() {
        for (StartCategory category : StartCategory.values()) {
            for (InspectionState state : InspectionState.values()) {
                String suffix = "start-matrix-"
                        + category + "-" + state;
                var ready = ready(suffix);
                var attempt = attempt(suffix);
                var proposal = materialized(ready, attempt);
                var lease = lease(ready, attempt, 1);
                var persisted = persisted(ready, lease, proposal);
                var committed = committed(ready, persisted);
                var failure = new PersistenceFailure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "executionStart");
                Object start = startResult(
                        category, persisted, failure);
                Object third = inspectionResult(
                        state, ready, committed);
                var trace = new ArrayList<String>();
                var inspector = inspector(
                        trace,
                        PersistenceResult.found(ready),
                        PersistenceResult.found(ready),
                        third);

                if (category == StartCategory.FOUND
                        || category == StartCategory.AUTHORITY_MISMATCH
                        || (category == StartCategory.APPLIED
                                || category == StartCategory.REPLAYED)
                                && state == InspectionState.READY) {
                    var protocol = assertThrows(
                            ExecutionStartRecoveryProtocolException.class,
                            () -> recoverer(
                                    inspector,
                                    materializer(
                                            trace, proposal, proposal),
                                    leases(
                                            trace,
                                            PersistenceResult.applied(
                                                    lease)),
                                    starts(trace, start))
                                    .recover(request(ready, attempt)));
                    assertEquals(
                            category == StartCategory.FOUND
                                    || category
                                            == StartCategory
                                                    .AUTHORITY_MISMATCH
                                    ? ExecutionStartRecoveryStage.ATOMIC_START
                                    : ExecutionStartRecoveryStage
                                            .POST_START_INSPECT,
                            protocol.stage());
                    assertEquals(
                            category == StartCategory.FOUND
                                    ? ExecutionStartRecoveryProtocolCode
                                            .UNEXPECTED_PERSISTENCE_OUTCOME
                                    : category
                                            == StartCategory
                                                    .AUTHORITY_MISMATCH
                                            ? ExecutionStartRecoveryProtocolCode
                                                    .INCONSISTENT_EXECUTION_START_RESULT
                                    : ExecutionStartRecoveryProtocolCode
                                            .INCONSISTENT_INSPECTION_RESULT,
                            protocol.code());
                    assertEquals(
                            category == StartCategory.FOUND
                                    ? "executionStartRecovery"
                                            + ".executionStartResult.outcome"
                                    : category
                                            == StartCategory
                                                    .AUTHORITY_MISMATCH
                                            ? "executionStartRecovery"
                                                    + ".executionStartResult"
                                                    + ".value"
                                    : "executionStartRecovery"
                                            + ".postStartInspectResult.value",
                            protocol.path());
                    assertEquals(
                            ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY,
                            protocol.leaseDisposition());
                } else {
                    ExecutionStartRecoveryOutcome outcome = recoverer(
                            inspector,
                            materializer(trace, proposal, proposal),
                            leases(
                                    trace,
                                    PersistenceResult.applied(lease)),
                            starts(trace, start))
                            .recover(request(ready, attempt));
                    if (state == InspectionState.COMMITTED) {
                        var recovered = assertInstanceOf(
                                RecoveredExecutionStart.class,
                                outcome);
                        ExecutionStartRecoveryResolution expected =
                                switch (category) {
                                    case APPLIED ->
                                            ExecutionStartRecoveryResolution
                                                    .ATOMIC_START_APPLIED;
                                    case REPLAYED ->
                                            ExecutionStartRecoveryResolution
                                                    .ATOMIC_START_REPLAYED;
                                    case REJECTED ->
                                            ExecutionStartRecoveryResolution
                                                    .OBSERVED_COMMITTED;
                                    case NULL, THROW ->
                                            ExecutionStartRecoveryResolution
                                                    .RECONCILED_AFTER_RESPONSE_LOSS;
                                    case FOUND, AUTHORITY_MISMATCH ->
                                            throw new AssertionError();
                                };
                        assertEquals(expected, recovered.resolution());
                        assertSame(persisted, recovered.persistedStart());
                    } else if (state == InspectionState.READY) {
                        if (category == StartCategory.REJECTED) {
                            var rejected = assertInstanceOf(
                                    ExecutionStartRecoveryRejected.class,
                                    outcome);
                            assertEquals(
                                    ExecutionStartRecoveryStage
                                            .ATOMIC_START,
                                    rejected.stage());
                            assertSame(failure, rejected.failure());
                        } else {
                            assertInstanceOf(
                                    ExecutionStartRecoveryRetryRequired.class,
                                    outcome);
                        }
                    } else {
                        assertInspectionStateOutcome(
                                outcome,
                                state,
                                ExecutionStartRecoveryStage
                                        .POST_START_INSPECT,
                                ExecutionStartRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY);
                    }
                }
                assertEquals(
                        List.of(
                                "I1", "P1", "A", "I2",
                                "P2", "S", "I3"),
                        trace);
                assertEquals(3, inspector.calls.get());
            }
        }
    }

    private static ExecutionStartRecoveryOutcome recoverWithInitial(
            ExecutionStartRecoveryRequest request,
            Object inspection) {
        var trace = new ArrayList<String>();
        return recoverer(
                inspector(trace, inspection),
                materializer(trace),
                leases(trace, NULL),
                starts(trace, NULL))
                .recover(request);
    }

    private static Object malformedInspectionResult(
            MalformedInspectionCategory category,
            PersistedExecutionStartReady ready,
            PersistedExecutionStartReady wrong,
            RuntimeException thrown) {
        return switch (category) {
            case NULL -> NULL;
            case THROW -> thrown;
            case APPLIED -> PersistenceResult.applied(ready);
            case REPLAYED -> PersistenceResult.replayed(ready);
            case WRONG_PLAN -> PersistenceResult.found(wrong);
            case WRONG_FAILURE -> PersistenceResult.rejected(
                    PersistenceErrorCode.NOT_FOUND,
                    "executionRecovery");
        };
    }

    private static ExecutionStartRecoveryProtocolCode
            malformedInspectionCode(
                    MalformedInspectionCategory category) {
        return switch (category) {
            case NULL ->
                    ExecutionStartRecoveryProtocolCode
                            .NULL_COLLABORATOR_RESULT;
            case THROW ->
                    ExecutionStartRecoveryProtocolCode
                            .COLLABORATOR_EXCEPTION;
            case APPLIED, REPLAYED ->
                    ExecutionStartRecoveryProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME;
            case WRONG_PLAN, WRONG_FAILURE ->
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_INSPECTION_RESULT;
        };
    }

    private static String malformedInspectionPath(
            ExecutionStartRecoveryStage stage,
            MalformedInspectionCategory category) {
        String root = switch (stage) {
            case INITIAL_INSPECT ->
                    "executionStartRecovery.initialInspectResult";
            case POST_LEASE_INSPECT ->
                    "executionStartRecovery.postLeaseInspectResult";
            case POST_START_INSPECT ->
                    "executionStartRecovery.postStartInspectResult";
            default -> throw new AssertionError(stage);
        };
        return switch (category) {
            case NULL, THROW -> root;
            case APPLIED, REPLAYED -> root + ".outcome";
            case WRONG_PLAN -> root + ".value";
            case WRONG_FAILURE -> root + ".failure";
        };
    }

    private static Object acquireResult(
            AcquireCategory category,
            LeaseRecord lease,
            PersistenceFailure failure) {
        return switch (category) {
            case APPLIED -> PersistenceResult.applied(lease);
            case REPLAYED -> PersistenceResult.replayed(lease);
            case REJECTED -> new PersistenceResult<LeaseRecord>(
                    PersistenceOutcome.REJECTED,
                    Optional.empty(),
                    Optional.of(failure));
            case AUTHORITY_MISMATCH ->
                    PersistenceResult.applied(new LeaseRecord(
                            lease.planId(),
                            lease.ownerId(),
                            lease.leaseToken() + "-wrong",
                            lease.fencingToken(),
                            lease.acquiredAt(),
                            lease.expiresAt()));
            case NULL -> NULL;
            case THROW -> new IllegalStateException("SECRET-acquire-matrix");
            case FOUND -> PersistenceResult.found(lease);
        };
    }

    private static Object startResult(
            StartCategory category,
            PersistedExecutionStart persisted,
            PersistenceFailure failure) {
        return switch (category) {
            case APPLIED -> PersistenceResult.applied(persisted);
            case REPLAYED -> PersistenceResult.replayed(persisted);
            case REJECTED ->
                    new PersistenceResult<PersistedExecutionStart>(
                            PersistenceOutcome.REJECTED,
                            Optional.empty(),
                            Optional.of(failure));
            case AUTHORITY_MISMATCH ->
                    PersistenceResult.applied(
                            new PersistedExecutionStart(
                                    persisted.planId(),
                                    persisted.leaseOwnerId() + "-wrong",
                                    persisted.fencingToken(),
                                    persisted.startEvent(),
                                    persisted.startedCheckpoint()));
            case NULL -> NULL;
            case THROW -> new IllegalStateException("SECRET-start-matrix");
            case FOUND -> PersistenceResult.found(persisted);
        };
    }

    private static Object inspectionResult(
            InspectionState state,
            PersistedExecutionStartReady ready,
            PersistedExecutionStartCommitted committed) {
        return switch (state) {
            case READY -> PersistenceResult.found(ready);
            case COMMITTED -> PersistenceResult.found(committed);
            case ADVANCED -> PersistenceResult.rejected(
                    PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                    "executionRecovery");
            case PARTIAL -> PersistenceResult.rejected(
                    PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                    "executionRecovery");
            case NOT_FOUND -> PersistenceResult.rejected(
                    PersistenceErrorCode.NOT_FOUND,
                    "planId");
        };
    }

    private static ExecutionStartRecoveryLeaseDisposition
            acquireDisposition(AcquireCategory category) {
        return switch (category) {
            case APPLIED, REPLAYED ->
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY;
            case REJECTED ->
                    ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED;
            case AUTHORITY_MISMATCH ->
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY;
            case NULL, THROW, FOUND ->
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE;
        };
    }

    private static void assertInspectionStateOutcome(
            ExecutionStartRecoveryOutcome outcome,
            InspectionState state,
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryLeaseDisposition disposition) {
        if (state == InspectionState.COMMITTED) {
            var recovered = assertInstanceOf(
                    RecoveredExecutionStart.class, outcome);
            assertEquals(
                    ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                    recovered.resolution());
            assertEquals(disposition, recovered.leaseDisposition());
            return;
        }
        if (state == InspectionState.ADVANCED) {
            var advanced = assertInstanceOf(
                    ExecutionStartRecoveryAdvancedUnsupported.class,
                    outcome);
            assertEquals(stage, advanced.stage());
            assertEquals(
                    PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                    advanced.failure().code());
            assertEquals("executionRecovery", advanced.failure().path());
            assertEquals(disposition, advanced.leaseDisposition());
            return;
        }
        var rejected = assertInstanceOf(
                ExecutionStartRecoveryRejected.class, outcome);
        assertEquals(stage, rejected.stage());
        assertEquals(
                state == InspectionState.NOT_FOUND
                        ? PersistenceErrorCode.NOT_FOUND
                        : PersistenceErrorCode
                                .EXECUTION_RECOVERY_PARTIAL_STATE,
                rejected.failure().code());
        assertEquals(
                state == InspectionState.NOT_FOUND
                        ? "planId"
                        : "executionRecovery",
                rejected.failure().path());
        assertEquals(disposition, rejected.leaseDisposition());
    }

    private static ExecutionStartRecoveryOutcome recoverWithP1(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            Object p1) {
        var trace = new ArrayList<String>();
        return recoverer(
                inspector(trace, PersistenceResult.found(ready)),
                materializer(trace, p1),
                leases(trace, NULL),
                starts(trace, NULL))
                .recover(request(ready, attempt));
    }

    private static StartRun runStart(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            MaterializedExecutionStart candidate,
            LeaseRecord lease,
            Object start,
            Object thirdInspection) {
        var trace = new ArrayList<String>();
        var outcome = recoverer(
                inspector(
                        trace,
                        PersistenceResult.found(ready),
                        PersistenceResult.found(ready),
                        thirdInspection),
                materializer(trace, candidate, candidate),
                leases(trace, PersistenceResult.applied(lease)),
                starts(trace, start))
                .recover(request(ready, attempt));
        return new StartRun(outcome, trace);
    }

    private static DefaultExecutionStartRecoverer recoverer(
            ExecutionStartRecoveryTestFixtures.ScriptedRecoveryRepository
                    inspector,
            ExecutionStartRecoveryTestFixtures.ScriptedMaterializer
                    materializer,
            ExecutionStartRecoveryTestFixtures.ScriptedLeaseRepository leases,
            ExecutionStartRecoveryTestFixtures.ScriptedStartRepository starts) {
        return new DefaultExecutionStartRecoverer(
                inspector, materializer, leases, starts);
    }

    private static ExecutionStartRecoveryTestFixtures
            .ScriptedRecoveryRepository inspector(
                    List<String> trace,
                    Object... results) {
        return new ExecutionStartRecoveryTestFixtures
                .ScriptedRecoveryRepository(List.of(results), trace);
    }

    private static ExecutionStartRecoveryTestFixtures.ScriptedMaterializer
            materializer(List<String> trace, Object... results) {
        return new ExecutionStartRecoveryTestFixtures.ScriptedMaterializer(
                List.of(results), trace);
    }

    private static ExecutionStartRecoveryTestFixtures.ScriptedLeaseRepository
            leases(List<String> trace, Object result) {
        return new ExecutionStartRecoveryTestFixtures.ScriptedLeaseRepository(
                result, trace);
    }

    private static ExecutionStartRecoveryTestFixtures.ScriptedStartRepository
            starts(List<String> trace, Object result) {
        return new ExecutionStartRecoveryTestFixtures.ScriptedStartRepository(
                result, trace);
    }

    private static ExecutionStartRecoveryProtocolException assertP2Failure(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            MaterializedExecutionStart p1,
            LeaseRecord lease,
            Object p2,
            ExecutionStartRecoveryProtocolCode code,
            String path,
            RuntimeException original) {
        var trace = new ArrayList<String>();
        var starts = starts(trace, NULL);
        var protocol = assertProtocol(
                ExecutionStartRecoveryStage.POST_LEASE_MATERIALIZE,
                code,
                path,
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY,
                () -> recoverer(
                        inspector(
                                trace,
                                PersistenceResult.found(ready),
                                PersistenceResult.found(ready)),
                        materializer(trace, p1, p2),
                        leases(trace, PersistenceResult.applied(lease)),
                        starts)
                        .recover(request(ready, attempt)));
        assertEquals(List.of("I1", "P1", "A", "I2", "P2"), trace);
        assertEquals(0, starts.calls.get());
        if (original == null) {
            assertEquals(null, protocol.getCause());
        }
        return protocol;
    }

    private static boolean validRejectedCombination(
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryLeaseDisposition disposition,
            PersistenceFailure failure) {
        boolean inspectionFailure = isNotFoundOrPartial(failure);
        return switch (stage) {
            case INITIAL_INSPECT ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .NO_LEASE_ACTION
                            && inspectionFailure;
            case LEASE_ACQUIRE ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .NOT_ACQUIRED;
            case POST_LEASE_INSPECT ->
                    disposition
                            != ExecutionStartRecoveryLeaseDisposition
                                    .NO_LEASE_ACTION
                            && inspectionFailure;
            case ATOMIC_START ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY;
            case POST_START_INSPECT ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY
                            && inspectionFailure;
            case MATERIALIZE, POST_LEASE_MATERIALIZE -> false;
        };
    }

    private static List<PersistenceFailure> allPersistenceFailures() {
        List<PersistenceFailure> failures = new ArrayList<>();
        for (PersistenceErrorCode code : PersistenceErrorCode.values()) {
            for (String path : List.of(
                    "planId",
                    "executionRecovery",
                    "wrong")) {
                failures.add(new PersistenceFailure(code, path));
            }
        }
        return List.copyOf(failures);
    }

    private static String invalidRejectedPath(
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryLeaseDisposition disposition,
            PersistenceFailure failure) {
        return switch (stage) {
            case MATERIALIZE, POST_LEASE_MATERIALIZE ->
                    "executionStartRecoveryRejected.stage";
            case INITIAL_INSPECT ->
                    disposition
                            != ExecutionStartRecoveryLeaseDisposition
                                    .NO_LEASE_ACTION
                            ? "executionStartRecoveryRejected"
                                    + ".leaseDisposition"
                            : "executionStartRecoveryRejected.failure";
            case LEASE_ACQUIRE ->
                    "executionStartRecoveryRejected.leaseDisposition";
            case POST_LEASE_INSPECT ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .NO_LEASE_ACTION
                            ? "executionStartRecoveryRejected"
                                    + ".leaseDisposition"
                            : "executionStartRecoveryRejected.failure";
            case ATOMIC_START ->
                    "executionStartRecoveryRejected.leaseDisposition";
            case POST_START_INSPECT ->
                    disposition
                            != ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY
                            ? "executionStartRecoveryRejected"
                                    + ".leaseDisposition"
                            : "executionStartRecoveryRejected.failure";
        };
    }

    private static boolean validAdvancedCombination(
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryLeaseDisposition disposition,
            PersistenceFailure failure) {
        if (!isAdvanced(failure)) {
            return false;
        }
        return switch (stage) {
            case INITIAL_INSPECT ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .NO_LEASE_ACTION;
            case POST_LEASE_INSPECT ->
                    disposition
                            != ExecutionStartRecoveryLeaseDisposition
                                    .NO_LEASE_ACTION;
            case POST_START_INSPECT ->
                    disposition
                            == ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY;
            case MATERIALIZE, LEASE_ACQUIRE, POST_LEASE_MATERIALIZE,
                    ATOMIC_START -> false;
        };
    }

    private static String invalidAdvancedPath(
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryLeaseDisposition disposition,
            PersistenceFailure failure) {
        if (!isAdvanced(failure)) {
            return "executionStartRecoveryAdvancedUnsupported.failure";
        }
        return switch (stage) {
            case MATERIALIZE, LEASE_ACQUIRE, POST_LEASE_MATERIALIZE,
                    ATOMIC_START ->
                    "executionStartRecoveryAdvancedUnsupported.stage";
            case INITIAL_INSPECT, POST_LEASE_INSPECT, POST_START_INSPECT ->
                    "executionStartRecoveryAdvancedUnsupported"
                            + ".leaseDisposition";
        };
    }

    private static boolean isNotFoundOrPartial(PersistenceFailure failure) {
        return failure.code() == PersistenceErrorCode.NOT_FOUND
                        && "planId".equals(failure.path())
                || failure.code()
                        == PersistenceErrorCode
                                .EXECUTION_RECOVERY_PARTIAL_STATE
                        && "executionRecovery".equals(failure.path());
    }

    private static boolean isAdvanced(PersistenceFailure failure) {
        return failure.code()
                        == PersistenceErrorCode
                                .EXECUTION_RECOVERY_ADVANCED_STATE
                && "executionRecovery".equals(failure.path());
    }

    private static void assertConstructorValidity(
            boolean valid,
            String invalidPath,
            Runnable action) {
        if (valid) {
            action.run();
            return;
        }
        assertValidation(
                ExecutionStartRecoveryValidationCode.INVALID_OUTCOME_STATE,
                invalidPath,
                action);
    }

    private static void assertRejectedRequiredValues(
            PlanId planId,
            PersistenceFailure failure) {
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryRejected.planId",
                () -> new ExecutionStartRecoveryRejected(
                        null,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        failure,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryRejected.stage",
                () -> new ExecutionStartRecoveryRejected(
                        planId,
                        null,
                        failure,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryRejected.failure",
                () -> new ExecutionStartRecoveryRejected(
                        planId,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        null,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryRejected.leaseDisposition",
                () -> new ExecutionStartRecoveryRejected(
                        planId,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        failure,
                        null));
    }

    private static void assertAdvancedRequiredValues(
            PlanId planId,
            PersistenceFailure failure) {
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryAdvancedUnsupported.planId",
                () -> new ExecutionStartRecoveryAdvancedUnsupported(
                        null,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        failure,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryAdvancedUnsupported.stage",
                () -> new ExecutionStartRecoveryAdvancedUnsupported(
                        planId,
                        null,
                        failure,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryAdvancedUnsupported.failure",
                () -> new ExecutionStartRecoveryAdvancedUnsupported(
                        planId,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        null,
                        ExecutionStartRecoveryLeaseDisposition
                                .NO_LEASE_ACTION));
        assertValidation(
                ExecutionStartRecoveryValidationCode.REQUIRED_VALUE_MISSING,
                "executionStartRecoveryAdvancedUnsupported.leaseDisposition",
                () -> new ExecutionStartRecoveryAdvancedUnsupported(
                        planId,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        failure,
                        null));
    }

    private static void assertValidation(
            String path,
            Runnable action) {
        var exception = assertThrows(
                ExecutionStartRecoveryValidationException.class,
                action::run);
        assertEquals(path, exception.path());
        assertFalse(exception.getMessage().contains("SECRET"));
    }

    private static void assertValidation(
            ExecutionStartRecoveryValidationCode code,
            String path,
            Runnable action) {
        var exception = assertThrows(
                ExecutionStartRecoveryValidationException.class,
                action::run);
        assertEquals(code, exception.code());
        assertEquals(path, exception.path());
        assertFalse(exception.getMessage().contains("SECRET"));
    }

    private static <T extends RuntimeException> T polluted(T exception) {
        exception.initCause(
                new IllegalArgumentException("SECRET-nested-cause"));
        exception.addSuppressed(
                new IllegalStateException("SECRET-suppressed"));
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(
                        "SECRET-class",
                        "SECRET-method",
                        "SECRET-file",
                        1)
        });
        return exception;
    }

    private static ExecutionStartRecoveryProtocolException assertProtocol(
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryProtocolCode code,
            String path,
            ExecutionStartRecoveryLeaseDisposition disposition,
            Runnable action) {
        var exception = assertThrows(
                ExecutionStartRecoveryProtocolException.class,
                action::run);
        assertEquals(stage, exception.stage());
        assertEquals(code, exception.code());
        assertEquals(path, exception.path());
        assertEquals(disposition, exception.leaseDisposition());
        return exception;
    }

    private static void assertSanitized(
            ExecutionStartRecoveryProtocolException protocol,
            String secret,
            RuntimeException original) {
        assertFalse(protocol.getMessage().contains(secret));
        assertFalse(printed(protocol).contains("SECRET"));
        assertEquals(0, protocol.getSuppressed().length);
        assertNotSame(original, protocol.getCause());
        assertTrue(protocol.getCause().getMessage()
                .contains(original.getClass().getName()));
        assertFalse(protocol.getCause().getMessage().contains("SECRET"));
        assertEquals(null, protocol.getCause().getCause());
        assertEquals(0, protocol.getCause().getSuppressed().length);
        assertEquals(0, protocol.getCause().getStackTrace().length);
    }

    private static String printed(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private record StartRun(
            ExecutionStartRecoveryOutcome outcome,
            List<String> trace) {
    }

    private enum AcquireCategory {
        APPLIED,
        REPLAYED,
        REJECTED,
        AUTHORITY_MISMATCH,
        NULL,
        THROW,
        FOUND
    }

    private enum StartCategory {
        APPLIED,
        REPLAYED,
        REJECTED,
        AUTHORITY_MISMATCH,
        NULL,
        THROW,
        FOUND
    }

    private enum InspectionState {
        READY,
        COMMITTED,
        ADVANCED,
        PARTIAL,
        NOT_FOUND
    }

    private enum MalformedInspectionCategory {
        NULL,
        THROW,
        APPLIED,
        REPLAYED,
        WRONG_PLAN,
        WRONG_FAILURE
    }

    private static final class InspectionPollutedException
            extends IllegalStateException {
        private InspectionPollutedException(String message) {
            super(message);
        }
    }

    private static final class FirstCollaboratorFailure
            extends IllegalStateException {
        private FirstCollaboratorFailure(String message) {
            super(message);
        }
    }

    private static final class SecondCollaboratorFailure
            extends IllegalStateException {
        private SecondCollaboratorFailure(String message) {
            super(message);
        }
    }
}
