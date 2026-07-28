package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure dynamic-head activation materializer for a persisted READY cut. */
public final class DeterministicReadyStepActivationMaterializer {
    public MaterializedStepActivation materialize(
            ReadyStepActivationMaterializationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        PersistedStepRecoveryReady ready = request.ready();
        Checkpoint source = ready.checkpoint().checkpoint();
        PlanStepId selected = deterministicReadyStep(ready);
        if (!canonicalReadyAuthority(ready)
                || selected == null || !selected.equals(ready.readyStepId())
                || source.lastEventSequence() == Long.MAX_VALUE
                || ready.checkpoint().version() == Long.MAX_VALUE
                || request.eventDraft().occurredAt()
                        .isBefore(source.createdAt())
                || request.checkpointCreatedAt().isBefore(source.createdAt())) {
            throw new IllegalArgumentException(
                    "ready activation authority is not eligible");
        }
        long nextSequence = source.lastEventSequence() + 1;
        StepActivationEventDraft draft = request.eventDraft();
        EventEnvelope event = new EventEnvelope(
                draft.id(), ready.taskFrame().id(), ready.plan().id(),
                nextSequence, draft.occurredAt(), draft.type(),
                draft.causationId(), draft.correlationId(), draft.payload());
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(source.stepStates());
        states.put(selected, StepExecutionState.ACTIVE);
        Checkpoint activated = new Checkpoint(
                source.taskFrameId(), source.planId(), source.revisionId(),
                source.revisionNumber(), nextSequence,
                PlanExecutionState.ACTIVE, states,
                source.receiptReferences(), request.checkpointCreatedAt());
        CheckpointValidators.requireValid(
                activated, ready.taskFrame(), ready.plan(), source);
        return new MaterializedStepActivation(event, activated);
    }

    private static boolean canonicalReadyAuthority(
            PersistedStepRecoveryReady ready) {
        Checkpoint checkpoint = ready.checkpoint().checkpoint();
        var revision = ready.plan().latestRevision();
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return ready.plan().taskFrameId().equals(ready.taskFrame().id())
                && checkpoint.taskFrameId().equals(ready.taskFrame().id())
                && checkpoint.planId().equals(ready.plan().id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && ready.checkpoint().version() >= 2
                && checkpoint.stepStates().keySet().equals(stepIds)
                && revision.completedFacts().entrySet().stream().allMatch(
                        entry -> checkpoint.stepStates().get(entry.getKey())
                                == StepExecutionState.SUCCEEDED)
                && checkpoint.stepStates().entrySet().stream().allMatch(
                        entry -> entry.getValue()
                                        != StepExecutionState.SUCCEEDED
                                || revision.completedFacts()
                                        .containsKey(entry.getKey()))
                && CheckpointValidators.validate(
                        checkpoint, ready.taskFrame(), ready.plan(), null)
                        .isEmpty();
    }

    private static PlanStepId deterministicReadyStep(
            PersistedStepRecoveryReady ready) {
        Checkpoint checkpoint = ready.checkpoint().checkpoint();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || checkpoint.stepStates().values().stream().anyMatch(
                        state -> state == StepExecutionState.ACTIVE
                                || state == StepExecutionState.PAUSED
                                || state == StepExecutionState.FAILED
                                || state == StepExecutionState.CANCELLED)) {
            return null;
        }
        for (PlanStep step : ready.plan().latestRevision().steps()) {
            if (checkpoint.stepStates().get(step.id())
                            == StepExecutionState.NOT_STARTED
                    && !ready.plan().latestRevision().completedFacts()
                            .containsKey(step.id())
                    && step.dependencies().stream().allMatch(dependency ->
                            checkpoint.stepStates().get(dependency)
                                            == StepExecutionState.SUCCEEDED
                                    && ready.plan().latestRevision()
                                            .completedFacts()
                                            .containsKey(dependency))) {
                return step.id();
            }
        }
        return null;
    }
}
