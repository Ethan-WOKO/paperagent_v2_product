package io.paperagent.v2.runtime.execution.context.composition;

import java.time.Instant;

public record PlanExecutionContextLeaseAttempt(
        String leaseOwnerId,
        String leaseToken,
        Instant leaseExpiresAt) {

    public PlanExecutionContextLeaseAttempt {
        leaseOwnerId = PlanExecutionContextCompositionValues.identifier(
                leaseOwnerId,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseOwnerId");
        leaseToken = PlanExecutionContextCompositionValues.identifier(
                leaseToken,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseToken");
        PlanExecutionContextCompositionValues.required(
                leaseExpiresAt,
                "planExecutionContextComposition.request"
                        + ".leaseAttempt.leaseExpiresAt");
    }

    @Override
    public String toString() {
        return "PlanExecutionContextLeaseAttempt["
                + "leaseOwnerId=<redacted>, "
                + "leaseToken=<redacted>, "
                + "leaseExpiresAt=<provided>]";
    }
}
