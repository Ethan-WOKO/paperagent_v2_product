package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;

import java.util.Optional;

public record ExecutionStartRecoveryRequest(
        PlanId planId,
        Optional<FreshExecutionStartAttempt> attempt) {

    public ExecutionStartRecoveryRequest {
        ExecutionStartRecoveryValues.required(
                planId,
                "executionStartRecovery.request.planId");
        attempt = ExecutionStartRecoveryValues.required(
                attempt,
                "executionStartRecovery.request.attempt");
    }

    @Override
    public String toString() {
        return "ExecutionStartRecoveryRequest[planId=<provided>, attempt="
                + (attempt.isPresent() ? "<provided>]" : "<empty>]");
    }
}
