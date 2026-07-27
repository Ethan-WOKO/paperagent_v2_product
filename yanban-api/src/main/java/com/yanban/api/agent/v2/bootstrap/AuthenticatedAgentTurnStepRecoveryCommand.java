package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

/**
 * Caller-owned lease attempt for one authenticated active-Step recovery.
 *
 * <p>Validation belongs to the authenticated service so ownership resolution
 * remains the first observable operation.</p>
 */
public record AuthenticatedAgentTurnStepRecoveryCommand(
        StepRecoveryLeaseAttempt attempt) {

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepRecoveryCommand[attempt=<redacted>]";
    }
}
