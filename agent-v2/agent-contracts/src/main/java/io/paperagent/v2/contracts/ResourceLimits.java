package io.paperagent.v2.contracts;

import java.time.Duration;

public record ResourceLimits(
        Duration wallTime,
        Duration cpuTime,
        long memoryBytes,
        long outputBytes,
        int processCount) {

    public ResourceLimits {
        Contracts.required(wallTime, "resourceLimits.wallTime");
        Contracts.required(cpuTime, "resourceLimits.cpuTime");
        if (wallTime.isZero() || wallTime.isNegative()
                || cpuTime.isZero() || cpuTime.isNegative()
                || memoryBytes <= 0 || outputBytes <= 0 || processCount <= 0) {
            Contracts.fail(ViolationCode.INVALID_TIME_RANGE, "resourceLimits",
                    "durations and numeric limits must be positive");
        }
    }
}
