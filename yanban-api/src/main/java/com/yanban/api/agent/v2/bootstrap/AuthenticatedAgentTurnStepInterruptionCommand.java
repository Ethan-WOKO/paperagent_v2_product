package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionEventDraft;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

import java.time.Instant;

/** Caller-owned intent for one authenticated active-Step interruption. */
public record AuthenticatedAgentTurnStepInterruptionCommand(
        StepRecoveryLeaseAttempt recoveryAttempt,
        StepInterruptionKind kind,
        ActiveStepInterruptionEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepInterruptionCommand"
                + "[recoveryAttempt=<redacted>, kind=<provided>, "
                + "eventDraft=<redacted>, checkpointCreatedAt=<provided>]";
    }
}
