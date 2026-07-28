package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceFailure;

import java.util.Objects;
import java.util.Optional;

/** Bounded and sanitized result of one persistent loop call. */
public record PersistentPlanAgentLoopOutcome(
        PlanId planId,
        int cyclesAttempted,
        PersistentPlanAgentLoopState state,
        Optional<PlanStepId> stepId,
        Optional<PersistentPlanAgentLoopCut> cut,
        Optional<PersistentPlanAgentLoopReplanEvidence> replan,
        Optional<PersistenceFailure> failure) {

    public PersistentPlanAgentLoopOutcome {
        Objects.requireNonNull(planId, "planId");
        if (cyclesAttempted < 0) {
            throw new IllegalArgumentException(
                    "cyclesAttempted must not be negative");
        }
        Objects.requireNonNull(state, "state");
        stepId = Objects.requireNonNull(stepId, "stepId");
        cut = Objects.requireNonNull(cut, "cut");
        replan = Objects.requireNonNull(replan, "replan");
        failure = Objects.requireNonNull(failure, "failure");
    }

    @Override
    public String toString() {
        return "PersistentPlanAgentLoopOutcome["
                + "planId=<provided>, cyclesAttempted="
                + cyclesAttempted + ", state=" + state
                + ", stepId=<provided>, cut=<provided>, "
                + "replan=<provided>, failure=" + failure + "]";
    }
}
