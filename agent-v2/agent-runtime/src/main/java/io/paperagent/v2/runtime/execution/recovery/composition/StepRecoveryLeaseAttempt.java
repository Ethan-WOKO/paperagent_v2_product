package io.paperagent.v2.runtime.execution.recovery.composition;

import java.time.Instant;

public record StepRecoveryLeaseAttempt(
        String leaseOwnerId,
        String leaseToken,
        Instant leaseExpiresAt) {

    public StepRecoveryLeaseAttempt {
        leaseOwnerId = StepRecoveryCompositionValues.identifier(
                leaseOwnerId, "stepRecoveryLeaseAttempt.leaseOwnerId");
        leaseToken = StepRecoveryCompositionValues.identifier(
                leaseToken, "stepRecoveryLeaseAttempt.leaseToken");
        StepRecoveryCompositionValues.required(
                leaseExpiresAt, "stepRecoveryLeaseAttempt.leaseExpiresAt");
    }

    @Override
    public String toString() {
        return "StepRecoveryLeaseAttempt[leaseOwnerId=<redacted>, "
                + "leaseToken=<redacted>, leaseExpiresAt=<provided>]";
    }
}
