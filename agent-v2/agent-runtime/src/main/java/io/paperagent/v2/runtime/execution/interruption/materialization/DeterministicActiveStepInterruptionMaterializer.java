package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DeterministicActiveStepInterruptionMaterializer
        implements ActiveStepInterruptionMaterializer {
    private static final long ACTIVE_CHECKPOINT_VERSION = 3;
    private static final long ACTIVE_EVENT_SEQUENCE = 2;
    private static final long INTERRUPTION_EVENT_SEQUENCE = 3;

    public DeterministicActiveStepInterruptionMaterializer() {
    }

    @Override
    public MaterializedActiveStepInterruption materialize(
            ActiveStepInterruptionMaterializationRequest request) {
        ActiveStepInterruptionMaterializationRequest requiredRequest =
                ActiveStepInterruptionMaterializationValues.required(
                        request,
                        "activeStepInterruptionMaterializationRequest");
        Authority authority = requireAuthority(
                requiredRequest.recoveredActiveStep());
        if (requiredRequest.checkpointCreatedAt()
                .isBefore(authority.current().createdAt())) {
            throw ActiveStepInterruptionMaterializationValues.validation(
                    ActiveStepInterruptionMaterializationValidationCode
                            .CHECKPOINT_TIME_REGRESSION,
                    ActiveStepInterruptionMaterializationStage.CHECKPOINT,
                    "activeStepInterruptionMaterializationRequest"
                            + ".checkpointCreatedAt");
        }

        ActiveStepInterruptionEventDraft draft =
                requiredRequest.eventDraft();
        EventEnvelope event = new EventEnvelope(
                draft.id(),
                authority.taskFrame().id(),
                authority.plan().id(),
                INTERRUPTION_EVENT_SEQUENCE,
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload());
        StepInterruptionKind kind = requiredRequest.kind();
        Checkpoint interruptedCheckpoint = interruptedCheckpoint(
                authority, kind, requiredRequest.checkpointCreatedAt());
        if (!CheckpointValidators.validate(
                        interruptedCheckpoint,
                        authority.taskFrame(),
                        authority.plan(),
                        authority.current())
                .isEmpty()) {
            throw ActiveStepInterruptionMaterializationValues.protocol(
                    ActiveStepInterruptionMaterializationProtocolCode
                            .CHECKPOINT_VALIDATION_FAILED,
                    ActiveStepInterruptionMaterializationStage.CHECKPOINT,
                    "recoveredActiveStep.recovery.checkpoint");
        }

        PlanRevision revision = authority.plan().latestRevision();
        LeaseRecord lease = authority.lease();
        return switch (kind) {
            case PAUSE -> new MaterializedStepPause(new StepPauseRequest(
                    authority.plan().id(),
                    lease.leaseToken(),
                    lease.fencingToken(),
                    revision.id(),
                    revision.number(),
                    ACTIVE_CHECKPOINT_VERSION,
                    ACTIVE_EVENT_SEQUENCE,
                    authority.stepId(),
                    event,
                    interruptedCheckpoint));
            case FAIL -> new MaterializedStepFailure(new StepFailRequest(
                    authority.plan().id(),
                    lease.leaseToken(),
                    lease.fencingToken(),
                    revision.id(),
                    revision.number(),
                    ACTIVE_CHECKPOINT_VERSION,
                    ACTIVE_EVENT_SEQUENCE,
                    authority.stepId(),
                    event,
                    interruptedCheckpoint));
            case CANCEL -> new MaterializedStepCancellation(
                    new StepCancelRequest(
                            authority.plan().id(),
                            lease.leaseToken(),
                            lease.fencingToken(),
                            revision.id(),
                            revision.number(),
                            ACTIVE_CHECKPOINT_VERSION,
                            ACTIVE_EVENT_SEQUENCE,
                            authority.stepId(),
                            event,
                            interruptedCheckpoint));
        };
    }

    private static Authority requireAuthority(RecoveredActiveStep recovered) {
        PersistedStepRecoveryActive active = recovered.recovery();
        TaskFrame taskFrame = active.taskFrame();
        Plan plan = active.plan();
        VersionedCheckpoint versioned = active.checkpoint();
        Checkpoint checkpoint = versioned.checkpoint();
        PersistedStepActivation activation = active.activation();
        LeaseRecord lease = recovered.lease();
        PlanRevision revision = plan.latestRevision();
        PlanStepId stepId = activation.stepId();

        boolean baseConsistent =
                recovered.leaseDisposition()
                                == StepRecoveryLeaseDisposition
                                        .RETAINED_FOR_RECOVERY
                        && plan.id().equals(active.planId())
                        && plan.taskFrameId().equals(taskFrame.id())
                        && versioned.version() == ACTIVE_CHECKPOINT_VERSION
                        && checkpoint.taskFrameId().equals(taskFrame.id())
                        && checkpoint.planId().equals(plan.id())
                        && checkpoint.revisionId().equals(revision.id())
                        && checkpoint.revisionNumber() == revision.number()
                        && checkpoint.lastEventSequence()
                                == ACTIVE_EVENT_SEQUENCE
                        && checkpoint.planState() == PlanExecutionState.ACTIVE
                        && lease.planId().equals(plan.id())
                        && activation.planId().equals(plan.id())
                        && activation.activatedCheckpoint().version()
                                == ACTIVE_CHECKPOINT_VERSION
                        && activation.activatedCheckpoint().equals(versioned)
                        && activation.activationEvent().planId()
                                .equals(plan.id())
                        && activation.activationEvent().taskFrameId()
                                .equals(taskFrame.id())
                        && activation.activationEvent().sequence()
                                == ACTIVE_EVENT_SEQUENCE
                        && revision.steps().stream()
                                .anyMatch(step -> step.id().equals(stepId))
                        && checkpoint.stepStates().keySet().equals(
                                revision.steps().stream()
                                        .map(step -> step.id())
                                        .collect(java.util.stream.Collectors
                                                .toSet()))
                        && CheckpointValidators.validate(
                                        checkpoint, taskFrame, plan, null)
                                .isEmpty();
        if (!baseConsistent) {
            throw authorityFailure();
        }

        if (revision.completedFacts().containsKey(stepId)
                || checkpoint.stepStates().get(stepId)
                        != StepExecutionState.ACTIVE) {
            throw stepFailure();
        }
        int activeCount = 0;
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : checkpoint.stepStates().entrySet()) {
            StepExecutionState state = entry.getValue();
            if (state == StepExecutionState.ACTIVE) {
                activeCount++;
            }
            if (!entry.getKey().equals(stepId)
                    && state != StepExecutionState.NOT_STARTED
                    && state != StepExecutionState.SUCCEEDED) {
                throw stepFailure();
            }
        }
        if (activeCount != 1) {
            throw stepFailure();
        }
        return new Authority(
                taskFrame, plan, checkpoint, activation.stepId(), lease);
    }

    private static Checkpoint interruptedCheckpoint(
            Authority authority,
            StepInterruptionKind kind,
            java.time.Instant createdAt) {
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(authority.current().stepStates());
        states.put(authority.stepId(), stepState(kind));
        return new Checkpoint(
                authority.current().taskFrameId(),
                authority.current().planId(),
                authority.current().revisionId(),
                authority.current().revisionNumber(),
                INTERRUPTION_EVENT_SEQUENCE,
                planState(kind),
                states,
                authority.current().receiptReferences(),
                createdAt);
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

    private static ActiveStepInterruptionMaterializationProtocolException
            authorityFailure() {
        return ActiveStepInterruptionMaterializationValues.protocol(
                ActiveStepInterruptionMaterializationProtocolCode
                        .INCONSISTENT_RECOVERED_AUTHORITY,
                ActiveStepInterruptionMaterializationStage.RECOVERED_AUTHORITY,
                "recoveredActiveStep");
    }

    private static ActiveStepInterruptionMaterializationProtocolException
            stepFailure() {
        return ActiveStepInterruptionMaterializationValues.protocol(
                ActiveStepInterruptionMaterializationProtocolCode
                        .STEP_NOT_ELIGIBLE,
                ActiveStepInterruptionMaterializationStage.RECOVERED_AUTHORITY,
                "recoveredActiveStep.recovery.activation.stepId");
    }

    private record Authority(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint current,
            PlanStepId stepId,
            LeaseRecord lease) {
    }
}
