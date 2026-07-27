package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;

import java.time.Instant;

public record StepActivationAttempt(
        String leaseOwnerId,
        String leaseToken,
        Instant leaseExpiresAt,
        StepActivationEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public StepActivationAttempt {
        leaseOwnerId = StepActivationCompositionValues.identifier(
                leaseOwnerId, "stepActivationAttempt.leaseOwnerId");
        leaseToken = StepActivationCompositionValues.identifier(
                leaseToken, "stepActivationAttempt.leaseToken");
        StepActivationCompositionValues.required(
                leaseExpiresAt, "stepActivationAttempt.leaseExpiresAt");
        StepActivationCompositionValues.required(
                eventDraft, "stepActivationAttempt.eventDraft");
        StepActivationCompositionValues.required(
                checkpointCreatedAt, "stepActivationAttempt.checkpointCreatedAt");
    }

    @Override
    public String toString() {
        return "StepActivationAttempt[leaseOwnerId=<provided>, "
                + "leaseToken=<redacted>, leaseExpiresAt=<provided>, "
                + "eventDraft=<provided>, checkpointCreatedAt=<provided>]";
    }
}
