package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class InMemoryExecutionStartRecoveryRepository
        implements ExecutionStartRecoveryRepository {
    private final InMemoryState state;

    InMemoryExecutionStartRecoveryRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<ExecutionStartRecoverySnapshot> inspect(PlanId planId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            if (!InMemoryExecutionMutationAuthority
                    .hasPlanScopedOccupancy(state, planId)) {
                return PersistenceChecks.notFound("planId");
            }

            PersistedPlanBootstrap bootstrap = state.planBootstraps.get(planId);
            Plan currentPlan = state.plans.get(planId);
            if (!hasConsistentBootstrapRoot(planId, bootstrap, currentPlan)) {
                return partialState();
            }
            if (!InMemoryPlanExecutionContextAuthority
                    .hasValidGlobalIndex(state)) {
                return partialState();
            }

            boolean hasStartMarker = state.executionStarts.containsKey(planId);
            if (!hasStartMarker) {
                if (!InMemoryPlanExecutionContextAuthority
                                .isStrictPreStart(
                                        state,
                                        planId,
                                        bootstrap,
                                        currentPlan)
                        || hasContextOccupancy(planId)) {
                    return partialState();
                }
                return PersistenceResult.found(
                        new PersistedExecutionStartReady(bootstrap, currentPlan));
            }

            InMemoryState.ExecutionStartMarker marker =
                    state.executionStarts.get(planId);
            InMemoryExecutionMutationAuthority.AuthoritativeSource source =
                    InMemoryExecutionMutationAuthority
                            .validateAuthoritativeSource(state, planId);
            if (source == null) {
                return partialState();
            }
            InMemoryPlanExecutionContextAuthority.ContextCut context =
                    InMemoryPlanExecutionContextAuthority.inspect(
                            state, planId, source);
            if (context.status()
                    == InMemoryPlanExecutionContextAuthority.Status.PARTIAL) {
                return partialState();
            }

            if (source.links().isEmpty()
                    && source.activationMarkers().isEmpty()
                    && isStrictCommitted(
                            planId, currentPlan, marker.result())) {
                return PersistenceResult.found(
                        new PersistedExecutionStartCommitted(
                                bootstrap, currentPlan, marker.result()));
            }
            if (!source.links().isEmpty()) {
                return context.status()
                                        == InMemoryPlanExecutionContextAuthority
                                                .Status.CONFIRMED
                                || source.taskFrame()
                                        .sourceProjectVersion()
                                        .isEmpty()
                                        && context.status()
                                                == InMemoryPlanExecutionContextAuthority
                                                        .Status.NONE
                        ? advancedState()
                        : partialState();
            }
            return partialState();
        }
    }

    private boolean hasConsistentBootstrapRoot(
            PlanId planId,
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan) {
        if (bootstrap == null
                || currentPlan == null
                || !planId.equals(bootstrap.plan().id())
                || !planId.equals(currentPlan.id())) {
            return false;
        }
        TaskFrame bootstrapTaskFrame = bootstrap.taskFrame();
        if (!bootstrapTaskFrame.equals(
                        state.taskFrames.get(bootstrapTaskFrame.id()))
                || !bootstrapTaskFrame.id().equals(bootstrap.plan().taskFrameId())
                || !bootstrapTaskFrame.id().equals(currentPlan.taskFrameId())
                || !isExactPrefix(
                        bootstrap.plan().revisions(), currentPlan.revisions())) {
            return false;
        }
        return isCanonicalBootstrapCheckpoint(bootstrap);
    }

    private static boolean isCanonicalBootstrapCheckpoint(
            PersistedPlanBootstrap bootstrap) {
        VersionedCheckpoint source = bootstrap.initialCheckpoint();
        if (source.version() != 1) {
            return false;
        }
        Checkpoint checkpoint = source.checkpoint();
        Plan bootstrapPlan = bootstrap.plan();
        PlanRevision latest = bootstrapPlan.latestRevision();
        return checkpoint.taskFrameId().equals(bootstrap.taskFrame().id())
                && checkpoint.planId().equals(bootstrapPlan.id())
                && matchesRevision(latest, checkpoint)
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && hasExactStepShape(
                        checkpoint, latest, StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && latest.completedFacts().isEmpty();
    }

    private boolean isStrictCommitted(
            PlanId planId,
            Plan currentPlan,
            PersistedExecutionStart executionStart) {
        var stream = state.eventStreams.get(planId);
        Checkpoint started = executionStart.startedCheckpoint().checkpoint();
        return stream.size() == 1
                && executionStart.startedCheckpoint().equals(
                        state.checkpoints.get(planId))
                && matchesRevision(currentPlan.latestRevision(), started);
    }

    private boolean hasContextOccupancy(PlanId planId) {
        return state.planExecutionContextReservations.containsKey(planId)
                || state.planExecutionContextConfirmations.containsKey(planId)
                || InMemoryPlanExecutionContextAuthority
                        .hasOwnerReference(state, planId);
    }

    private static boolean hasExactStepShape(
            Checkpoint checkpoint,
            PlanRevision revision,
            StepExecutionState expectedState) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == expectedState);
    }

    private static boolean matchesRevision(
            PlanRevision revision,
            Checkpoint checkpoint) {
        return revision.id().equals(checkpoint.revisionId())
                && revision.number() == checkpoint.revisionNumber();
    }

    private static boolean isExactPrefix(
            List<?> prefix,
            List<?> values) {
        return values.size() >= prefix.size()
                && values.subList(0, prefix.size()).equals(prefix);
    }

    private static PersistenceResult<ExecutionStartRecoverySnapshot> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    private static PersistenceResult<ExecutionStartRecoverySnapshot> advancedState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");
    }
}
