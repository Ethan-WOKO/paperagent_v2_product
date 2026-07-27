package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializationRequest;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializationValidationException;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.MaterializedStepActivation;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;

/** Composes one H0-derived, lease-fenced atomic Step activation attempt. */
public final class DefaultStepActivationComposer
        implements StepActivationComposer {
    private static final long ACTIVATION_EVENT_SEQUENCE = 2;
    private static final long ACTIVATED_CHECKPOINT_VERSION = 3;

    private final CommittedStepActivationMaterializer materializer;
    private final LeaseRepository leaseRepository;
    private final StepActivationRepository stepActivationRepository;

    public DefaultStepActivationComposer(
            CommittedStepActivationMaterializer materializer,
            LeaseRepository leaseRepository,
            StepActivationRepository stepActivationRepository) {
        this.materializer = StepActivationCompositionValues.required(
                materializer, "stepActivationComposition.materializer");
        this.leaseRepository = StepActivationCompositionValues.required(
                leaseRepository, "stepActivationComposition.leaseRepository");
        this.stepActivationRepository = StepActivationCompositionValues.required(
                stepActivationRepository,
                "stepActivationComposition.stepActivationRepository");
    }

    @Override
    public StepActivationCompositionOutcome compose(
            StepActivationCompositionRequest request) {
        StepActivationCompositionRequest requiredRequest =
                StepActivationCompositionValues.required(
                        request, "stepActivationComposition.request");
        PersistedExecutionStartCommitted committed = requiredRequest.committedStart();
        PlanId planId = committed.planId();
        PlanStepId stepId = requiredRequest.stepId();
        StepActivationAttempt attempt = requiredRequest.attempt();

        MaterializedStepActivation materialized = materialize(
                planId, committed, stepId, attempt);
        validateMaterialization(planId, committed, stepId, attempt, materialized);

        PersistenceResult<LeaseRecord> leaseResult = acquire(planId, attempt);
        if (leaseResult.outcome() == PersistenceOutcome.REJECTED) {
            var failure = leaseResult.failure().orElse(null);
            if (failure == null) {
                throw protocol(
                        planId,
                        StepActivationCompositionStage.LEASE_ACQUIRE,
                        StepActivationCompositionProtocolCode
                                .INCONSISTENT_LEASE_AUTHORITY,
                        "stepActivationComposition.leaseAcquireResult.failure",
                        StepActivationLeaseDisposition.NOT_ACQUIRED,
                        null);
            }
            return new StepActivationLeaseRejected(
                    planId,
                    failure,
                    StepActivationLeaseDisposition.NOT_ACQUIRED);
        }
        LeaseRecord returnedLease = leaseResult.value().orElse(null);
        if (returnedLease == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.LEASE_ACQUIRE,
                    StepActivationCompositionProtocolCode.INCONSISTENT_LEASE_AUTHORITY,
                    "stepActivationComposition.leaseAcquireResult.value",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        LeaseRecord lease = validateLease(planId, attempt, returnedLease);

        StepActivationRequest activationRequest = activationRequest(
                committed, stepId, materialized, lease);
        return activate(planId, stepId, lease, materialized, activationRequest);
    }

    private MaterializedStepActivation materialize(
            PlanId planId,
            PersistedExecutionStartCommitted committed,
            PlanStepId stepId,
            StepActivationAttempt attempt) {
        try {
            return materializer.materialize(
                    new CommittedStepActivationMaterializationRequest(
                            committed,
                            stepId,
                            attempt.eventDraft(),
                            attempt.checkpointCreatedAt()));
        } catch (CommittedStepActivationMaterializationValidationException exception) {
            throw exception;
        } catch (ContractViolationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.MATERIALIZE,
                    StepActivationCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                    "stepActivationComposition.materializeResult",
                    StepActivationLeaseDisposition.NO_LEASE_ACTION,
                    exception);
        }
    }

    private static void validateMaterialization(
            PlanId planId,
            PersistedExecutionStartCommitted committed,
            PlanStepId stepId,
            StepActivationAttempt attempt,
            MaterializedStepActivation materialized) {
        if (materialized == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.MATERIALIZE,
                    StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                    "stepActivationComposition.materializeResult",
                    StepActivationLeaseDisposition.NO_LEASE_ACTION,
                    null);
        }
        if (!expectedEvent(committed, attempt.eventDraft(), materialized.activationEvent())
                || !expectedCheckpoint(
                        committed,
                        stepId,
                        attempt.checkpointCreatedAt(),
                        materialized.activatedCheckpoint())) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.MATERIALIZE,
                    StepActivationCompositionProtocolCode
                            .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                    "stepActivationComposition.materializeResult.value",
                    StepActivationLeaseDisposition.NO_LEASE_ACTION,
                    null);
        }
    }

    private static boolean expectedEvent(
            PersistedExecutionStartCommitted committed,
            StepActivationEventDraft draft,
            EventEnvelope event) {
        return event != null
                && event.id().equals(draft.id())
                && event.taskFrameId().equals(committed.bootstrap().taskFrame().id())
                && event.planId().equals(committed.planId())
                && event.sequence() == ACTIVATION_EVENT_SEQUENCE
                && event.occurredAt().equals(draft.occurredAt())
                && event.type().equals(draft.type())
                && event.causationId().equals(draft.causationId())
                && event.correlationId().equals(draft.correlationId())
                && event.payload().equals(draft.payload());
    }

    private static boolean expectedCheckpoint(
            PersistedExecutionStartCommitted committed,
            PlanStepId selectedStepId,
            java.time.Instant createdAt,
            Checkpoint materializedCheckpoint) {
        if (materializedCheckpoint == null) {
            return false;
        }
        Checkpoint h0 = committed.executionStart().startedCheckpoint().checkpoint();
        if (!materializedCheckpoint.taskFrameId().equals(h0.taskFrameId())
                || !materializedCheckpoint.planId().equals(h0.planId())
                || !materializedCheckpoint.revisionId().equals(h0.revisionId())
                || materializedCheckpoint.revisionNumber() != h0.revisionNumber()
                || materializedCheckpoint.lastEventSequence() != ACTIVATION_EVENT_SEQUENCE
                || materializedCheckpoint.planState() != h0.planState()
                || !materializedCheckpoint.receiptReferences().equals(h0.receiptReferences())
                || !materializedCheckpoint.createdAt().equals(createdAt)
                || !materializedCheckpoint.stepStates().keySet().equals(h0.stepStates().keySet())
                || h0.stepStates().get(selectedStepId)
                        != StepExecutionState.NOT_STARTED
                || materializedCheckpoint.stepStates().get(selectedStepId)
                        != StepExecutionState.ACTIVE) {
            return false;
        }
        for (var entry : h0.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(selectedStepId)
                    ? StepExecutionState.ACTIVE : entry.getValue();
            if (materializedCheckpoint.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private PersistenceResult<LeaseRecord> acquire(
            PlanId planId,
            StepActivationAttempt attempt) {
        PersistenceResult<LeaseRecord> result;
        try {
            result = leaseRepository.acquire(
                    planId,
                    attempt.leaseOwnerId(),
                    attempt.leaseToken(),
                    attempt.leaseExpiresAt());
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.LEASE_ACQUIRE,
                    StepActivationCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                    "stepActivationComposition.leaseAcquireResult",
                    StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE,
                    exception);
        }
        if (result == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.LEASE_ACQUIRE,
                    StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                    "stepActivationComposition.leaseAcquireResult",
                    StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE,
                    null);
        }
        if (result.outcome() == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.LEASE_ACQUIRE,
                    StepActivationCompositionProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "stepActivationComposition.leaseAcquireResult.outcome",
                    StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE,
                    null);
        }
        return switch (result.outcome()) {
            case REJECTED -> result;
            case FOUND -> throw protocol(
                    planId,
                    StepActivationCompositionStage.LEASE_ACQUIRE,
                    StepActivationCompositionProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "stepActivationComposition.leaseAcquireResult.outcome",
                    StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE,
                    null);
            case APPLIED, REPLAYED -> result;
        };
    }

    private static LeaseRecord validateLease(
            PlanId planId,
            StepActivationAttempt attempt,
            LeaseRecord lease) {
        if (!lease.planId().equals(planId)
                || !lease.ownerId().equals(attempt.leaseOwnerId())
                || !lease.leaseToken().equals(attempt.leaseToken())
                || !lease.expiresAt().equals(attempt.leaseExpiresAt())) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.LEASE_ACQUIRE,
                    StepActivationCompositionProtocolCode.INCONSISTENT_LEASE_AUTHORITY,
                    "stepActivationComposition.leaseAcquireResult.value",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        return lease;
    }

    private static StepActivationRequest activationRequest(
            PersistedExecutionStartCommitted committed,
            PlanStepId stepId,
            MaterializedStepActivation materialized,
            LeaseRecord lease) {
        Checkpoint h0 = committed.executionStart().startedCheckpoint().checkpoint();
        return new StepActivationRequest(
                committed.planId(),
                lease.leaseToken(),
                lease.fencingToken(),
                h0.revisionId(),
                h0.revisionNumber(),
                committed.executionStart().startedCheckpoint().version(),
                committed.executionStart().startEvent().sequence(),
                stepId,
                materialized.activationEvent(),
                materialized.activatedCheckpoint());
    }

    private StepActivationCompositionOutcome activate(
            PlanId planId,
            PlanStepId stepId,
            LeaseRecord lease,
            MaterializedStepActivation materialized,
            StepActivationRequest request) {
        PersistenceResult<PersistedStepActivation> result;
        try {
            result = stepActivationRepository.activate(request);
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.ATOMIC_ACTIVATION,
                    StepActivationCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                    "stepActivationComposition.activationResult",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    exception);
        }
        if (result == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.ATOMIC_ACTIVATION,
                    StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                    "stepActivationComposition.activationResult",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        if (result.outcome() == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.ATOMIC_ACTIVATION,
                    StepActivationCompositionProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "stepActivationComposition.activationResult.outcome",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        return switch (result.outcome()) {
            case REJECTED -> {
                var failure = result.failure().orElse(null);
                if (failure == null) {
                    throw protocol(
                            planId,
                            StepActivationCompositionStage.ATOMIC_ACTIVATION,
                            StepActivationCompositionProtocolCode
                                    .INCONSISTENT_ACTIVATION_RESULT,
                            "stepActivationComposition.activationResult.failure",
                            StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                            null);
                }
                yield new StepActivationPersistenceRejected(
                        planId,
                        failure,
                        StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
            }
            case FOUND -> throw protocol(
                    planId,
                    StepActivationCompositionStage.ATOMIC_ACTIVATION,
                    StepActivationCompositionProtocolCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                    "stepActivationComposition.activationResult.outcome",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
            case APPLIED, REPLAYED -> committed(
                    planId, stepId, lease, materialized, result);
        };
    }

    private static StepActivationCommitted committed(
            PlanId planId,
            PlanStepId stepId,
            LeaseRecord lease,
            MaterializedStepActivation materialized,
            PersistenceResult<PersistedStepActivation> result) {
        PersistedStepActivation persisted = result.value().orElse(null);
        if (persisted == null) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.ATOMIC_ACTIVATION,
                    StepActivationCompositionProtocolCode.INCONSISTENT_ACTIVATION_RESULT,
                    "stepActivationComposition.activationResult.value",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        if (!persisted.planId().equals(planId)
                || !persisted.stepId().equals(stepId)
                || !persisted.leaseOwnerId().equals(lease.ownerId())
                || persisted.fencingToken() != lease.fencingToken()
                || !persisted.activationEvent().equals(materialized.activationEvent())
                || persisted.activatedCheckpoint().version()
                        != ACTIVATED_CHECKPOINT_VERSION
                || !persisted.activatedCheckpoint().checkpoint()
                        .equals(materialized.activatedCheckpoint())) {
            throw protocol(
                    planId,
                    StepActivationCompositionStage.ATOMIC_ACTIVATION,
                    StepActivationCompositionProtocolCode.INCONSISTENT_ACTIVATION_RESULT,
                    "stepActivationComposition.activationResult.value",
                    StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                    null);
        }
        return new StepActivationCommitted(
                result.outcome(),
                persisted,
                StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static StepActivationCompositionProtocolException protocol(
            PlanId planId,
            StepActivationCompositionStage stage,
            StepActivationCompositionProtocolCode code,
            String path,
            StepActivationLeaseDisposition disposition,
            Throwable cause) {
        return StepActivationCompositionValues.protocolFailure(
                planId, stage, code, path, disposition, cause);
    }
}
