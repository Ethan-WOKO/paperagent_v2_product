package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.BootstrapRejected;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.ExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.ExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.FreshExecutionDecision;
import io.paperagent.v2.runtime.execution.FreshExecutionGate;
import io.paperagent.v2.runtime.execution.FreshLeaseAdmissionEligible;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.RecoveryRequired;

public final class DefaultFreshExecutionStarter
        implements FreshExecutionStarter {
    private static final long START_EVENT_SEQUENCE = 1;
    private static final long START_CHECKPOINT_VERSION = 2;

    private final FreshExecutionGate freshExecutionGate;
    private final ExecutionStartMaterializer executionStartMaterializer;
    private final LeaseRepository leaseRepository;
    private final ExecutionStartRepository executionStartRepository;

    public DefaultFreshExecutionStarter(
            FreshExecutionGate freshExecutionGate,
            ExecutionStartMaterializer executionStartMaterializer,
            LeaseRepository leaseRepository,
            ExecutionStartRepository executionStartRepository) {
        this.freshExecutionGate = FreshExecutionStartValues.required(
                freshExecutionGate,
                "freshExecutionStart.freshExecutionGate");
        this.executionStartMaterializer = FreshExecutionStartValues.required(
                executionStartMaterializer,
                "freshExecutionStart.executionStartMaterializer");
        this.leaseRepository = FreshExecutionStartValues.required(
                leaseRepository,
                "freshExecutionStart.leaseRepository");
        this.executionStartRepository = FreshExecutionStartValues.required(
                executionStartRepository,
                "freshExecutionStart.executionStartRepository");
    }

    @Override
    public FreshExecutionStartOutcome start(FreshExecutionStartRequest request) {
        FreshExecutionStartRequest requiredRequest =
                FreshExecutionStartValues.required(
                        request,
                        "freshExecutionStart.request");
        PersistenceResult<PersistedPlanBootstrap> bootstrapResult =
                requiredRequest.bootstrapResult();

        FreshExecutionDecision decision =
                freshExecutionGate.evaluate(bootstrapResult);
        validateGateDecision(bootstrapResult, decision);

        if (decision instanceof RecoveryRequired recoveryRequired) {
            return new FreshExecutionRecoveryRequired(
                    recoveryRequired.planId());
        }
        if (decision instanceof BootstrapRejected bootstrapRejected) {
            return new FreshExecutionBootstrapRejected(
                    bootstrapRejected.failure());
        }

        FreshLeaseAdmissionEligible eligible =
                (FreshLeaseAdmissionEligible) decision;
        FreshExecutionStartAttempt attempt = requiredRequest.attempt()
                .orElseThrow(() -> FreshExecutionStartValues.failure(
                        FreshExecutionStartValidationCode
                                .REQUIRED_VALUE_MISSING,
                        "freshExecutionStart.request.attempt",
                        "fresh execution start attempt is required"));
        PersistedPlanBootstrap bootstrap =
                bootstrapResult.value().orElseThrow();

        MaterializedExecutionStart materialized =
                executionStartMaterializer.materialize(
                        new ExecutionStartMaterializationRequest(
                                bootstrap,
                                attempt.eventDraft(),
                                attempt.checkpointCreatedAt()));
        validateMaterializedStart(bootstrap, attempt, materialized);

        PersistenceResult<LeaseRecord> leaseResult;
        try {
            leaseResult = leaseRepository.acquire(
                    eligible.planId(),
                    attempt.leaseOwnerId(),
                    attempt.leaseToken(),
                    attempt.leaseExpiresAt());
        } catch (RuntimeException exception) {
            throw protocolFailure(
                    eligible.planId(),
                    FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                    FreshExecutionStartProtocolCode.COLLABORATOR_EXCEPTION,
                    "freshExecutionStart.leaseAcquireResult",
                    FreshExecutionLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    exception);
        }

        LeaseRecord lease = classifyLeaseResult(
                eligible.planId(),
                attempt,
                leaseResult);
        if (lease == null) {
            PersistenceFailure failure =
                    leaseResult.failure().orElseThrow();
            return new FreshLeaseAcquisitionRejected(
                    eligible.planId(),
                    failure);
        }

        ExecutionStartRequest startRequest = new ExecutionStartRequest(
                lease.planId(),
                lease.leaseToken(),
                lease.fencingToken(),
                materialized.startEvent(),
                materialized.startedCheckpoint());
        PersistenceResult<PersistedExecutionStart> startResult;
        try {
            startResult = executionStartRepository.start(startRequest);
        } catch (RuntimeException exception) {
            throw protocolFailure(
                    eligible.planId(),
                    FreshExecutionStartProtocolStage.ATOMIC_START,
                    FreshExecutionStartProtocolCode.COLLABORATOR_EXCEPTION,
                    "freshExecutionStart.executionStartResult",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                    exception);
        }

        return classifyExecutionStartResult(
                eligible.planId(),
                lease,
                materialized,
                startResult);
    }

    private static void validateGateDecision(
            PersistenceResult<PersistedPlanBootstrap> bootstrapResult,
            FreshExecutionDecision decision) {
        boolean consistent = switch (bootstrapResult.outcome()) {
            case APPLIED -> decision instanceof FreshLeaseAdmissionEligible eligible
                    && eligible.planId().equals(
                            bootstrapResult.value()
                                    .orElseThrow()
                                    .plan()
                                    .id());
            case REPLAYED -> decision instanceof RecoveryRequired recovery
                    && recovery.planId().equals(
                            bootstrapResult.value()
                                    .orElseThrow()
                                    .plan()
                                    .id());
            case REJECTED -> decision instanceof BootstrapRejected rejected
                    && rejected.failure()
                            == bootstrapResult.failure().orElseThrow();
            case FOUND -> false;
        };
        if (!consistent) {
            throw FreshExecutionStartValues.failure(
                    FreshExecutionStartValidationCode
                            .INCONSISTENT_GATE_DECISION,
                    "freshExecutionStart.gateDecision",
                    "fresh execution gate decision is inconsistent"
                            + " with the bootstrap result");
        }
    }

    private static void validateMaterializedStart(
            PersistedPlanBootstrap bootstrap,
            FreshExecutionStartAttempt attempt,
            MaterializedExecutionStart materialized) {
        if (materialized == null) {
            throw materializationFailure(
                    "freshExecutionStart.materializedStart");
        }
        if (!isExpectedEvent(
                bootstrap,
                attempt.eventDraft(),
                materialized.startEvent())) {
            throw materializationFailure(
                    "freshExecutionStart.materializedStart.startEvent");
        }
        if (!isExpectedCheckpoint(
                bootstrap,
                attempt,
                materialized.startedCheckpoint())) {
            throw materializationFailure(
                    "freshExecutionStart.materializedStart"
                            + ".startedCheckpoint");
        }
    }

    private static boolean isExpectedEvent(
            PersistedPlanBootstrap bootstrap,
            ExecutionStartEventDraft draft,
            EventEnvelope event) {
        return event != null
                && event.id().equals(draft.id())
                && event.taskFrameId().equals(bootstrap.taskFrame().id())
                && event.planId().equals(bootstrap.plan().id())
                && event.sequence() == START_EVENT_SEQUENCE
                && event.occurredAt().equals(draft.occurredAt())
                && event.type().equals(draft.type())
                && event.causationId().equals(draft.causationId())
                && event.correlationId().equals(draft.correlationId())
                && event.payload().equals(draft.payload());
    }

    private static boolean isExpectedCheckpoint(
            PersistedPlanBootstrap bootstrap,
            FreshExecutionStartAttempt attempt,
            Checkpoint checkpoint) {
        if (checkpoint == null) {
            return false;
        }
        PlanRevision latestRevision = bootstrap.plan().latestRevision();
        if (!checkpoint.taskFrameId().equals(bootstrap.taskFrame().id())
                || !checkpoint.planId().equals(bootstrap.plan().id())
                || !checkpoint.revisionId().equals(latestRevision.id())
                || checkpoint.revisionNumber() != latestRevision.number()
                || checkpoint.lastEventSequence() != START_EVENT_SEQUENCE
                || checkpoint.planState() != PlanExecutionState.ACTIVE
                || !checkpoint.receiptReferences().isEmpty()
                || !checkpoint.createdAt()
                        .equals(attempt.checkpointCreatedAt())
                || checkpoint.stepStates().size()
                        != latestRevision.steps().size()) {
            return false;
        }
        for (var step : latestRevision.steps()) {
            if (checkpoint.stepStates().get(step.id())
                    != StepExecutionState.NOT_STARTED) {
                return false;
            }
        }
        return true;
    }

    private static FreshExecutionStartValidationException
            materializationFailure(String path) {
        return FreshExecutionStartValues.failure(
                FreshExecutionStartValidationCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                path,
                "materialized execution start is inconsistent"
                        + " with bootstrap and attempt authority");
    }

    private static LeaseRecord classifyLeaseResult(
            PlanId planId,
            FreshExecutionStartAttempt attempt,
            PersistenceResult<LeaseRecord> result) {
        if (result == null) {
            throw protocolFailure(
                    planId,
                    FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                    FreshExecutionStartProtocolCode.NULL_COLLABORATOR_RESULT,
                    "freshExecutionStart.leaseAcquireResult",
                    FreshExecutionLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null);
        }
        return switch (result.outcome()) {
            case REJECTED -> null;
            case FOUND -> throw protocolFailure(
                    planId,
                    FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                    FreshExecutionStartProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "freshExecutionStart.leaseAcquireResult.outcome",
                    FreshExecutionLeaseDisposition
                            .ACQUISITION_INDETERMINATE,
                    null);
            case APPLIED, REPLAYED -> validateLeaseAuthority(
                    planId,
                    attempt,
                    result.value().orElseThrow());
        };
    }

    private static LeaseRecord validateLeaseAuthority(
            PlanId planId,
            FreshExecutionStartAttempt attempt,
            LeaseRecord lease) {
        boolean consistent = lease.planId().equals(planId)
                && lease.ownerId().equals(attempt.leaseOwnerId())
                && lease.leaseToken().equals(attempt.leaseToken())
                && lease.expiresAt().equals(attempt.leaseExpiresAt());
        if (!consistent) {
            throw protocolFailure(
                    planId,
                    FreshExecutionStartProtocolStage.LEASE_ACQUIRE,
                    FreshExecutionStartProtocolCode
                            .INCONSISTENT_LEASE_AUTHORITY,
                    "freshExecutionStart.leaseAcquireResult.value",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        return lease;
    }

    private static FreshExecutionStartOutcome classifyExecutionStartResult(
            PlanId planId,
            LeaseRecord lease,
            MaterializedExecutionStart materialized,
            PersistenceResult<PersistedExecutionStart> result) {
        if (result == null) {
            throw protocolFailure(
                    planId,
                    FreshExecutionStartProtocolStage.ATOMIC_START,
                    FreshExecutionStartProtocolCode.NULL_COLLABORATOR_RESULT,
                    "freshExecutionStart.executionStartResult",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        return switch (result.outcome()) {
            case REJECTED -> new FreshAtomicExecutionStartRejected(
                    planId,
                    result.failure().orElseThrow(),
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY);
            case FOUND -> throw protocolFailure(
                    planId,
                    FreshExecutionStartProtocolStage.ATOMIC_START,
                    FreshExecutionStartProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "freshExecutionStart.executionStartResult.outcome",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
            case APPLIED, REPLAYED -> {
                PersistedExecutionStart persisted =
                        result.value().orElseThrow();
                validatePersistedStart(
                        planId,
                        lease,
                        materialized,
                        persisted);
                yield new FreshExecutionStarted(
                        result.outcome(),
                        persisted);
            }
        };
    }

    private static void validatePersistedStart(
            PlanId planId,
            LeaseRecord lease,
            MaterializedExecutionStart materialized,
            PersistedExecutionStart persisted) {
        boolean consistent = persisted.planId().equals(planId)
                && persisted.leaseOwnerId().equals(lease.ownerId())
                && persisted.fencingToken() == lease.fencingToken()
                && persisted.startEvent().equals(materialized.startEvent())
                && persisted.startedCheckpoint().version()
                        == START_CHECKPOINT_VERSION
                && persisted.startedCheckpoint().checkpoint()
                        .equals(materialized.startedCheckpoint());
        if (!consistent) {
            throw protocolFailure(
                    planId,
                    FreshExecutionStartProtocolStage.ATOMIC_START,
                    FreshExecutionStartProtocolCode
                            .INCONSISTENT_EXECUTION_START_RESULT,
                    "freshExecutionStart.executionStartResult.value",
                    FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
    }

    private static FreshExecutionStartProtocolException protocolFailure(
            PlanId planId,
            FreshExecutionStartProtocolStage stage,
            FreshExecutionStartProtocolCode code,
            String path,
            FreshExecutionLeaseDisposition disposition,
            Throwable cause) {
        return FreshExecutionStartValues.protocolFailure(
                planId,
                stage,
                code,
                path,
                disposition,
                cause);
    }
}
