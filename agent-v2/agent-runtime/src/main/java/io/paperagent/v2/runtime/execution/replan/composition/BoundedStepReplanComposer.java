package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

/** Composes one fenced active-Step replan after a bounded-loop turn limit. */
@FunctionalInterface
public interface BoundedStepReplanComposer {
    BoundedStepReplanCompositionOutcome compose(
            RecoveredActiveStep recoveredActiveStep,
            BoundedStepAgentLoopTurnLimitReached turnLimitReached,
            ActiveStepReplanRequest activeStepReplanRequest);
}
