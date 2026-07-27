package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

/** The already fenced authority and caller-selected upper bound for one loop run. */
public record BoundedStepAgentLoopRequest(
        RecoveredActiveStep recoveredStep,
        int maxTurns) {

    public BoundedStepAgentLoopRequest {
        recoveredStep = BoundedStepAgentLoopValues.required(
                recoveredStep, "boundedStepAgentLoopRequest.recoveredStep");
        maxTurns = BoundedStepAgentLoopValues.maxTurns(
                maxTurns, "boundedStepAgentLoopRequest.maxTurns");
    }

    @Override
    public String toString() {
        return "BoundedStepAgentLoopRequest[recoveredStep=<provided>, maxTurns="
                + maxTurns + "]";
    }
}
