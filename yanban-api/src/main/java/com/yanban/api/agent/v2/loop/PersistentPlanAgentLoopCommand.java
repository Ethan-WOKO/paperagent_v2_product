package com.yanban.api.agent.v2.loop;

import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionActivationLeaseAttempt;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

import java.util.Optional;

/**
 * Caller-owned bounded attempts and an optional untrusted replan proposal.
 * Product identity and Plan authority are deliberately absent.
 */
public record PersistentPlanAgentLoopCommand(
        int maxCycles,
        StepRecoveryLeaseAttempt currentRecoveryAttempt,
        StepActivationAttempt readyActivationAttempt,
        EffectDrivenStepProgressionActivationLeaseAttempt
                nextStepActivationAttempt,
        Optional<ActiveStepReplanRequest> replanProposal) {

    @Override
    public String toString() {
        return "PersistentPlanAgentLoopCommand["
                + "maxCycles=" + maxCycles
                + ", currentRecoveryAttempt=<redacted>, "
                + "readyActivationAttempt=<redacted>, "
                + "nextStepActivationAttempt=<redacted>, "
                + "replanProposal=<provided>]";
    }
}
