package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Map;
import java.util.Optional;

final class InMemoryStepRecoveryRepository
        implements StepRecoveryRepository {
    private static final String INSPECTION_PATH = "stepRecovery";

    private final InMemoryState state;

    InMemoryStepRecoveryRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId) {
        if (planId == null) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            if (!InMemoryExecutionMutationAuthority
                    .hasPlanScopedOccupancy(state, planId)) {
                return PersistenceChecks.notFound("planId");
            }

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
            Optional<PersistedPlanExecutionContextConfirmed> confirmed =
                    confirmedContext(source, context);
            if (confirmed == null) {
                return partialState();
            }

            PersistenceResult<StepRecoverySnapshot> inactive =
                    classifyInactive(source, confirmed);
            if (inactive != null) {
                return inactive;
            }

            InMemoryState.StepActivationMarker activation =
                    activeActivation(source);
            if (activation == null) {
                return notEligible();
            }
            if (!hasRecoverableActiveStep(source, activation)) {
                return notEligible();
            }

            return PersistenceResult.found(new PersistedStepRecoveryActive(
                    source.taskFrame(),
                    source.plan(),
                    source.checkpoint(),
                    activation.result(),
                    confirmed));
        }
    }

    private static PersistenceResult<StepRecoverySnapshot> classifyInactive(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            Optional<PersistedPlanExecutionContextConfirmed> confirmed) {
        Checkpoint checkpoint = source.checkpoint().checkpoint();
        PlanRevision revision = source.plan().latestRevision();
        boolean hasActive = checkpoint.stepStates().values().stream()
                .anyMatch(state -> state == StepExecutionState.ACTIVE);
        if (hasActive) {
            return null;
        }
        if (checkpoint.stepStates().values().stream().anyMatch(state ->
                state == StepExecutionState.PAUSED
                        || state == StepExecutionState.FAILED
                        || state == StepExecutionState.CANCELLED)) {
            return notEligible();
        }
        if (!revision.completedFacts().entrySet().stream().allMatch(entry ->
                checkpoint.stepStates().get(entry.getKey())
                        == StepExecutionState.SUCCEEDED)
                || !checkpoint.stepStates().entrySet().stream().allMatch(
                        entry -> entry.getValue() != StepExecutionState.SUCCEEDED
                                || revision.completedFacts()
                                        .containsKey(entry.getKey()))) {
            return partialState();
        }
        boolean allSucceeded = checkpoint.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);
        if (allSucceeded) {
            return checkpoint.planState() == PlanExecutionState.SUCCEEDED
                    ? PersistenceResult.found(
                            new PersistedStepRecoverySucceeded(
                                    source.taskFrame(), source.plan(),
                                    source.checkpoint(), confirmed))
                    : partialState();
        }
        if (checkpoint.planState() != PlanExecutionState.ACTIVE) {
            return partialState();
        }
        PlanStepId ready = revision.steps().stream()
                .filter(step -> checkpoint.stepStates().get(step.id())
                        == StepExecutionState.NOT_STARTED)
                .filter(step -> step.dependencies().stream().allMatch(
                        dependency -> checkpoint.stepStates().get(dependency)
                                        == StepExecutionState.SUCCEEDED
                                && revision.completedFacts()
                                        .containsKey(dependency)))
                .map(step -> step.id())
                .findFirst()
                .orElse(null);
        return ready == null
                ? partialState()
                : PersistenceResult.found(new PersistedStepRecoveryReady(
                        source.taskFrame(), source.plan(), source.checkpoint(),
                        ready, confirmed));
    }

    private static Optional<PersistedPlanExecutionContextConfirmed>
            confirmedContext(
                    InMemoryExecutionMutationAuthority.AuthoritativeSource
                            source,
                    InMemoryPlanExecutionContextAuthority.ContextCut context) {
        if (source.taskFrame().sourceProjectVersion().isEmpty()) {
            return context.status()
                    == InMemoryPlanExecutionContextAuthority.Status.NONE
                    ? Optional.empty()
                    : null;
        }
        return context.status()
                        == InMemoryPlanExecutionContextAuthority.Status.CONFIRMED
                ? Optional.of(context.confirmation())
                : null;
    }

    private static InMemoryState.StepActivationMarker activeActivation(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source) {
        if (source.links().isEmpty()) {
            return null;
        }
        InMemoryState.ExecutionMutationLink tip =
                source.links().get(source.links().size() - 1);
        if (!tip.resultHead().equals(source.head())
                || !tip.markerIdentity().equals(
                        InMemoryState.ExecutionMutationMarkerIdentity
                                .stepActivation(
                                        source.head().mutationEventId()))) {
            return null;
        }
        InMemoryState.StepActivationMarker activation =
                source.activationMarkers().get(
                        source.head().mutationEventId());
        return activation != null
                        && activation.provenanceLink().equals(tip)
                ? activation
                : null;
    }

    private static boolean hasRecoverableActiveStep(
            InMemoryExecutionMutationAuthority.AuthoritativeSource source,
            InMemoryState.StepActivationMarker activation) {
        Checkpoint checkpoint = source.checkpoint().checkpoint();
        PlanRevision revision = source.plan().latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || !source.checkpoint().equals(
                        activation.result().activatedCheckpoint())
                || !source.head().equals(
                        activation.provenanceLink().resultHead())) {
            return false;
        }

        PlanStepId activeStep = null;
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                checkpoint.stepStates().entrySet()) {
            StepExecutionState state = entry.getValue();
            if (state == StepExecutionState.ACTIVE) {
                if (activeStep != null) {
                    return false;
                }
                activeStep = entry.getKey();
            } else if (state == StepExecutionState.SUCCEEDED) {
                if (!revision.completedFacts().containsKey(entry.getKey())) {
                    return false;
                }
            } else if (state != StepExecutionState.NOT_STARTED) {
                return false;
            }
        }
        return activeStep != null
                && activeStep.equals(activation.result().stepId())
                && revision.completedFacts().entrySet().stream()
                        .allMatch(entry -> checkpoint.stepStates().get(
                                entry.getKey())
                                == StepExecutionState.SUCCEEDED);
    }

    private static PersistenceResult<StepRecoverySnapshot> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                INSPECTION_PATH);
    }

    private static PersistenceResult<StepRecoverySnapshot> notEligible() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                INSPECTION_PATH);
    }
}
