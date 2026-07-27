package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;

/**
 * Caller-owned selection and attempt material for one authenticated activation.
 *
 * <p>Validation belongs to the authenticated service so ownership resolution
 * remains the first observable operation.</p>
 */
public record AuthenticatedAgentTurnStepActivationCommand(
        PlanStepId stepId,
        StepActivationAttempt attempt) {

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepActivationCommand["
                + "stepId=<provided>, attempt=<redacted>]";
    }
}
