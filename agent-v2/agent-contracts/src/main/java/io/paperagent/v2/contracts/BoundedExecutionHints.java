package io.paperagent.v2.contracts;

import java.time.Duration;

/**
 * Bounds execution without prescribing a fixed tool-call sequence.
 */
public record BoundedExecutionHints(int maxAttempts, Duration maxDuration) {
    public BoundedExecutionHints {
        Contracts.required(maxDuration, "boundedExecutionHints.maxDuration");
        if (maxAttempts <= 0 || maxDuration.isZero() || maxDuration.isNegative()) {
            Contracts.fail(ViolationCode.INVALID_TIME_RANGE, "boundedExecutionHints",
                    "attempt and duration bounds must be positive");
        }
    }
}
