package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.persistence.PersistedStepRecoveryReady;

import java.time.Instant;
import java.util.Objects;

public record ReadyStepActivationMaterializationRequest(
        PersistedStepRecoveryReady ready,
        StepActivationEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public ReadyStepActivationMaterializationRequest {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(eventDraft, "eventDraft");
        Objects.requireNonNull(checkpointCreatedAt, "checkpointCreatedAt");
    }
}
