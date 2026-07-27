package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;

public final class DefaultExecutionStartRecoverer
        implements ExecutionStartRecoverer {
    private final ExecutionStartRecoveryRepository recoveryRepository;
    private final RecoveryReadyExecutionStartMaterializer materializer;
    private final LeaseRepository leaseRepository;
    private final ExecutionStartRepository executionStartRepository;

    public DefaultExecutionStartRecoverer(
            ExecutionStartRecoveryRepository recoveryRepository,
            RecoveryReadyExecutionStartMaterializer materializer,
            LeaseRepository leaseRepository,
            ExecutionStartRepository executionStartRepository) {
        this.recoveryRepository = ExecutionStartRecoveryValues.required(
                recoveryRepository,
                "executionStartRecovery.recoveryRepository");
        this.materializer = ExecutionStartRecoveryValues.required(
                materializer,
                "executionStartRecovery.materializer");
        this.leaseRepository = ExecutionStartRecoveryValues.required(
                leaseRepository,
                "executionStartRecovery.leaseRepository");
        this.executionStartRepository = ExecutionStartRecoveryValues.required(
                executionStartRepository,
                "executionStartRecovery.executionStartRepository");
    }

    @Override
    public ExecutionStartRecoveryOutcome recover(
            ExecutionStartRecoveryRequest request) {
        ExecutionStartRecoveryRequest requiredRequest =
                ExecutionStartRecoveryValues.required(
                        request,
                        "executionStartRecovery.request");
        PlanId planId = requiredRequest.planId();
        Inspection initial = inspect(
                planId,
                ExecutionStartRecoveryStage.INITIAL_INSPECT,
                "executionStartRecovery.initialInspectResult",
                ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION);
        if (initial.kind() != InspectionKind.READY) {
            return stateOutcome(
                    planId,
                    initial,
                    ExecutionStartRecoveryStage.INITIAL_INSPECT,
                    ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION);
        }

        FreshExecutionStartAttempt attempt = requiredRequest.attempt()
                .orElseThrow(() ->
                        ExecutionStartRecoveryValues.validationFailure(
                                ExecutionStartRecoveryValidationCode
                                        .REQUIRED_VALUE_MISSING,
                                "executionStartRecovery.request.attempt"));
        materialize(
                planId,
                initial.ready(),
                attempt,
                false);

        PersistenceResult<LeaseRecord> leaseResult = null;
        RuntimeException leaseException = null;
        try {
            leaseResult = leaseRepository.acquire(
                    planId,
                    attempt.leaseOwnerId(),
                    attempt.leaseToken(),
                    attempt.leaseExpiresAt());
        } catch (RuntimeException exception) {
            leaseException = exception;
        }
        ExecutionStartRecoveryLeaseDisposition acquisitionDisposition =
                acquisitionDisposition(leaseResult, leaseException);
        Inspection afterLease = inspect(
                planId,
                ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                "executionStartRecovery.postLeaseInspectResult",
                acquisitionDisposition);

        LeaseRecord lease = classifyBrokenLeaseResult(
                planId,
                attempt,
                leaseResult,
                leaseException);
        if (afterLease.kind() != InspectionKind.READY) {
            return stateOutcome(
                    planId,
                    afterLease,
                    ExecutionStartRecoveryStage.POST_LEASE_INSPECT,
                    acquisitionDisposition);
        }
        if (leaseException != null) {
            throw protocol(
                    planId,
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                    "executionStartRecovery.leaseAcquireResult",
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    leaseException);
        }
        if (leaseResult == null) {
            throw protocol(
                    planId,
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    ExecutionStartRecoveryProtocolCode
                            .NULL_COLLABORATOR_RESULT,
                    "executionStartRecovery.leaseAcquireResult",
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null);
        }
        if (leaseResult.outcome() == PersistenceOutcome.REJECTED) {
            return new ExecutionStartRecoveryRejected(
                    planId,
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    leaseResult.failure().orElseThrow(),
                    ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED);
        }

        MaterializedExecutionStart proposal = materialize(
                planId,
                afterLease.ready(),
                attempt,
                true);
        ExecutionStartRequest startRequest = new ExecutionStartRequest(
                lease.planId(),
                lease.leaseToken(),
                lease.fencingToken(),
                proposal.startEvent(),
                proposal.startedCheckpoint());
        StartObservation start = start(
                planId,
                lease,
                proposal,
                startRequest);
        Inspection afterStart = inspect(
                planId,
                ExecutionStartRecoveryStage.POST_START_INSPECT,
                "executionStartRecovery.postStartInspectResult",
                ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY);

        if (start.kind() == StartKind.BROKEN) {
            throw start.protocolFailure();
        }
        return reconcileStart(planId, start, afterStart);
    }

    private Inspection inspect(
            PlanId planId,
            ExecutionStartRecoveryStage stage,
            String resultPath,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition) {
        PersistenceResult<ExecutionStartRecoverySnapshot> result;
        try {
            result = recoveryRepository.inspect(planId);
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    stage,
                    ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                    resultPath,
                    leaseDisposition,
                    exception);
        }
        if (result == null) {
            throw protocol(
                    planId,
                    stage,
                    ExecutionStartRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                    resultPath,
                    leaseDisposition,
                    null);
        }
        return switch (result.outcome()) {
            case APPLIED, REPLAYED -> throw protocol(
                    planId,
                    stage,
                    ExecutionStartRecoveryProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    resultPath + ".outcome",
                    leaseDisposition,
                    null);
            case FOUND -> classifyFoundInspection(
                    planId,
                    stage,
                    resultPath,
                    leaseDisposition,
                    result);
            case REJECTED -> classifyRejectedInspection(
                    planId,
                    stage,
                    resultPath,
                    leaseDisposition,
                    result);
        };
    }

    private static Inspection classifyFoundInspection(
            PlanId planId,
            ExecutionStartRecoveryStage stage,
            String resultPath,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition,
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        Object value = result.value().orElse(null);
        if (!(value instanceof ExecutionStartRecoverySnapshot snapshot)
                || !snapshot.planId().equals(planId)) {
            throw protocol(
                    planId,
                    stage,
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_INSPECTION_RESULT,
                    resultPath + ".value",
                    leaseDisposition,
                    null);
        }
        if (snapshot instanceof PersistedExecutionStartReady ready) {
            return Inspection.ready(ready);
        }
        if (snapshot instanceof PersistedExecutionStartCommitted committed) {
            return Inspection.committed(committed);
        }
        throw protocol(
                planId,
                stage,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_INSPECTION_RESULT,
                resultPath + ".value",
                leaseDisposition,
                null);
    }

    private static Inspection classifyRejectedInspection(
            PlanId planId,
            ExecutionStartRecoveryStage stage,
            String resultPath,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition,
            PersistenceResult<ExecutionStartRecoverySnapshot> result) {
        Object value = result.failure().orElse(null);
        if (!(value instanceof PersistenceFailure failure)) {
            throw protocol(
                    planId,
                    stage,
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_INSPECTION_RESULT,
                    resultPath + ".failure",
                    leaseDisposition,
                    null);
        }
        if (ExecutionStartRecoveryValues.isNotFound(failure)) {
            return Inspection.notFound(failure);
        }
        if (ExecutionStartRecoveryValues.isPartial(failure)) {
            return Inspection.partial(failure);
        }
        if (ExecutionStartRecoveryValues.isAdvanced(failure)) {
            return Inspection.advanced(failure);
        }
        throw protocol(
                planId,
                stage,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_INSPECTION_RESULT,
                resultPath + ".failure",
                leaseDisposition,
                null);
    }

    private MaterializedExecutionStart materialize(
            PlanId planId,
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            boolean afterLease) {
        String resultPath = afterLease
                ? "executionStartRecovery.postLeaseMaterializedStart"
                : "executionStartRecovery.materializedStart";
        ExecutionStartRecoveryStage stage = afterLease
                ? ExecutionStartRecoveryStage.POST_LEASE_MATERIALIZE
                : ExecutionStartRecoveryStage.MATERIALIZE;
        ExecutionStartRecoveryLeaseDisposition leaseDisposition = afterLease
                ? ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY
                : ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION;
        MaterializedExecutionStart proposal;
        try {
            proposal = materializer.materialize(
                    new RecoveryReadyExecutionStartMaterializationRequest(
                            ready,
                            attempt.eventDraft(),
                            attempt.checkpointCreatedAt()));
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    stage,
                    ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                    resultPath,
                    leaseDisposition,
                    exception);
        }
        if (proposal == null) {
            if (afterLease) {
                throw protocol(
                        planId,
                        stage,
                        ExecutionStartRecoveryProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        resultPath,
                        leaseDisposition,
                        null);
            }
            throw materializationValidation(resultPath);
        }
        if (!isExpectedEvent(
                ready,
                attempt,
                proposal.startEvent())) {
            if (afterLease) {
                throw protocol(
                        planId,
                        stage,
                        ExecutionStartRecoveryProtocolCode
                                .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                        resultPath + ".startEvent",
                        leaseDisposition,
                        null);
            }
            throw materializationValidation(resultPath + ".startEvent");
        }
        if (!isExpectedCheckpoint(
                ready,
                attempt,
                proposal.startedCheckpoint())) {
            if (afterLease) {
                throw protocol(
                        planId,
                        stage,
                        ExecutionStartRecoveryProtocolCode
                                .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                        resultPath + ".startedCheckpoint",
                        leaseDisposition,
                        null);
            }
            throw materializationValidation(
                    resultPath + ".startedCheckpoint");
        }
        return proposal;
    }

    private static boolean isExpectedEvent(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            EventEnvelope event) {
        var draft = attempt.eventDraft();
        return event != null
                && event.id().equals(draft.id())
                && event.taskFrameId().equals(
                        ready.bootstrap().taskFrame().id())
                && event.planId().equals(ready.currentPlan().id())
                && event.sequence() == 1
                && event.occurredAt().equals(draft.occurredAt())
                && event.type().equals(draft.type())
                && event.causationId().equals(draft.causationId())
                && event.correlationId().equals(draft.correlationId())
                && event.payload().equals(draft.payload());
    }

    private static boolean isExpectedCheckpoint(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            Checkpoint checkpoint) {
        if (checkpoint == null) {
            return false;
        }
        PlanRevision latest = ready.currentPlan().latestRevision();
        if (!checkpoint.taskFrameId().equals(
                        ready.bootstrap().taskFrame().id())
                || !checkpoint.planId().equals(ready.currentPlan().id())
                || !checkpoint.revisionId().equals(latest.id())
                || checkpoint.revisionNumber() != latest.number()
                || checkpoint.lastEventSequence() != 1
                || checkpoint.planState() != PlanExecutionState.ACTIVE
                || !checkpoint.createdAt().equals(
                        attempt.checkpointCreatedAt())
                || !checkpoint.receiptReferences().isEmpty()
                || checkpoint.stepStates().size()
                        != latest.steps().size()) {
            return false;
        }
        for (var step : latest.steps()) {
            if (checkpoint.stepStates().get(step.id())
                    != StepExecutionState.NOT_STARTED) {
                return false;
            }
        }
        return true;
    }

    private static ExecutionStartRecoveryLeaseDisposition
            acquisitionDisposition(
                    PersistenceResult<LeaseRecord> result,
                    RuntimeException exception) {
        if (exception != null
                || result == null
                || result.outcome() == PersistenceOutcome.FOUND) {
            return ExecutionStartRecoveryLeaseDisposition
                    .ACQUISITION_INDETERMINATE;
        }
        if (result.outcome() == PersistenceOutcome.REJECTED) {
            return ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED;
        }
        return ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY;
    }

    private static LeaseRecord classifyBrokenLeaseResult(
            PlanId planId,
            FreshExecutionStartAttempt attempt,
            PersistenceResult<LeaseRecord> result,
            RuntimeException exception) {
        if (exception != null || result == null) {
            return null;
        }
        return switch (result.outcome()) {
            case REJECTED -> null;
            case FOUND -> throw protocol(
                    planId,
                    ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                    ExecutionStartRecoveryProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "executionStartRecovery.leaseAcquireResult.outcome",
                    ExecutionStartRecoveryLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null);
            case APPLIED, REPLAYED -> requireLeaseAuthority(
                    planId,
                    attempt,
                    result.value().orElseThrow());
        };
    }

    private static LeaseRecord requireLeaseAuthority(
            PlanId planId,
            FreshExecutionStartAttempt attempt,
            LeaseRecord lease) {
        boolean consistent = lease.planId().equals(planId)
                && lease.ownerId().equals(attempt.leaseOwnerId())
                && lease.leaseToken().equals(attempt.leaseToken())
                && lease.expiresAt().equals(attempt.leaseExpiresAt());
        if (consistent) {
            return lease;
        }
        throw protocol(
                planId,
                ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_LEASE_AUTHORITY,
                "executionStartRecovery.leaseAcquireResult.value",
                ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                null);
    }

    private StartObservation start(
            PlanId planId,
            LeaseRecord lease,
            MaterializedExecutionStart proposal,
            ExecutionStartRequest request) {
        PersistenceResult<PersistedExecutionStart> result;
        try {
            result = executionStartRepository.start(request);
        } catch (RuntimeException exception) {
            return StartObservation.indeterminate(protocol(
                    planId,
                    ExecutionStartRecoveryStage.ATOMIC_START,
                    ExecutionStartRecoveryProtocolCode.COLLABORATOR_EXCEPTION,
                    "executionStartRecovery.executionStartResult",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    exception));
        }
        if (result == null) {
            return StartObservation.indeterminate(protocol(
                    planId,
                    ExecutionStartRecoveryStage.ATOMIC_START,
                    ExecutionStartRecoveryProtocolCode.NULL_COLLABORATOR_RESULT,
                    "executionStartRecovery.executionStartResult",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null));
        }
        return switch (result.outcome()) {
            case REJECTED -> StartObservation.rejected(
                    result.failure().orElseThrow());
            case FOUND -> StartObservation.broken(protocol(
                    planId,
                    ExecutionStartRecoveryStage.ATOMIC_START,
                    ExecutionStartRecoveryProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "executionStartRecovery.executionStartResult.outcome",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null));
            case APPLIED, REPLAYED -> classifyStartSuccess(
                    planId,
                    lease,
                    proposal,
                    result.outcome(),
                    result.value().orElseThrow());
        };
    }

    private static StartObservation classifyStartSuccess(
            PlanId planId,
            LeaseRecord lease,
            MaterializedExecutionStart proposal,
            PersistenceOutcome outcome,
            PersistedExecutionStart persisted) {
        boolean consistent = persisted.planId().equals(planId)
                && persisted.leaseOwnerId().equals(lease.ownerId())
                && persisted.fencingToken() == lease.fencingToken()
                && persisted.startEvent().equals(proposal.startEvent())
                && persisted.startedCheckpoint().version() == 2
                && persisted.startedCheckpoint().checkpoint()
                        .equals(proposal.startedCheckpoint());
        if (consistent) {
            return StartObservation.success(outcome, persisted);
        }
        return StartObservation.broken(protocol(
                planId,
                ExecutionStartRecoveryStage.ATOMIC_START,
                ExecutionStartRecoveryProtocolCode
                        .INCONSISTENT_EXECUTION_START_RESULT,
                "executionStartRecovery.executionStartResult.value",
                ExecutionStartRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY,
                null));
    }

    private static ExecutionStartRecoveryOutcome reconcileStart(
            PlanId planId,
            StartObservation start,
            Inspection afterStart) {
        if (afterStart.kind() == InspectionKind.ADVANCED
                || afterStart.kind() == InspectionKind.PARTIAL
                || afterStart.kind() == InspectionKind.NOT_FOUND) {
            return stateOutcome(
                    planId,
                    afterStart,
                    ExecutionStartRecoveryStage.POST_START_INSPECT,
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
        }
        if (afterStart.kind() == InspectionKind.COMMITTED) {
            PersistedExecutionStart observed =
                    afterStart.committed().executionStart();
            return switch (start.kind()) {
                case SUCCESS -> {
                    if (!observed.equals(start.persistedStart())) {
                        throw protocol(
                                planId,
                                ExecutionStartRecoveryStage.POST_START_INSPECT,
                                ExecutionStartRecoveryProtocolCode
                                        .INCONSISTENT_INSPECTION_RESULT,
                                "executionStartRecovery"
                                        + ".postStartInspectResult.value",
                                ExecutionStartRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY,
                                null);
                    }
                    ExecutionStartRecoveryResolution resolution =
                            start.outcome() == PersistenceOutcome.APPLIED
                                    ? ExecutionStartRecoveryResolution
                                            .ATOMIC_START_APPLIED
                                    : ExecutionStartRecoveryResolution
                                            .ATOMIC_START_REPLAYED;
                    yield new RecoveredExecutionStart(
                            resolution,
                            observed,
                            ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
                }
                case REJECTED -> new RecoveredExecutionStart(
                        ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                        observed,
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
                case INDETERMINATE -> new RecoveredExecutionStart(
                        ExecutionStartRecoveryResolution
                                .RECONCILED_AFTER_RESPONSE_LOSS,
                        observed,
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
                case BROKEN -> throw new IllegalStateException(
                        "broken start must be handled before reconciliation");
            };
        }
        return switch (start.kind()) {
            case SUCCESS -> throw protocol(
                    planId,
                    ExecutionStartRecoveryStage.POST_START_INSPECT,
                    ExecutionStartRecoveryProtocolCode
                            .INCONSISTENT_INSPECTION_RESULT,
                    "executionStartRecovery.postStartInspectResult.value",
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    null);
            case REJECTED -> new ExecutionStartRecoveryRejected(
                    planId,
                    ExecutionStartRecoveryStage.ATOMIC_START,
                    start.failure(),
                    ExecutionStartRecoveryLeaseDisposition
                            .RETAINED_FOR_RECOVERY);
            case INDETERMINATE ->
                    new ExecutionStartRecoveryRetryRequired(
                            planId,
                            ExecutionStartRecoveryLeaseDisposition
                                    .RETAINED_FOR_RECOVERY);
            case BROKEN -> throw new IllegalStateException(
                    "broken start must be handled before reconciliation");
        };
    }

    private static ExecutionStartRecoveryOutcome stateOutcome(
            PlanId planId,
            Inspection inspection,
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition) {
        return switch (inspection.kind()) {
            case COMMITTED -> new RecoveredExecutionStart(
                    ExecutionStartRecoveryResolution.OBSERVED_COMMITTED,
                    inspection.committed().executionStart(),
                    leaseDisposition);
            case ADVANCED ->
                    new ExecutionStartRecoveryAdvancedUnsupported(
                            planId,
                            stage,
                            inspection.failure(),
                            leaseDisposition);
            case PARTIAL, NOT_FOUND ->
                    new ExecutionStartRecoveryRejected(
                            planId,
                            stage,
                            inspection.failure(),
                            leaseDisposition);
            case READY -> throw new IllegalStateException(
                    "READY requires execution-start processing");
        };
    }

    private static ExecutionStartRecoveryValidationException
            materializationValidation(String path) {
        return ExecutionStartRecoveryValues.validationFailure(
                ExecutionStartRecoveryValidationCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                path);
    }

    private static ExecutionStartRecoveryProtocolException protocol(
            PlanId planId,
            ExecutionStartRecoveryStage stage,
            ExecutionStartRecoveryProtocolCode code,
            String path,
            ExecutionStartRecoveryLeaseDisposition leaseDisposition,
            Throwable cause) {
        return ExecutionStartRecoveryValues.protocolFailure(
                planId,
                stage,
                code,
                path,
                leaseDisposition,
                cause);
    }

    private enum InspectionKind {
        READY,
        COMMITTED,
        ADVANCED,
        PARTIAL,
        NOT_FOUND
    }

    private record Inspection(
            InspectionKind kind,
            PersistedExecutionStartReady ready,
            PersistedExecutionStartCommitted committed,
            PersistenceFailure failure) {
        static Inspection ready(PersistedExecutionStartReady value) {
            return new Inspection(
                    InspectionKind.READY,
                    value,
                    null,
                    null);
        }

        static Inspection committed(
                PersistedExecutionStartCommitted value) {
            return new Inspection(
                    InspectionKind.COMMITTED,
                    null,
                    value,
                    null);
        }

        static Inspection advanced(PersistenceFailure failure) {
            return failed(InspectionKind.ADVANCED, failure);
        }

        static Inspection partial(PersistenceFailure failure) {
            return failed(InspectionKind.PARTIAL, failure);
        }

        static Inspection notFound(PersistenceFailure failure) {
            return failed(InspectionKind.NOT_FOUND, failure);
        }

        private static Inspection failed(
                InspectionKind kind,
                PersistenceFailure failure) {
            return new Inspection(kind, null, null, failure);
        }
    }

    private enum StartKind {
        SUCCESS,
        REJECTED,
        INDETERMINATE,
        BROKEN
    }

    private record StartObservation(
            StartKind kind,
            PersistenceOutcome outcome,
            PersistedExecutionStart persistedStart,
            PersistenceFailure failure,
            ExecutionStartRecoveryProtocolException protocolFailure) {
        static StartObservation success(
                PersistenceOutcome outcome,
                PersistedExecutionStart persisted) {
            return new StartObservation(
                    StartKind.SUCCESS,
                    outcome,
                    persisted,
                    null,
                    null);
        }

        static StartObservation rejected(PersistenceFailure failure) {
            return new StartObservation(
                    StartKind.REJECTED,
                    null,
                    null,
                    failure,
                    null);
        }

        static StartObservation indeterminate(
                ExecutionStartRecoveryProtocolException failure) {
            return new StartObservation(
                    StartKind.INDETERMINATE,
                    null,
                    null,
                    null,
                    failure);
        }

        static StartObservation broken(
                ExecutionStartRecoveryProtocolException failure) {
            return new StartObservation(
                    StartKind.BROKEN,
                    null,
                    null,
                    null,
                    failure);
        }
    }
}
