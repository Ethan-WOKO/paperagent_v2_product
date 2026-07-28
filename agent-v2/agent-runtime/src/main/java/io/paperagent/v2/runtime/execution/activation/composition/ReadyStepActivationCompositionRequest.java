package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.persistence.PersistedStepRecoveryReady;

import java.util.Objects;

public record ReadyStepActivationCompositionRequest(
        PersistedStepRecoveryReady ready,
        StepActivationAttempt attempt) {

    public ReadyStepActivationCompositionRequest {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(attempt, "attempt");
    }

    @Override
    public String toString() {
        return "ReadyStepActivationCompositionRequest["
                + "ready=<provided>, attempt=<provided>]";
    }
}
