package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;

public final class DeterministicCommittedStepActivationMaterializer
        implements CommittedStepActivationMaterializer {

    public DeterministicCommittedStepActivationMaterializer() {
    }

    @Override
    public MaterializedStepActivation materialize(
            CommittedStepActivationMaterializationRequest request) {
        CommittedStepActivationMaterializationRequest requiredRequest =
                CommittedStepActivationMaterializationValues.required(
                request,
                "committedStepActivationMaterializationRequest");
        PersistedExecutionStartCommitted committed =
                requiredRequest.committedStart();
        Checkpoint committedH0 = committed.executionStart()
                .startedCheckpoint()
                .checkpoint();
        PlanRevision latestRevision =
                committed.currentPlan().latestRevision();
        PlanStepId selectedStepId = requiredRequest.stepId();
        PlanStep selectedStep = null;
        for (PlanStep step : latestRevision.steps()) {
            if (step.id().equals(selectedStepId)) {
                selectedStep = step;
                break;
            }
        }

        boolean dependenciesReady = selectedStep != null;
        if (selectedStep != null) {
            for (PlanStepId dependency : selectedStep.dependencies()) {
                if (committedH0.stepStates().get(dependency)
                                != StepExecutionState.SUCCEEDED
                        || !latestRevision.completedFacts()
                                .containsKey(dependency)) {
                    dependenciesReady = false;
                    break;
                }
            }
        }

        boolean otherStatesPermitActivation = true;
        for (var entry : committedH0.stepStates().entrySet()) {
            StepExecutionState state = entry.getValue();
            if (state == StepExecutionState.FAILED
                    || state == StepExecutionState.CANCELLED
                    || !entry.getKey().equals(selectedStepId)
                            && (state == StepExecutionState.ACTIVE
                                    || state
                                            == StepExecutionState.PAUSED)) {
                otherStatesPermitActivation = false;
                break;
            }
        }

        boolean selectedStepReady = selectedStep != null
                && committedH0.planState() == PlanExecutionState.ACTIVE
                && committedH0.stepStates().get(selectedStepId)
                        == StepExecutionState.NOT_STARTED
                && !latestRevision.completedFacts()
                        .containsKey(selectedStepId)
                && dependenciesReady
                && otherStatesPermitActivation;
        if (!selectedStepReady) {
            throw CommittedStepActivationMaterializationValues
                    .stepNotEligible();
        }

        StepActivationEventDraft draft = requiredRequest.eventDraft();
        EventEnvelope activationEvent = new EventEnvelope(
                draft.id(),
                committed.bootstrap().taskFrame().id(),
                committed.currentPlan().id(),
                2,
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload());

        var activatedStates =
                new java.util.LinkedHashMap<PlanStepId, StepExecutionState>(
                        committedH0.stepStates());
        activatedStates.put(selectedStepId, StepExecutionState.ACTIVE);
        Checkpoint activatedCheckpoint = new Checkpoint(
                committedH0.taskFrameId(),
                committedH0.planId(),
                committedH0.revisionId(),
                committedH0.revisionNumber(),
                2,
                committedH0.planState(),
                activatedStates,
                committedH0.receiptReferences(),
                requiredRequest.checkpointCreatedAt());
        CheckpointValidators.requireValid(
                activatedCheckpoint,
                committed.bootstrap().taskFrame(),
                committed.currentPlan(),
                committedH0);

        return new MaterializedStepActivation(
                activationEvent,
                activatedCheckpoint);
    }
}
