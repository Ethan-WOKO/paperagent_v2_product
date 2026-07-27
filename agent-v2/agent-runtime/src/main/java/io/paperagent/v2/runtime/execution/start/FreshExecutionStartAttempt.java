package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;

import java.time.Instant;

public record FreshExecutionStartAttempt(
        String leaseOwnerId,
        String leaseToken,
        Instant leaseExpiresAt,
        ExecutionStartEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public FreshExecutionStartAttempt {
        leaseOwnerId = FreshExecutionStartValues.identifier(
                leaseOwnerId,
                "freshExecutionStartAttempt.leaseOwnerId");
        leaseToken = FreshExecutionStartValues.identifier(
                leaseToken,
                "freshExecutionStartAttempt.leaseToken");
        FreshExecutionStartValues.required(
                leaseExpiresAt,
                "freshExecutionStartAttempt.leaseExpiresAt");
        FreshExecutionStartValues.required(
                eventDraft,
                "freshExecutionStartAttempt.eventDraft");
        FreshExecutionStartValues.required(
                checkpointCreatedAt,
                "freshExecutionStartAttempt.checkpointCreatedAt");
    }

    @Override
    public String toString() {
        return "FreshExecutionStartAttempt[leaseOwnerId=<provided>, "
                + "leaseToken=<redacted>, leaseExpiresAt=<provided>, "
                + "eventDraft=<provided>, checkpointCreatedAt=<provided>]";
    }
}
