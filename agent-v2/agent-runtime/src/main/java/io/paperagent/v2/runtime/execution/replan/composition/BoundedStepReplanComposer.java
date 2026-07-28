package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopNoEffect;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

/** Composes one fenced active-Step replan after an eligible bounded-loop stall. */
@FunctionalInterface
public interface BoundedStepReplanComposer {
    BoundedStepReplanCompositionOutcome compose(
            RecoveredActiveStep recoveredActiveStep,
            BoundedStepAgentLoopTurnLimitReached turnLimitReached,
            ActiveStepReplanRequest activeStepReplanRequest);

    /**
     * Composes a replan from a genuine no-effect outcome.
     *
     * <p>The default preserves source compatibility for existing functional
     * implementations. Implementations supporting no-effect replans override
     * this method explicitly; the outcome is never converted to a turn-limit
     * outcome.</p>
     */
    default BoundedStepReplanCompositionOutcome composeNoEffect(
            RecoveredActiveStep recoveredActiveStep,
            BoundedStepAgentLoopNoEffect noEffect,
            ActiveStepReplanRequest activeStepReplanRequest) {
        throw new UnsupportedOperationException(
                "genuine no-effect replan composition is not supported");
    }
}
