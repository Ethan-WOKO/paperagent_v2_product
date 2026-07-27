package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeterministicExecutionStartMaterializer
        implements ExecutionStartMaterializer {
    private static final long START_SEQUENCE = 1;

    @Override
    public MaterializedExecutionStart materialize(
            ExecutionStartMaterializationRequest request) {
        ExecutionStartMaterializationRequest requiredRequest =
                ExecutionStartMaterializationValues.required(
                        request,
                        "executionStartMaterializationRequest");
        PersistedPlanBootstrap bootstrap = requiredRequest.bootstrap();
        Checkpoint source = bootstrap.initialCheckpoint().checkpoint();

        CheckpointValidators.requireValid(
                source,
                bootstrap.taskFrame(),
                bootstrap.plan(),
                null);
        requireCanonicalSource(source);

        ExecutionStartEventDraft draft = requiredRequest.eventDraft();
        EventEnvelope startEvent = new EventEnvelope(
                draft.id(),
                bootstrap.taskFrame().id(),
                bootstrap.plan().id(),
                START_SEQUENCE,
                draft.occurredAt(),
                draft.type(),
                draft.causationId(),
                draft.correlationId(),
                draft.payload());

        PlanRevision latestRevision = bootstrap.plan().latestRevision();
        Map<PlanStepId, StepExecutionState> stepStates =
                new LinkedHashMap<>();
        latestRevision.steps().forEach(step ->
                stepStates.put(step.id(), StepExecutionState.NOT_STARTED));
        Checkpoint startedCheckpoint = new Checkpoint(
                bootstrap.taskFrame().id(),
                bootstrap.plan().id(),
                latestRevision.id(),
                latestRevision.number(),
                START_SEQUENCE,
                PlanExecutionState.ACTIVE,
                stepStates,
                List.of(),
                requiredRequest.checkpointCreatedAt());

        CheckpointValidators.requireValid(
                startedCheckpoint,
                bootstrap.taskFrame(),
                bootstrap.plan(),
                source);
        return new MaterializedExecutionStart(startEvent, startedCheckpoint);
    }

    private static void requireCanonicalSource(Checkpoint source) {
        if (source.lastEventSequence() != 0) {
            throw nonCanonical(
                    "executionStartMaterializationRequest.bootstrap"
                            + ".initialCheckpoint.checkpoint"
                            + ".lastEventSequence",
                    "bootstrap source event sequence must be zero");
        }
        if (source.planState() != PlanExecutionState.NOT_STARTED) {
            throw nonCanonical(
                    "executionStartMaterializationRequest.bootstrap"
                            + ".initialCheckpoint.checkpoint.planState",
                    "bootstrap source Plan must not be started");
        }
        if (source.stepStates().values().stream()
                .anyMatch(state -> state != StepExecutionState.NOT_STARTED)) {
            throw nonCanonical(
                    "executionStartMaterializationRequest.bootstrap"
                            + ".initialCheckpoint.checkpoint.stepStates",
                    "bootstrap source Steps must all be not started");
        }
        if (!source.receiptReferences().isEmpty()) {
            throw nonCanonical(
                    "executionStartMaterializationRequest.bootstrap"
                            + ".initialCheckpoint.checkpoint"
                            + ".receiptReferences",
                    "bootstrap source receipt references must be empty");
        }
    }

    private static ExecutionStartMaterializationValidationException
            nonCanonical(String path, String message) {
        return ExecutionStartMaterializationValues.failure(
                ExecutionStartMaterializationValidationCode
                        .NON_CANONICAL_BOOTSTRAP,
                path,
                message);
    }
}
