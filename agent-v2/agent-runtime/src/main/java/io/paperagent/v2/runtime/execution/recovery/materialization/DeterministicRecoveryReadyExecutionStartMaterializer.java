package io.paperagent.v2.runtime.execution.recovery.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeterministicRecoveryReadyExecutionStartMaterializer
        implements RecoveryReadyExecutionStartMaterializer {
    @Override
    public MaterializedExecutionStart materialize(
            RecoveryReadyExecutionStartMaterializationRequest request) {
        RecoveryReadyExecutionStartMaterializationRequest requiredRequest =
                RecoveryReadyExecutionStartMaterializationValues.required(
                        request,
                        "recoveryReadyExecutionStartMaterializationRequest");
        var ready = requiredRequest.ready();
        var bootstrap = ready.bootstrap();
        var currentPlan = ready.currentPlan();
        Checkpoint source = bootstrap.initialCheckpoint().checkpoint();

        CheckpointValidators.requireValid(
                source,
                bootstrap.taskFrame(),
                bootstrap.plan(),
                null);
        requireCanonicalSource(source);

        PlanRevision currentLatest = currentPlan.latestRevision();
        if (!currentLatest.completedFacts().isEmpty()) {
            throw nonCanonical(
                    "recoveryReadyExecutionStartMaterializationRequest"
                            + ".ready.currentPlan.latestRevision.completedFacts",
                    "current latest revision completion facts must be empty");
        }

        ExecutionStartEventDraft draft = requiredRequest.eventDraft();
        EventEnvelope startEvent = new EventEnvelope(
                draft.id(),
                bootstrap.taskFrame().id(),
                currentPlan.id(),
                1,
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload());

        Map<PlanStepId, StepExecutionState> stepStates =
                new LinkedHashMap<>();
        currentLatest.steps().forEach(step ->
                stepStates.put(step.id(), StepExecutionState.NOT_STARTED));
        Checkpoint startedCheckpoint = new Checkpoint(
                bootstrap.taskFrame().id(),
                currentPlan.id(),
                currentLatest.id(),
                currentLatest.number(),
                1,
                PlanExecutionState.ACTIVE,
                stepStates,
                List.of(),
                requiredRequest.checkpointCreatedAt());

        CheckpointValidators.requireValid(
                startedCheckpoint,
                bootstrap.taskFrame(),
                currentPlan,
                source);
        return new MaterializedExecutionStart(startEvent, startedCheckpoint);
    }

    private static void requireCanonicalSource(Checkpoint source) {
        if (source.lastEventSequence() != 0) {
            throw nonCanonical(
                    "recoveryReadyExecutionStartMaterializationRequest"
                            + ".ready.bootstrap.initialCheckpoint.checkpoint"
                            + ".lastEventSequence",
                    "bootstrap source event sequence must be zero");
        }
        if (source.planState() != PlanExecutionState.NOT_STARTED) {
            throw nonCanonical(
                    "recoveryReadyExecutionStartMaterializationRequest"
                            + ".ready.bootstrap.initialCheckpoint.checkpoint"
                            + ".planState",
                    "bootstrap source Plan must not be started");
        }
        if (source.stepStates().values().stream()
                .anyMatch(state -> state != StepExecutionState.NOT_STARTED)) {
            throw nonCanonical(
                    "recoveryReadyExecutionStartMaterializationRequest"
                            + ".ready.bootstrap.initialCheckpoint.checkpoint"
                            + ".stepStates",
                    "bootstrap source Steps must all be not started");
        }
        if (!source.receiptReferences().isEmpty()) {
            throw nonCanonical(
                    "recoveryReadyExecutionStartMaterializationRequest"
                            + ".ready.bootstrap.initialCheckpoint.checkpoint"
                            + ".receiptReferences",
                    "bootstrap source receipt references must be empty");
        }
    }

    private static RecoveryReadyExecutionStartMaterializationValidationException
            nonCanonical(String path, String message) {
        return RecoveryReadyExecutionStartMaterializationValues.failure(
                RecoveryReadyExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_READY_SNAPSHOT,
                path,
                message);
    }
}
