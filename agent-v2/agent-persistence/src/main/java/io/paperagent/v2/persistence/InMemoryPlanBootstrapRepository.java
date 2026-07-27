package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.Set;
import java.util.stream.Collectors;

final class InMemoryPlanBootstrapRepository implements PlanBootstrapRepository {
    private final InMemoryState state;

    InMemoryPlanBootstrapRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedPlanBootstrap> bootstrap(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint) {
        if (PersistenceChecks.missing(taskFrame)) {
            return PersistenceChecks.invalid("taskFrame");
        }
        if (PersistenceChecks.missing(plan)) {
            return PersistenceChecks.invalid("plan");
        }
        if (PersistenceChecks.missing(checkpoint)) {
            return PersistenceChecks.invalid("checkpoint");
        }
        synchronized (state.monitor) {
            PersistedPlanBootstrap existing = state.planBootstraps.get(plan.id());
            if (existing != null) {
                boolean exactReplay = existing.taskFrame().equals(taskFrame)
                        && existing.plan().equals(plan)
                        && existing.initialCheckpoint().checkpoint().equals(checkpoint);
                return exactReplay
                        ? PersistenceResult.replayed(existing)
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY, "plan.id");
            }
            if (state.taskFrames.containsKey(taskFrame.id())
                    || state.plans.containsKey(plan.id())
                    || state.checkpoints.containsKey(plan.id())
                    || state.planExecutionContextReservations
                            .containsKey(plan.id())
                    || state.planExecutionContextConfirmations
                            .containsKey(plan.id())
                    || InMemoryPlanExecutionContextAuthority
                            .hasOwnerReference(state, plan.id())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.BOOTSTRAP_PARTIAL_STATE, "bootstrap");
            }
            if (!plan.taskFrameId().equals(taskFrame.id())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.TASK_FRAME_MISMATCH, "plan.taskFrameId");
            }
            if (!CheckpointValidators.validate(checkpoint, taskFrame, plan, null).isEmpty()
                    || !isInitialShape(checkpoint, plan.latestRevision())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED, "checkpoint");
            }

            VersionedCheckpoint initialCheckpoint = new VersionedCheckpoint(1, checkpoint);
            PersistedPlanBootstrap bootstrap =
                    new PersistedPlanBootstrap(taskFrame, plan, initialCheckpoint);
            state.taskFrames.put(taskFrame.id(), taskFrame);
            state.plans.put(plan.id(), plan);
            state.checkpoints.put(plan.id(), initialCheckpoint);
            state.planBootstraps.put(plan.id(), bootstrap);
            return PersistenceResult.applied(bootstrap);
        }
    }

    private static boolean isInitialShape(Checkpoint checkpoint, PlanRevision latestRevision) {
        Set<PlanStepId> latestStepIds = latestRevision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(latestStepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(state -> state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }
}
