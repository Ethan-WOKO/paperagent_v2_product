package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.BootstrapRejected;
import io.paperagent.v2.runtime.execution.ExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.FreshExecutionDecision;
import io.paperagent.v2.runtime.execution.FreshExecutionGate;
import io.paperagent.v2.runtime.execution.FreshLeaseAdmissionEligible;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.RecoveryRequired;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.runtime.execution.start.FreshExecutionStartTestFixtures.OWNER;
import static io.paperagent.v2.runtime.execution.start.FreshExecutionStartTestFixtures.T0;
import static io.paperagent.v2.runtime.execution.start.FreshExecutionStartTestFixtures.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFreshExecutionStarterTest {

    @Test
    void starterConstructorAndNullRequestUseStablePaths() {
        FreshExecutionGate gate = result -> null;
        ExecutionStartMaterializer materializer = request -> null;
        var leases =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) -> null);
        var starts =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> null);

        assertValidation(
                () -> new DefaultFreshExecutionStarter(
                        null,
                        materializer,
                        leases,
                        starts),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStart.freshExecutionGate");
        assertValidation(
                () -> new DefaultFreshExecutionStarter(
                        gate,
                        null,
                        leases,
                        starts),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStart.executionStartMaterializer");
        assertValidation(
                () -> new DefaultFreshExecutionStarter(
                        gate,
                        materializer,
                        null,
                        starts),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStart.leaseRepository");
        assertValidation(
                () -> new DefaultFreshExecutionStarter(
                        gate,
                        materializer,
                        leases,
                        null),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStart.executionStartRepository");
        DefaultFreshExecutionStarter starter =
                new DefaultFreshExecutionStarter(
                        gate,
                        materializer,
                        leases,
                        starts);
        assertValidation(
                () -> starter.start(null),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStart.request");
    }

    @Test
    void recordsValidateStablePathsAndRedactAttemptToken() {
        FreshExecutionStartAttempt valid =
                FreshExecutionStartTestFixtures.attempt("records");
        assertTrue(valid.toString().contains("leaseToken=<redacted>"));
        assertFalse(valid.toString().contains(TOKEN));

        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        null,
                        TOKEN,
                        valid.leaseExpiresAt(),
                        valid.eventDraft(),
                        valid.checkpointCreatedAt()),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartAttempt.leaseOwnerId");
        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        " ",
                        TOKEN,
                        valid.leaseExpiresAt(),
                        valid.eventDraft(),
                        valid.checkpointCreatedAt()),
                FreshExecutionStartValidationCode.INVALID_IDENTIFIER,
                "freshExecutionStartAttempt.leaseOwnerId");
        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        OWNER,
                        "\t",
                        valid.leaseExpiresAt(),
                        valid.eventDraft(),
                        valid.checkpointCreatedAt()),
                FreshExecutionStartValidationCode.INVALID_IDENTIFIER,
                "freshExecutionStartAttempt.leaseToken");
        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        OWNER,
                        null,
                        valid.leaseExpiresAt(),
                        valid.eventDraft(),
                        valid.checkpointCreatedAt()),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartAttempt.leaseToken");
        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        OWNER,
                        TOKEN,
                        null,
                        valid.eventDraft(),
                        valid.checkpointCreatedAt()),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartAttempt.leaseExpiresAt");
        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        OWNER,
                        TOKEN,
                        valid.leaseExpiresAt(),
                        null,
                        valid.checkpointCreatedAt()),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartAttempt.eventDraft");
        assertValidation(
                () -> new FreshExecutionStartAttempt(
                        OWNER,
                        TOKEN,
                        valid.leaseExpiresAt(),
                        valid.eventDraft(),
                        null),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartAttempt.checkpointCreatedAt");
        assertValidation(
                () -> new FreshExecutionStartRequest(null, Optional.empty()),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartRequest.bootstrapResult");
        assertValidation(
                () -> new FreshExecutionStartRequest(
                        PersistenceResult.applied(
                                FreshExecutionStartTestFixtures.bootstrap(
                                        "null-optional")),
                        null),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStartRequest.attempt");

        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("outcome");
        FreshExecutionStartAttempt attempt =
                FreshExecutionStartTestFixtures.attempt("outcome");
        MaterializedExecutionStart materialized =
                FreshExecutionStartTestFixtures.materialized(
                        bootstrap,
                        attempt);
        LeaseRecord lease = FreshExecutionStartTestFixtures.lease(
                bootstrap,
                attempt,
                7);
        PersistedExecutionStart persisted =
                FreshExecutionStartTestFixtures.persisted(
                        bootstrap,
                        lease,
                        materialized);
        assertValidation(
                () -> new FreshExecutionStarted(
                        PersistenceOutcome.FOUND,
                        persisted),
                FreshExecutionStartValidationCode.INVALID_OUTCOME_STATE,
                "freshExecutionStarted.startOutcome");
        PersistenceFailure failure = failure("atomic");
        assertValidation(
                () -> new FreshAtomicExecutionStartRejected(
                        bootstrap.plan().id(),
                        failure,
                        FreshExecutionLeaseDisposition
                                .ACQUISITION_INDETERMINATE),
                FreshExecutionStartValidationCode.INVALID_OUTCOME_STATE,
                "freshAtomicExecutionStartRejected.leaseDisposition");
    }

    @Test
    void replayAndRejectedGateBeforeAndIgnoreEvenPresentAttempt() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("gate-short");
        FreshExecutionStartAttempt ignoredAttempt =
                FreshExecutionStartTestFixtures.attempt("gate-short");
        AtomicInteger materializeCalls = new AtomicInteger();
        ExecutionStartMaterializer materializer = request -> {
            materializeCalls.incrementAndGet();
            throw new AssertionError("must not materialize");
        };
        var leaseRepository =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) -> {
                            throw new AssertionError("must not acquire");
                        });
        var startRepository =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> {
                            throw new AssertionError("must not start");
                        });

        PersistenceResult<PersistedPlanBootstrap> replay =
                PersistenceResult.replayed(bootstrap);
        FreshExecutionStartOutcome replayOutcome = starter(
                ignored -> new RecoveryRequired(bootstrap.plan().id()),
                materializer,
                leaseRepository,
                startRepository).start(new FreshExecutionStartRequest(
                        replay,
                        Optional.of(ignoredAttempt)));
        assertEquals(
                new FreshExecutionRecoveryRequired(bootstrap.plan().id()),
                replayOutcome);

        PersistenceFailure rejection = failure("bootstrap");
        PersistenceResult<PersistedPlanBootstrap> rejected =
                rejected(rejection);
        FreshExecutionStartOutcome rejectedOutcome = starter(
                ignored -> new BootstrapRejected(rejection),
                materializer,
                leaseRepository,
                startRepository).start(new FreshExecutionStartRequest(
                        rejected,
                        Optional.of(ignoredAttempt)));
        assertSame(
                rejection,
                ((FreshExecutionBootstrapRejected) rejectedOutcome).failure());
        assertEquals(0, materializeCalls.get());
        assertEquals(0, leaseRepository.acquireCalls.get());
        assertEquals(0, startRepository.calls.get());
        leaseRepository.assertOnlyAcquireWasUsed();
    }

    @Test
    void eligibleRequiresAttemptOnlyAfterConsistentGate() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("empty");
        AtomicInteger materializeCalls = new AtomicInteger();
        var leaseRepository =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) -> null);
        var startRepository =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> null);
        DefaultFreshExecutionStarter starter = starter(
                ignored -> new FreshLeaseAdmissionEligible(
                        bootstrap.plan().id()),
                request -> {
                    materializeCalls.incrementAndGet();
                    return null;
                },
                leaseRepository,
                startRepository);

        assertValidation(
                () -> starter.start(new FreshExecutionStartRequest(
                        PersistenceResult.applied(bootstrap),
                        Optional.empty())),
                FreshExecutionStartValidationCode.REQUIRED_VALUE_MISSING,
                "freshExecutionStart.request.attempt");
        assertEquals(0, materializeCalls.get());
        assertEquals(0, leaseRepository.acquireCalls.get());
        assertEquals(0, startRepository.calls.get());
    }

    @Test
    void inconsistentGateDecisionsFailBeforeAttemptAndDownstream() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("bad-gate");
        PersistenceFailure original = failure("original");
        List<GateCase> cases = List.of(
                new GateCase(
                        PersistenceResult.applied(bootstrap),
                        new RecoveryRequired(bootstrap.plan().id())),
                new GateCase(
                        PersistenceResult.applied(bootstrap),
                        new FreshLeaseAdmissionEligible(
                                new PlanId("wrong-plan"))),
                new GateCase(
                        PersistenceResult.replayed(bootstrap),
                        new FreshLeaseAdmissionEligible(
                                bootstrap.plan().id())),
                new GateCase(
                        rejected(original),
                        new BootstrapRejected(failure("replacement"))),
                new GateCase(
                        PersistenceResult.found(bootstrap),
                        new RecoveryRequired(bootstrap.plan().id())),
                new GateCase(
                        PersistenceResult.applied(bootstrap),
                        null));
        AtomicInteger materializeCalls = new AtomicInteger();
        var leaseRepository =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) -> null);
        var startRepository =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> null);

        for (GateCase gateCase : cases) {
            assertValidation(
                    () -> starter(
                            ignored -> gateCase.decision(),
                            request -> {
                                materializeCalls.incrementAndGet();
                                return null;
                            },
                            leaseRepository,
                            startRepository).start(
                                    new FreshExecutionStartRequest(
                                            gateCase.result(),
                                            Optional.empty())),
                    FreshExecutionStartValidationCode
                            .INCONSISTENT_GATE_DECISION,
                    "freshExecutionStart.gateDecision");
        }
        assertEquals(0, materializeCalls.get());
        assertEquals(0, leaseRepository.acquireCalls.get());
        assertEquals(0, startRepository.calls.get());
    }

    @Test
    void happyPathUsesStrictOrderReturnedFenceAndPreservesOutcomes() {
        for (PersistenceOutcome leaseOutcome : List.of(
                PersistenceOutcome.APPLIED,
                PersistenceOutcome.REPLAYED)) {
            for (PersistenceOutcome startOutcome : List.of(
                    PersistenceOutcome.APPLIED,
                    PersistenceOutcome.REPLAYED)) {
                String suffix = "happy-" + leaseOutcome + "-" + startOutcome;
                PersistedPlanBootstrap bootstrap =
                        FreshExecutionStartTestFixtures.bootstrap(suffix);
                FreshExecutionStartAttempt attempt =
                        FreshExecutionStartTestFixtures.attempt(suffix);
                MaterializedExecutionStart materialized =
                        FreshExecutionStartTestFixtures.materialized(
                                bootstrap,
                                attempt);
                LeaseRecord lease = FreshExecutionStartTestFixtures.lease(
                        bootstrap,
                        attempt,
                        41);
                PersistedExecutionStart persisted =
                        FreshExecutionStartTestFixtures.persisted(
                                bootstrap,
                                lease,
                                materialized);
                List<String> order = new ArrayList<>();
                FreshExecutionGate gate = result -> {
                    order.add("gate");
                    return new FreshLeaseAdmissionEligible(
                            bootstrap.plan().id());
                };
                var leases =
                        new FreshExecutionStartTestFixtures
                                .ScriptedLeaseRepository(
                                (planId, owner, token, expiry) -> {
                                    order.add("acquire");
                                    return successful(
                                            leaseOutcome,
                                            lease);
                                });
                var starts =
                        new FreshExecutionStartTestFixtures
                                .ScriptedStartRepository(request -> {
                                    order.add("start");
                                    return successful(
                                            startOutcome,
                                            persisted);
                                });

                FreshExecutionStartOutcome outcome = starter(
                        gate,
                        request -> {
                            order.add("materialize");
                            return materialized;
                        },
                        leases,
                        starts).start(FreshExecutionStartTestFixtures.request(
                                PersistenceResult.applied(bootstrap),
                                attempt));

                FreshExecutionStarted started =
                        (FreshExecutionStarted) outcome;
                assertEquals(startOutcome, started.startOutcome());
                assertSame(persisted, started.persistedStart());
                assertEquals(
                        List.of("gate", "materialize", "acquire", "start"),
                        order);
                assertEquals(1, leases.acquireCalls.get());
                assertEquals(1, starts.calls.get());
                assertEquals(lease.planId(), starts.request.planId());
                assertEquals(lease.leaseToken(), starts.request.leaseToken());
                assertEquals(41, starts.request.fencingToken());
                assertEquals(materialized.startEvent(),
                        starts.request.startEvent());
                assertEquals(materialized.startedCheckpoint(),
                        starts.request.startedCheckpoint());
                leases.assertOnlyAcquireWasUsed();
            }
        }
    }

    @Test
    void materializerFailureAndEveryAuthorityMismatchStopBeforeAcquire() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("materialized");
        FreshExecutionStartAttempt attempt =
                FreshExecutionStartTestFixtures.attempt("materialized");
        MaterializedExecutionStart valid =
                FreshExecutionStartTestFixtures.materialized(
                        bootstrap,
                        attempt);
        var leases =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) -> null);
        var starts =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> null);
        IllegalStateException sentinel =
                new IllegalStateException("materializer sentinel");
        IllegalStateException propagated = assertThrows(
                IllegalStateException.class,
                () -> starter(
                        ignored -> new FreshLeaseAdmissionEligible(
                                bootstrap.plan().id()),
                        request -> {
                            throw sentinel;
                        },
                        leases,
                        starts).start(FreshExecutionStartTestFixtures.request(
                                PersistenceResult.applied(bootstrap),
                                attempt)));
        assertSame(sentinel, propagated);

        List<MaterializedCase> invalid = new ArrayList<>();
        invalid.add(new MaterializedCase(
                null,
                "freshExecutionStart.materializedStart"));
        eventMismatches(valid).forEach(value -> invalid.add(
                new MaterializedCase(
                        new MaterializedExecutionStart(
                                value,
                                valid.startedCheckpoint()),
                        "freshExecutionStart.materializedStart.startEvent")));
        checkpointMismatches(valid).forEach(value -> invalid.add(
                new MaterializedCase(
                        new MaterializedExecutionStart(
                                valid.startEvent(),
                                value),
                        "freshExecutionStart.materializedStart"
                                + ".startedCheckpoint")));

        for (MaterializedCase invalidCase : invalid) {
            assertValidation(
                    () -> starter(
                            ignored -> new FreshLeaseAdmissionEligible(
                                    bootstrap.plan().id()),
                            request -> invalidCase.value(),
                            leases,
                            starts).start(
                                    FreshExecutionStartTestFixtures.request(
                                            PersistenceResult.applied(
                                                    bootstrap),
                                            attempt)),
                    FreshExecutionStartValidationCode
                            .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                    invalidCase.path());
        }
        assertEquals(0, leases.acquireCalls.get());
        assertEquals(0, starts.calls.get());
        leases.assertOnlyAcquireWasUsed();
    }

    @Test
    void leaseResultsHaveExactFailureTaxonomyAndNeverCallStartOrRelease() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("lease-protocol");
        FreshExecutionStartAttempt attempt =
                FreshExecutionStartTestFixtures.attempt("lease-protocol");
        MaterializedExecutionStart materialized =
                FreshExecutionStartTestFixtures.materialized(
                        bootstrap,
                        attempt);
        LeaseRecord valid = FreshExecutionStartTestFixtures.lease(
                bootstrap,
                attempt,
                9);
        PersistenceFailure typedFailure = failure("lease-rejected");
        var rejectedLeases =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) ->
                                rejected(typedFailure));
        var neverStarts =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> {
                            throw new AssertionError("must not start");
                        });
        FreshExecutionStartOutcome rejectedOutcome = starter(
                ignored -> new FreshLeaseAdmissionEligible(
                        bootstrap.plan().id()),
                ignored -> materialized,
                rejectedLeases,
                neverStarts).start(FreshExecutionStartTestFixtures.request(
                        PersistenceResult.applied(bootstrap),
                        attempt));
        assertSame(
                typedFailure,
                ((FreshLeaseAcquisitionRejected) rejectedOutcome).failure());
        assertEquals(1, rejectedLeases.acquireCalls.get());
        assertEquals(0, neverStarts.calls.get());
        rejectedLeases.assertOnlyAcquireWasUsed();

        IllegalArgumentException leaseSentinel =
                new IllegalArgumentException("repository saw " + TOKEN);
        List<LeaseProtocolCase> indeterminate = List.of(
                new LeaseProtocolCase(
                        (planId, owner, token, expiry) -> null,
                        FreshExecutionStartProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "freshExecutionStart.leaseAcquireResult",
                        null),
                new LeaseProtocolCase(
                        (planId, owner, token, expiry) ->
                                PersistenceResult.found(valid),
                        FreshExecutionStartProtocolCode
                                .UNEXPECTED_PERSISTENCE_OUTCOME,
                        "freshExecutionStart.leaseAcquireResult.outcome",
                        null),
                new LeaseProtocolCase(
                        throwingLease(leaseSentinel),
                        FreshExecutionStartProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "freshExecutionStart.leaseAcquireResult",
                        leaseSentinel));
        for (LeaseProtocolCase protocolCase : indeterminate) {
            var leases =
                    new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                            protocolCase.behavior());
            FreshExecutionStartProtocolException failure = assertThrows(
                    FreshExecutionStartProtocolException.class,
                    () -> starter(
                            ignored -> new FreshLeaseAdmissionEligible(
                                    bootstrap.plan().id()),
                            ignored -> materialized,
                            leases,
                            neverStarts).start(
                                    FreshExecutionStartTestFixtures.request(
                                            PersistenceResult.applied(
                                                    bootstrap),
                                            attempt)));
            assertProtocol(
                    failure,
                    bootstrap.plan().id(),
                    FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                    protocolCase.code(),
                    protocolCase.path(),
                    FreshExecutionLeaseDisposition
                            .ACQUISITION_INDETERMINATE);
            if (protocolCase.originalCause() == null) {
                assertNull(failure.getCause());
            } else {
                assertSanitizedCause(
                        failure,
                        protocolCase.originalCause());
            }
            assertFalse(failure.getMessage().contains(TOKEN));
            assertFalse(failure.toString().contains(TOKEN));
            assertEquals(1, leases.acquireCalls.get());
            assertEquals(0, neverStarts.calls.get());
            leases.assertOnlyAcquireWasUsed();
        }

        List<LeaseRecord> mismatches = List.of(
                new LeaseRecord(
                        new PlanId("wrong-plan"),
                        OWNER,
                        TOKEN,
                        9,
                        T0.plusSeconds(10),
                        attempt.leaseExpiresAt()),
                new LeaseRecord(
                        bootstrap.plan().id(),
                        "wrong-owner",
                        TOKEN,
                        9,
                        T0.plusSeconds(10),
                        attempt.leaseExpiresAt()),
                new LeaseRecord(
                        bootstrap.plan().id(),
                        OWNER,
                        "wrong-token",
                        9,
                        T0.plusSeconds(10),
                        attempt.leaseExpiresAt()),
                new LeaseRecord(
                        bootstrap.plan().id(),
                        OWNER,
                        TOKEN,
                        9,
                        T0.plusSeconds(10),
                        attempt.leaseExpiresAt().plusSeconds(1)));
        for (LeaseRecord mismatch : mismatches) {
            var leases =
                    new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                            (planId, owner, token, expiry) ->
                                    PersistenceResult.applied(mismatch));
            FreshExecutionStartProtocolException failure = assertThrows(
                    FreshExecutionStartProtocolException.class,
                    () -> starter(
                            ignored -> new FreshLeaseAdmissionEligible(
                                    bootstrap.plan().id()),
                            ignored -> materialized,
                            leases,
                            neverStarts).start(
                                    FreshExecutionStartTestFixtures.request(
                                            PersistenceResult.applied(
                                                    bootstrap),
                                            attempt)));
            assertProtocol(
                    failure,
                    bootstrap.plan().id(),
                    FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                    FreshExecutionStartProtocolCode
                            .INCONSISTENT_LEASE_AUTHORITY,
                    "freshExecutionStart.leaseAcquireResult.value",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY);
            assertEquals(1, leases.acquireCalls.get());
            assertEquals(0, neverStarts.calls.get());
            leases.assertOnlyAcquireWasUsed();
        }
        assertEquals(0, neverStarts.calls.get());
    }

    @Test
    void atomicStartResultsRetainLeasePreserveFailureAndSanitizeCause() {
        PersistedPlanBootstrap bootstrap =
                FreshExecutionStartTestFixtures.bootstrap("start-protocol");
        FreshExecutionStartAttempt attempt =
                FreshExecutionStartTestFixtures.attempt("start-protocol");
        MaterializedExecutionStart materialized =
                FreshExecutionStartTestFixtures.materialized(
                        bootstrap,
                        attempt);
        LeaseRecord lease = FreshExecutionStartTestFixtures.lease(
                bootstrap,
                attempt,
                13);
        var rejectedLeases =
                new FreshExecutionStartTestFixtures.ScriptedLeaseRepository(
                        (planId, owner, token, expiry) ->
                                PersistenceResult.applied(lease));
        PersistenceFailure typedFailure = failure("start-rejected");
        var rejectedStarts =
                new FreshExecutionStartTestFixtures.ScriptedStartRepository(
                        request -> rejected(typedFailure));
        FreshExecutionStartOutcome rejectedOutcome = starter(
                ignored -> new FreshLeaseAdmissionEligible(
                        bootstrap.plan().id()),
                ignored -> materialized,
                rejectedLeases,
                rejectedStarts).start(FreshExecutionStartTestFixtures.request(
                        PersistenceResult.applied(bootstrap),
                        attempt));
        FreshAtomicExecutionStartRejected rejected =
                (FreshAtomicExecutionStartRejected) rejectedOutcome;
        assertSame(typedFailure, rejected.failure());
        assertEquals(
                FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                rejected.leaseDisposition());
        assertEquals(1, rejectedLeases.acquireCalls.get());
        assertEquals(1, rejectedStarts.calls.get());
        rejectedLeases.assertOnlyAcquireWasUsed();

        PersistedExecutionStart persisted =
                FreshExecutionStartTestFixtures.persisted(
                        bootstrap,
                        lease,
                        materialized);
        IllegalStateException sentinel =
                new IllegalStateException("start saw " + TOKEN);
        List<StartProtocolCase> protocolCases = List.of(
                new StartProtocolCase(
                        request -> null,
                        FreshExecutionStartProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "freshExecutionStart.executionStartResult",
                        null),
                new StartProtocolCase(
                        request -> PersistenceResult.found(persisted),
                        FreshExecutionStartProtocolCode
                                .UNEXPECTED_PERSISTENCE_OUTCOME,
                        "freshExecutionStart.executionStartResult.outcome",
                        null),
                new StartProtocolCase(
                        request -> {
                            throw sentinel;
                        },
                        FreshExecutionStartProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "freshExecutionStart.executionStartResult",
                        sentinel));
        for (StartProtocolCase protocolCase : protocolCases) {
            var leases =
                    new FreshExecutionStartTestFixtures
                            .ScriptedLeaseRepository(
                            (planId, owner, token, expiry) ->
                                    PersistenceResult.applied(lease));
            var starts =
                    new FreshExecutionStartTestFixtures
                            .ScriptedStartRepository(protocolCase.behavior());
            FreshExecutionStartProtocolException failure = assertThrows(
                    FreshExecutionStartProtocolException.class,
                    () -> starter(
                            ignored -> new FreshLeaseAdmissionEligible(
                                    bootstrap.plan().id()),
                            ignored -> materialized,
                            leases,
                            starts).start(
                                    FreshExecutionStartTestFixtures.request(
                                            PersistenceResult.applied(
                                                    bootstrap),
                                            attempt)));
            assertProtocol(
                    failure,
                    bootstrap.plan().id(),
                    FreshExecutionStartProtocolStage.ATOMIC_START,
                    protocolCase.code(),
                    protocolCase.path(),
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY);
            if (protocolCase.cause() != null) {
                assertSanitizedCause(failure, protocolCase.cause());
            } else {
                assertNull(failure.getCause());
            }
            assertFalse(failure.getMessage().contains(TOKEN));
            assertFalse(failure.toString().contains(TOKEN));
            assertEquals(1, leases.acquireCalls.get());
            assertEquals(1, starts.calls.get());
            leases.assertOnlyAcquireWasUsed();
        }

        List<PersistedExecutionStart> mismatches = List.of(
                new PersistedExecutionStart(
                        new PlanId("wrong-plan"),
                        lease.ownerId(),
                        lease.fencingToken(),
                        materialized.startEvent(),
                        new VersionedCheckpoint(
                                2,
                                materialized.startedCheckpoint())),
                new PersistedExecutionStart(
                        bootstrap.plan().id(),
                        "wrong-owner",
                        lease.fencingToken(),
                        materialized.startEvent(),
                        new VersionedCheckpoint(
                                2,
                                materialized.startedCheckpoint())),
                new PersistedExecutionStart(
                        bootstrap.plan().id(),
                        lease.ownerId(),
                        lease.fencingToken() + 1,
                        materialized.startEvent(),
                        new VersionedCheckpoint(
                                2,
                                materialized.startedCheckpoint())),
                new PersistedExecutionStart(
                        bootstrap.plan().id(),
                        lease.ownerId(),
                        lease.fencingToken(),
                        eventWith(
                                materialized.startEvent(),
                                new EventId("different-start-event")),
                        new VersionedCheckpoint(
                                2,
                                materialized.startedCheckpoint())),
                new PersistedExecutionStart(
                        bootstrap.plan().id(),
                        lease.ownerId(),
                        lease.fencingToken(),
                        materialized.startEvent(),
                        new VersionedCheckpoint(
                                2,
                                checkpointWithCreatedAt(
                                        materialized.startedCheckpoint(),
                                        T0.plusSeconds(20)))));
        for (PersistedExecutionStart mismatch : mismatches) {
            var leases =
                    new FreshExecutionStartTestFixtures
                            .ScriptedLeaseRepository(
                            (planId, owner, token, expiry) ->
                                    PersistenceResult.applied(lease));
            var starts =
                    new FreshExecutionStartTestFixtures
                            .ScriptedStartRepository(
                            request -> PersistenceResult.applied(mismatch));
            FreshExecutionStartProtocolException failure = assertThrows(
                    FreshExecutionStartProtocolException.class,
                    () -> starter(
                            ignored -> new FreshLeaseAdmissionEligible(
                                    bootstrap.plan().id()),
                            ignored -> materialized,
                            leases,
                            starts).start(
                                    FreshExecutionStartTestFixtures.request(
                                            PersistenceResult.applied(
                                                    bootstrap),
                                            attempt)));
            assertProtocol(
                    failure,
                    bootstrap.plan().id(),
                    FreshExecutionStartProtocolStage.ATOMIC_START,
                    FreshExecutionStartProtocolCode
                            .INCONSISTENT_EXECUTION_START_RESULT,
                    "freshExecutionStart.executionStartResult.value",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY);
            assertEquals(1, leases.acquireCalls.get());
            assertEquals(1, starts.calls.get());
            leases.assertOnlyAcquireWasUsed();
        }
    }

    private static DefaultFreshExecutionStarter starter(
            FreshExecutionGate gate,
            ExecutionStartMaterializer materializer,
            FreshExecutionStartTestFixtures.ScriptedLeaseRepository leases,
            FreshExecutionStartTestFixtures.ScriptedStartRepository starts) {
        return new DefaultFreshExecutionStarter(
                gate,
                materializer,
                leases,
                starts);
    }

    private static List<EventEnvelope> eventMismatches(
            MaterializedExecutionStart valid) {
        EventEnvelope event = valid.startEvent();
        return List.of(
                eventWith(event, new EventId("wrong-id")),
                new EventEnvelope(
                        event.id(),
                        new TaskFrameId("wrong-task"),
                        event.planId(),
                        event.sequence(),
                        event.occurredAt(),
                        event.type(),
                        event.causationId(),
                        event.correlationId(),
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        new PlanId("wrong-plan"),
                        event.sequence(),
                        event.occurredAt(),
                        event.type(),
                        event.causationId(),
                        event.correlationId(),
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        event.planId(),
                        2,
                        event.occurredAt(),
                        event.type(),
                        event.causationId(),
                        event.correlationId(),
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        event.planId(),
                        event.sequence(),
                        event.occurredAt().plusSeconds(1),
                        event.type(),
                        event.causationId(),
                        event.correlationId(),
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        event.planId(),
                        event.sequence(),
                        event.occurredAt(),
                        new EventType("wrong-type"),
                        event.causationId(),
                        event.correlationId(),
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        event.planId(),
                        event.sequence(),
                        event.occurredAt(),
                        event.type(),
                        Optional.of(new EventId("cause")),
                        event.correlationId(),
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        event.planId(),
                        event.sequence(),
                        event.occurredAt(),
                        event.type(),
                        event.causationId(),
                        "wrong-correlation",
                        event.payload()),
                new EventEnvelope(
                        event.id(),
                        event.taskFrameId(),
                        event.planId(),
                        event.sequence(),
                        event.occurredAt(),
                        event.type(),
                        event.causationId(),
                        event.correlationId(),
                        new InlineEventPayload(new ObjectValue(
                                Map.of("value", new TextValue("different"))))));
    }

    private static List<Checkpoint> checkpointMismatches(
            MaterializedExecutionStart valid) {
        Checkpoint checkpoint = valid.startedCheckpoint();
        var stepId = checkpoint.stepStates().keySet().iterator().next();
        return List.of(
                checkpoint(
                        checkpoint,
                        new TaskFrameId("wrong-task"),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        new PlanId("wrong-plan"),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        new PlanRevisionId("wrong-revision"),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber() + 1,
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        2,
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        PlanExecutionState.PAUSED,
                        checkpoint.stepStates(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        Map.of(),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        Map.of(stepId, StepExecutionState.ACTIVE),
                        checkpoint.receiptReferences(),
                        checkpoint.createdAt()),
                checkpoint(
                        checkpoint,
                        checkpoint.taskFrameId(),
                        checkpoint.planId(),
                        checkpoint.revisionId(),
                        checkpoint.revisionNumber(),
                        checkpoint.lastEventSequence(),
                        checkpoint.planState(),
                        checkpoint.stepStates(),
                        List.of(new ReceiptId("unexpected-receipt")),
                        checkpoint.createdAt()),
                checkpointWithCreatedAt(
                        checkpoint,
                        checkpoint.createdAt().plusSeconds(1)));
    }

    private static EventEnvelope eventWith(
            EventEnvelope event,
            EventId id) {
        return new EventEnvelope(
                id,
                event.taskFrameId(),
                event.planId(),
                event.sequence(),
                event.occurredAt(),
                event.type(),
                event.causationId(),
                event.correlationId(),
                event.payload());
    }

    private static Checkpoint checkpointWithCreatedAt(
            Checkpoint checkpoint,
            Instant createdAt) {
        return checkpoint(
                checkpoint,
                checkpoint.taskFrameId(),
                checkpoint.planId(),
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                checkpoint.lastEventSequence(),
                checkpoint.planState(),
                checkpoint.stepStates(),
                checkpoint.receiptReferences(),
                createdAt);
    }

    private static Checkpoint checkpoint(
            Checkpoint ignored,
            TaskFrameId taskFrameId,
            PlanId planId,
            PlanRevisionId revisionId,
            long revisionNumber,
            long sequence,
            PlanExecutionState state,
            Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState>
                    stepStates,
            List<ReceiptId> receipts,
            Instant createdAt) {
        return new Checkpoint(
                taskFrameId,
                planId,
                revisionId,
                revisionNumber,
                sequence,
                state,
                stepStates,
                receipts,
                createdAt);
    }

    private static FreshExecutionStartTestFixtures.LeaseAcquire throwingLease(
            RuntimeException failure) {
        return (planId, owner, token, expiry) -> {
            throw failure;
        };
    }

    private static PersistenceFailure failure(String suffix) {
        return new PersistenceFailure(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "failure." + suffix);
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceFailure failure) {
        return new PersistenceResult<>(
                PersistenceOutcome.REJECTED,
                Optional.empty(),
                Optional.of(failure));
    }

    private static <T> PersistenceResult<T> successful(
            PersistenceOutcome outcome,
            T value) {
        return switch (outcome) {
            case APPLIED -> PersistenceResult.applied(value);
            case REPLAYED -> PersistenceResult.replayed(value);
            default -> throw new IllegalArgumentException(
                    "test outcome must be successful");
        };
    }

    private static void assertValidation(
            Runnable action,
            FreshExecutionStartValidationCode code,
            String path) {
        FreshExecutionStartValidationException failure = assertThrows(
                FreshExecutionStartValidationException.class,
                action::run);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        assertFalse(failure.getMessage().contains(TOKEN));
        assertFalse(failure.toString().contains(TOKEN));
    }

    private static void assertProtocol(
            FreshExecutionStartProtocolException failure,
            PlanId planId,
            FreshExecutionStartProtocolStage stage,
            FreshExecutionStartProtocolCode code,
            String path,
            FreshExecutionLeaseDisposition disposition) {
        assertEquals(planId, failure.planId());
        assertEquals(stage, failure.stage());
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        assertEquals(disposition, failure.leaseDisposition());
    }

    private static void assertSanitizedCause(
            FreshExecutionStartProtocolException failure,
            Throwable original) {
        Throwable sanitized = failure.getCause();
        assertNotNull(sanitized);
        assertNotSame(original, sanitized);
        assertEquals(
                "collaborator exception details redacted [type="
                        + original.getClass().getName()
                        + "]",
                sanitized.getMessage());
        assertNull(sanitized.getCause());
        assertEquals(0, sanitized.getSuppressed().length);
        assertFalse(sanitized.toString().contains(TOKEN));
    }

    private record GateCase(
            PersistenceResult<PersistedPlanBootstrap> result,
            FreshExecutionDecision decision) {
    }

    private record MaterializedCase(
            MaterializedExecutionStart value,
            String path) {
    }

    private record LeaseProtocolCase(
            FreshExecutionStartTestFixtures.LeaseAcquire behavior,
            FreshExecutionStartProtocolCode code,
            String path,
            Throwable originalCause) {
    }

    private record StartProtocolCase(
            java.util.function.Function<
                    io.paperagent.v2.persistence.ExecutionStartRequest,
                    PersistenceResult<PersistedExecutionStart>> behavior,
            FreshExecutionStartProtocolCode code,
            String path,
            Throwable cause) {
    }
}
