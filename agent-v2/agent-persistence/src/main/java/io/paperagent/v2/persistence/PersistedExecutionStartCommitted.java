package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record PersistedExecutionStartCommitted(
        PersistedPlanBootstrap bootstrap,
        Plan currentPlan,
        PersistedExecutionStart executionStart)
        implements ExecutionStartRecoverySnapshot {

    public PersistedExecutionStartCommitted {
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(executionStart, "executionStart");
        PersistedExecutionStartReady.requireBootstrapBinding(bootstrap, currentPlan);
        requireExecutionStartBinding(bootstrap, currentPlan, executionStart);
    }

    @Override
    public PlanId planId() {
        return executionStart.planId();
    }

    @Override
    public String toString() {
        return "PersistedExecutionStartCommitted[bootstrap=<provided>, "
                + "currentPlan=<provided>, executionStart=<provided>]";
    }

    private static void requireExecutionStartBinding(
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan,
            PersistedExecutionStart executionStart) {
        EventEnvelope event = executionStart.startEvent();
        Checkpoint checkpoint = executionStart.startedCheckpoint().checkpoint();
        Checkpoint bootstrapCheckpoint =
                bootstrap.initialCheckpoint().checkpoint();
        PlanRevision latest = currentPlan.latestRevision();
        Set<?> latestStepIds = latest.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        if (!executionStart.planId().equals(currentPlan.id())
                || !event.planId().equals(currentPlan.id())
                || !event.taskFrameId().equals(bootstrap.taskFrame().id())
                || event.sequence() != 1
                || executionStart.startedCheckpoint().version() != 2
                || !checkpoint.planId().equals(currentPlan.id())
                || !checkpoint.taskFrameId().equals(bootstrap.taskFrame().id())
                || checkpoint.lastEventSequence() != 1
                || checkpoint.planState() != PlanExecutionState.ACTIVE
                || !isNotBeforeBootstrap(checkpoint, bootstrapCheckpoint)
                || !matchesRevision(latest, checkpoint)
                || !latest.completedFacts().isEmpty()
                || !checkpoint.stepStates().keySet().equals(latestStepIds)
                || checkpoint.stepStates().values().stream()
                        .anyMatch(state -> state != StepExecutionState.NOT_STARTED)
                || !checkpoint.receiptReferences().isEmpty()) {
            throw new IllegalArgumentException(
                    "executionStart must be structurally bound to the persisted Plan");
        }
    }

    private static boolean isNotBeforeBootstrap(
            Checkpoint started,
            Checkpoint bootstrap) {
        boolean revisionIsNotBefore =
                started.revisionNumber() > bootstrap.revisionNumber()
                        || started.revisionNumber()
                                        == bootstrap.revisionNumber()
                                && started.revisionId()
                                        .equals(bootstrap.revisionId());
        return revisionIsNotBefore
                && !started.createdAt().isBefore(bootstrap.createdAt());
    }

    private static boolean matchesRevision(
            PlanRevision revision,
            Checkpoint checkpoint) {
        return revision.id().equals(checkpoint.revisionId())
                && revision.number() == checkpoint.revisionNumber();
    }
}
