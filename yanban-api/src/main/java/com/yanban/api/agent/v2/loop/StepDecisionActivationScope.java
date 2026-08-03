package com.yanban.api.agent.v2.loop;

import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import java.util.concurrent.atomic.AtomicReference;

/** One-kernel activation handoff; never shared across kernel instances. */
final class StepDecisionActivationScope {
    private final AtomicReference<Cut> current = new AtomicReference<>();

    void capture(SingleTurnStepKernelRequest request) {
        var activation = request.recoveredStep().recovery().activation();
        current.set(new Cut(
                activation.activationEvent().id().value(),
                activation.activationEvent().sequence(),
                activation.activatedCheckpoint().version()));
    }

    Cut require() {
        Cut value = current.get();
        if (value == null) {
            throw new StepModelCallGuardException(
                    "STEP_CONTEXT_ACTIVATION_MISSING");
        }
        return value;
    }

    void clear() {
        current.set(null);
    }

    record Cut(String eventId, long sequence, long checkpointVersion) {}
}
