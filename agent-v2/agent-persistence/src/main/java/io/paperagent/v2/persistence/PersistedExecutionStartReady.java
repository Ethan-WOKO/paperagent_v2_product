package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;

import java.util.List;
import java.util.Objects;

public record PersistedExecutionStartReady(
        PersistedPlanBootstrap bootstrap,
        Plan currentPlan)
        implements ExecutionStartRecoverySnapshot {

    public PersistedExecutionStartReady {
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(currentPlan, "currentPlan");
        requireBootstrapBinding(bootstrap, currentPlan);
    }

    @Override
    public PlanId planId() {
        return bootstrap.plan().id();
    }

    @Override
    public String toString() {
        return "PersistedExecutionStartReady[bootstrap=<provided>, currentPlan=<provided>]";
    }

    static void requireBootstrapBinding(
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan) {
        if (!bootstrap.taskFrame().id().equals(bootstrap.plan().taskFrameId())
                || !bootstrap.plan().id().equals(currentPlan.id())
                || !bootstrap.plan().taskFrameId().equals(currentPlan.taskFrameId())) {
            throw new IllegalArgumentException(
                    "bootstrap and currentPlan identifiers must be consistent");
        }
        List<?> bootstrapRevisions = bootstrap.plan().revisions();
        List<?> currentRevisions = currentPlan.revisions();
        if (currentRevisions.size() < bootstrapRevisions.size()
                || !currentRevisions
                        .subList(0, bootstrapRevisions.size())
                        .equals(bootstrapRevisions)) {
            throw new IllegalArgumentException(
                    "bootstrap Plan must be an exact currentPlan prefix");
        }
        var checkpoint = bootstrap.initialCheckpoint().checkpoint();
        PlanRevision bootstrapLatest = bootstrap.plan().latestRevision();
        if (!checkpoint.planId().equals(bootstrap.plan().id())
                || !checkpoint.taskFrameId().equals(bootstrap.taskFrame().id())
                || !checkpoint.revisionId().equals(bootstrapLatest.id())
                || checkpoint.revisionNumber() != bootstrapLatest.number()) {
            throw new IllegalArgumentException(
                    "bootstrap initial checkpoint must be bound to the bootstrap root");
        }
    }
}
