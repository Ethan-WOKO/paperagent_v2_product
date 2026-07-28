package com.yanban.api.agent.v2.execution;

import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

/** Caller-owned lease attempt for one authenticated persisted Step turn. */
public record AuthenticatedAgentTurnStepTurnCommand(
        StepRecoveryLeaseAttempt recoveryAttempt) {
    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepTurnCommand[recoveryAttempt=<redacted>]";
    }
}
