package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionEventDraft;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationProtocolException;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationValidationException;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedActiveStepInterruption;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepCancellation;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepFailure;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepPause;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Instant;
import java.util.Map;

/** Composes one already-fenced active-Step interruption persistence attempt. */
public final class DefaultActiveStepInterruptionComposer
        implements ActiveStepInterruptionComposer {
    private static final long INTERRUPTION_EVENT_SEQUENCE = 3;
    private static final long INTERRUPTED_CHECKPOINT_VERSION = 4;

    private final ActiveStepInterruptionMaterializer materializer;
    private final StepInterruptionRepository repository;

    public DefaultActiveStepInterruptionComposer(
            ActiveStepInterruptionMaterializer materializer,
            StepInterruptionRepository repository) {
        this.materializer = ActiveStepInterruptionCompositionValues.required(
                materializer,
                "activeStepInterruptionComposition.materializer");
        this.repository = ActiveStepInterruptionCompositionValues.required(
                repository,
                "activeStepInterruptionComposition.repository");
    }

    @Override
    public ActiveStepInterruptionCompositionOutcome compose(
            ActiveStepInterruptionMaterializationRequest request) {
        ActiveStepInterruptionMaterializationRequest requiredRequest =
                ActiveStepInterruptionCompositionValues.required(
                        request, "activeStepInterruptionComposition.request");
        PlanId planId = requiredRequest.recoveredActiveStep()
                .recovery().planId();
        MaterializedActiveStepInterruption materialized =
                materialize(planId, requiredRequest);
        MaterializedAuthority authority =
                validateMaterialization(planId, requiredRequest, materialized);
        PersistenceResult<PersistedStepInterruption> result =
                persist(planId, materialized);
        return classify(requiredRequest, authority, result);
    }

    private MaterializedActiveStepInterruption materialize(
            PlanId planId,
            ActiveStepInterruptionMaterializationRequest request) {
        try {
            MaterializedActiveStepInterruption result =
                    materializer.materialize(request);
            if (result == null) {
                throw protocol(
                        planId,
                        ActiveStepInterruptionCompositionStage.MATERIALIZE,
                        ActiveStepInterruptionCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "activeStepInterruptionComposition.materialization",
                        null);
            }
            return result;
        } catch (ActiveStepInterruptionMaterializationValidationException
                | ActiveStepInterruptionMaterializationProtocolException
                        exception) {
            throw exception;
        } catch (ActiveStepInterruptionCompositionProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    ActiveStepInterruptionCompositionStage.MATERIALIZE,
                    ActiveStepInterruptionCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    "activeStepInterruptionComposition.materialization",
                    exception);
        }
    }

    private PersistenceResult<PersistedStepInterruption> persist(
            PlanId planId,
            MaterializedActiveStepInterruption materialized) {
        try {
            PersistenceResult<PersistedStepInterruption> result;
            if (materialized instanceof MaterializedStepPause pause) {
                result = repository.pause(pause.request());
            } else if (materialized instanceof MaterializedStepFailure failure) {
                result = repository.fail(failure.request());
            } else if (materialized
                    instanceof MaterializedStepCancellation cancellation) {
                result = repository.cancel(cancellation.request());
            } else {
                throw protocol(
                        planId,
                        ActiveStepInterruptionCompositionStage.MATERIALIZE,
                        ActiveStepInterruptionCompositionProtocolCode
                                .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                        "activeStepInterruptionComposition.materialization"
                                + ".value",
                        null);
            }
            if (result == null) {
                throw protocol(
                        planId,
                        ActiveStepInterruptionCompositionStage.PERSIST,
                        ActiveStepInterruptionCompositionProtocolCode
                                .NULL_COLLABORATOR_RESULT,
                        "activeStepInterruptionComposition.persistence",
                        null);
            }
            return result;
        } catch (ActiveStepInterruptionCompositionProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocol(
                    planId,
                    ActiveStepInterruptionCompositionStage.PERSIST,
                    ActiveStepInterruptionCompositionProtocolCode
                            .COLLABORATOR_EXCEPTION,
                    "activeStepInterruptionComposition.persistence",
                    exception);
        }
    }

    private static ActiveStepInterruptionCompositionOutcome classify(
            ActiveStepInterruptionMaterializationRequest request,
            MaterializedAuthority materialized,
            PersistenceResult<PersistedStepInterruption> result) {
        PlanId planId = materialized.planId();
        PersistenceOutcome outcome = result.outcome();
        if (outcome == null) {
            throw protocol(
                    planId,
                    ActiveStepInterruptionCompositionStage.PERSIST,
                    ActiveStepInterruptionCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "activeStepInterruptionComposition.persistence.outcome",
                    null);
        }
        return switch (outcome) {
            case FOUND -> throw protocol(
                    planId,
                    ActiveStepInterruptionCompositionStage.PERSIST,
                    ActiveStepInterruptionCompositionProtocolCode
                            .UNEXPECTED_PERSISTENCE_OUTCOME,
                    "activeStepInterruptionComposition.persistence.outcome",
                    null);
            case REJECTED -> {
                if (result.failure().isEmpty() || result.value().isPresent()) {
                    throw inconsistent(
                            planId,
                            "activeStepInterruptionComposition.persistence"
                                    + ".failure");
                }
                yield new ActiveStepInterruptionPersistenceRejected(
                        planId,
                        result.failure().orElseThrow(),
                        ActiveStepInterruptionLeaseDisposition
                                .RETAINED_FOR_RECOVERY);
            }
            case APPLIED, REPLAYED -> committed(
                    request, materialized, result);
        };
    }

    private static ActiveStepInterruptionCommitted committed(
            ActiveStepInterruptionMaterializationRequest request,
            MaterializedAuthority materialized,
            PersistenceResult<PersistedStepInterruption> result) {
        PersistedStepInterruption persisted = result.value().orElse(null);
        if (persisted == null || result.failure().isPresent()) {
            throw inconsistent(
                    materialized.planId(),
                    "activeStepInterruptionComposition.persistence.value");
        }
        LeaseRecord lease = request.recoveredActiveStep().lease();
        if (!persisted.planId().equals(materialized.planId())
                || !persisted.stepId().equals(materialized.stepId())
                || persisted.kind() != materialized.kind()
                || !persisted.leaseOwnerId().equals(lease.ownerId())
                || persisted.fencingToken() != lease.fencingToken()
                || !persisted.interruptionEvent().equals(materialized.event())
                || persisted.interruptedCheckpoint().version()
                        != INTERRUPTED_CHECKPOINT_VERSION
                || !persisted.interruptedCheckpoint().checkpoint()
                        .equals(materialized.checkpoint())) {
            throw inconsistent(
                    materialized.planId(),
                    "activeStepInterruptionComposition.persistence.value");
        }
        return new ActiveStepInterruptionCommitted(
                result.outcome(),
                persisted,
                ActiveStepInterruptionLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static MaterializedAuthority validateMaterialization(
            PlanId planId,
            ActiveStepInterruptionMaterializationRequest input,
            MaterializedActiveStepInterruption materialized) {
        MaterializedAuthority authority = authority(materialized);
        RecoveredActiveStep recovered = input.recoveredActiveStep();
        var active = recovered.recovery();
        var current = active.checkpoint();
        LeaseRecord lease = recovered.lease();
        PlanStepId stepId = active.activation().stepId();
        boolean exact = recovered.leaseDisposition()
                        == StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY
                && input.kind() == materialized.kind()
                && authority.planId().equals(planId)
                && authority.stepId().equals(stepId)
                && authority.leaseToken().equals(lease.leaseToken())
                && authority.fencingToken() == lease.fencingToken()
                && authority.expectedRevisionId().equals(
                        current.checkpoint().revisionId())
                && authority.expectedRevisionNumber()
                        == current.checkpoint().revisionNumber()
                && authority.expectedCheckpointVersion() == current.version()
                && authority.expectedEventHeadSequence()
                        == current.checkpoint().lastEventSequence()
                && expectedEvent(input, active.taskFrame().id(), planId,
                        authority.event())
                && expectedCheckpoint(
                        input,
                        current.checkpoint(),
                        stepId,
                        authority.checkpoint());
        if (!exact) {
            throw protocol(
                    planId,
                    ActiveStepInterruptionCompositionStage.MATERIALIZE,
                    ActiveStepInterruptionCompositionProtocolCode
                            .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                    "activeStepInterruptionComposition.materialization.value",
                    null);
        }
        return authority;
    }

    private static MaterializedAuthority authority(
            MaterializedActiveStepInterruption materialized) {
        if (materialized instanceof MaterializedStepPause value) {
            StepPauseRequest request = value.request();
            return new MaterializedAuthority(
                    request.planId(), request.stepId(), value.kind(),
                    request.leaseToken(), request.fencingToken(),
                    request.expectedRevisionId(),
                    request.expectedRevisionNumber(),
                    request.expectedCheckpointVersion(),
                    request.expectedEventHeadSequence(),
                    request.pauseEvent(), request.pausedCheckpoint());
        }
        if (materialized instanceof MaterializedStepFailure value) {
            StepFailRequest request = value.request();
            return new MaterializedAuthority(
                    request.planId(), request.stepId(), value.kind(),
                    request.leaseToken(), request.fencingToken(),
                    request.expectedRevisionId(),
                    request.expectedRevisionNumber(),
                    request.expectedCheckpointVersion(),
                    request.expectedEventHeadSequence(),
                    request.failureEvent(), request.failedCheckpoint());
        }
        if (materialized instanceof MaterializedStepCancellation value) {
            StepCancelRequest request = value.request();
            return new MaterializedAuthority(
                    request.planId(), request.stepId(), value.kind(),
                    request.leaseToken(), request.fencingToken(),
                    request.expectedRevisionId(),
                    request.expectedRevisionNumber(),
                    request.expectedCheckpointVersion(),
                    request.expectedEventHeadSequence(),
                    request.cancellationEvent(),
                    request.cancelledCheckpoint());
        }
        throw new IllegalStateException("sealed interruption kind is unknown");
    }

    private static boolean expectedEvent(
            ActiveStepInterruptionMaterializationRequest input,
            io.paperagent.v2.contracts.TaskFrameId taskFrameId,
            PlanId planId,
            EventEnvelope event) {
        ActiveStepInterruptionEventDraft draft = input.eventDraft();
        return event != null
                && event.id().equals(draft.id())
                && event.taskFrameId().equals(taskFrameId)
                && event.planId().equals(planId)
                && event.sequence() == INTERRUPTION_EVENT_SEQUENCE
                && event.occurredAt().equals(draft.occurredAt())
                && event.type().equals(draft.type())
                && event.causationId().equals(draft.causationId())
                && event.correlationId().equals(draft.correlationId())
                && event.payload().equals(draft.payload());
    }

    private static boolean expectedCheckpoint(
            ActiveStepInterruptionMaterializationRequest input,
            Checkpoint current,
            PlanStepId stepId,
            Checkpoint interrupted) {
        if (interrupted == null
                || !interrupted.taskFrameId().equals(current.taskFrameId())
                || !interrupted.planId().equals(current.planId())
                || !interrupted.revisionId().equals(current.revisionId())
                || interrupted.revisionNumber() != current.revisionNumber()
                || interrupted.lastEventSequence()
                        != INTERRUPTION_EVENT_SEQUENCE
                || !interrupted.createdAt().equals(input.checkpointCreatedAt())
                || !interrupted.receiptReferences().equals(
                        current.receiptReferences())
                || !interrupted.stepStates().keySet().equals(
                        current.stepStates().keySet())
                || interrupted.planState() != planState(input.kind())) {
            return false;
        }
        for (Map.Entry<PlanStepId, StepExecutionState> state
                : current.stepStates().entrySet()) {
            StepExecutionState expected = state.getKey().equals(stepId)
                    ? stepState(input.kind()) : state.getValue();
            if (interrupted.stepStates().get(state.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static StepExecutionState stepState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    private static PlanExecutionState planState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    private static ActiveStepInterruptionCompositionProtocolException
            inconsistent(PlanId planId, String path) {
        return protocol(
                planId,
                ActiveStepInterruptionCompositionStage.PERSIST,
                ActiveStepInterruptionCompositionProtocolCode
                        .INCONSISTENT_PERSISTENCE_RESULT,
                path,
                null);
    }

    private static ActiveStepInterruptionCompositionProtocolException protocol(
            PlanId planId,
            ActiveStepInterruptionCompositionStage stage,
            ActiveStepInterruptionCompositionProtocolCode code,
            String path,
            Throwable cause) {
        return new ActiveStepInterruptionCompositionProtocolException(
                planId, stage, code, path, cause);
    }

    private record MaterializedAuthority(
            PlanId planId,
            PlanStepId stepId,
            StepInterruptionKind kind,
            String leaseToken,
            long fencingToken,
            io.paperagent.v2.contracts.PlanRevisionId expectedRevisionId,
            long expectedRevisionNumber,
            long expectedCheckpointVersion,
            long expectedEventHeadSequence,
            EventEnvelope event,
            Checkpoint checkpoint) {
    }
}
