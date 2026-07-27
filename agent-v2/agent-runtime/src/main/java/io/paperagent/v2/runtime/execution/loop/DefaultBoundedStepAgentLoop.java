package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnIntentPersisted;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnPersistenceRejected;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelOutcome;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded sequencer for an already fenced Step authority.
 *
 * <p>The loop delegates every turn to the single-turn kernel and deliberately owns no
 * Persistence, lease, effect execution, or recovery behavior.
 */
public final class DefaultBoundedStepAgentLoop implements BoundedStepAgentLoop {
    private final SingleTurnStepKernel singleTurnStepKernel;

    public DefaultBoundedStepAgentLoop(SingleTurnStepKernel singleTurnStepKernel) {
        this.singleTurnStepKernel = BoundedStepAgentLoopValues.required(
                singleTurnStepKernel, "boundedStepAgentLoop.singleTurnStepKernel");
    }

    @Override
    public BoundedStepAgentLoopOutcome run(BoundedStepAgentLoopRequest request) {
        BoundedStepAgentLoopRequest requiredRequest = BoundedStepAgentLoopValues.required(
                request, "boundedStepAgentLoop.request");
        Authority authority = authority(requiredRequest.recoveredStep());
        List<PersistedEffectIntent> persistedIntents = new ArrayList<>();

        for (int turnsExecuted = 1; turnsExecuted <= requiredRequest.maxTurns(); turnsExecuted++) {
            SingleTurnStepKernelOutcome kernelOutcome = runTurn(authority);
            verifyAuthority(authority, kernelOutcome);
            if (kernelOutcome instanceof SingleTurnIntentPersisted intentPersisted) {
                persistedIntents.add(intentPersisted.persistedIntent());
                if (turnsExecuted == requiredRequest.maxTurns()) {
                    return new BoundedStepAgentLoopTurnLimitReached(
                            authority.planId(), authority.stepId(), turnsExecuted, persistedIntents);
                }
                continue;
            }
            if (kernelOutcome instanceof SingleTurnNoEffect) {
                return new BoundedStepAgentLoopNoEffect(
                        authority.planId(), authority.stepId(), turnsExecuted, persistedIntents);
            }
            if (kernelOutcome instanceof SingleTurnPersistenceRejected rejected) {
                return new BoundedStepAgentLoopPersistenceRejected(
                        authority.planId(),
                        authority.stepId(),
                        turnsExecuted,
                        persistedIntents,
                        rejected.failure());
            }
            throw protocol(
                    authority,
                    BoundedStepAgentLoopStage.KERNEL_OUTCOME,
                    BoundedStepAgentLoopProtocolCode.UNKNOWN_KERNEL_OUTCOME,
                    "boundedStepAgentLoop.kernelOutcome",
                    null);
        }
        throw new AssertionError("validated maxTurns must execute at least one turn");
    }

    private SingleTurnStepKernelOutcome runTurn(Authority authority) {
        SingleTurnStepKernelOutcome outcome;
        try {
            outcome = singleTurnStepKernel.run(
                    new SingleTurnStepKernelRequest(authority.recoveredStep()));
        } catch (RuntimeException exception) {
            throw protocol(
                    authority,
                    BoundedStepAgentLoopStage.KERNEL_TURN,
                    BoundedStepAgentLoopProtocolCode.COLLABORATOR_EXCEPTION,
                    "boundedStepAgentLoop.kernelRun",
                    exception);
        }
        if (outcome == null) {
            throw protocol(
                    authority,
                    BoundedStepAgentLoopStage.KERNEL_TURN,
                    BoundedStepAgentLoopProtocolCode.NULL_COLLABORATOR_RESULT,
                    "boundedStepAgentLoop.kernelRun",
                    null);
        }
        return outcome;
    }

    private static void verifyAuthority(
            Authority authority,
            SingleTurnStepKernelOutcome outcome) {
        if (!authority.planId().equals(outcome.planId())
                || !authority.stepId().equals(outcome.stepId())) {
            throw protocol(
                    authority,
                    BoundedStepAgentLoopStage.KERNEL_OUTCOME,
                    BoundedStepAgentLoopProtocolCode.INCONSISTENT_OUTCOME_AUTHORITY,
                    "boundedStepAgentLoop.kernelOutcome",
                    null);
        }
    }

    private static Authority authority(RecoveredActiveStep recoveredStep) {
        return new Authority(
                recoveredStep,
                recoveredStep.planId(),
                recoveredStep.recovery().activation().stepId());
    }

    private static BoundedStepAgentLoopProtocolException protocol(
            Authority authority,
            BoundedStepAgentLoopStage stage,
            BoundedStepAgentLoopProtocolCode code,
            String path,
            Throwable cause) {
        return BoundedStepAgentLoopValues.protocolFailure(
                authority.planId(), authority.stepId(), stage, code, path, cause);
    }

    private record Authority(
            RecoveredActiveStep recoveredStep,
            PlanId planId,
            PlanStepId stepId) {
    }
}
