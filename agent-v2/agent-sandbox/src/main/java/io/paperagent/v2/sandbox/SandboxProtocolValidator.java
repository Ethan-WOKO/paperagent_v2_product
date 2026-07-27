package io.paperagent.v2.sandbox;

import java.time.Instant;

/**
 * Pure request/result protocol checks. It performs no I/O and reads no ambient
 * state.
 */
public final class SandboxProtocolValidator {
    private SandboxProtocolValidator() {
    }

    public static void validate(
            SandboxRequest request,
            ExecutedCommand result) {
        SandboxValues.required(request, "sandboxProtocol.request");
        SandboxValues.required(result, "sandboxProtocol.result");
        validateTimeRange(result.startedAt(), result.endedAt());
        validateCapturedByteCounts(
                result.stdoutLength(),
                result.stderrLength(),
                request.executionProfile().resourceLimits().outputBytes());
    }

    public static long validateCapturedByteCounts(
            long stdoutBytes,
            long stderrBytes,
            long outputLimit) {
        if (stdoutBytes < 0 || stderrBytes < 0 || outputLimit < 0) {
            SandboxValues.fail(
                    SandboxValidationCode.INVALID_OUTPUT_BYTE_COUNT,
                    "sandboxProtocol.outputBytes",
                    "captured byte counts and output limit must be non-negative");
        }
        final long total;
        try {
            total = Math.addExact(stdoutBytes, stderrBytes);
        } catch (ArithmeticException overflow) {
            SandboxValues.fail(
                    SandboxValidationCode.OUTPUT_LENGTH_OVERFLOW,
                    "sandboxProtocol.outputBytes",
                    "captured stdout and stderr byte counts overflow");
            return 0;
        }
        if (total > outputLimit) {
            SandboxValues.fail(
                    SandboxValidationCode.OUTPUT_LIMIT_EXCEEDED,
                    "sandboxProtocol.outputBytes",
                    "captured stdout and stderr exceed the execution profile output limit");
        }
        return total;
    }

    static void validateTimeRange(Instant startedAt, Instant endedAt) {
        SandboxValues.required(startedAt, "executedCommand.startedAt");
        SandboxValues.required(endedAt, "executedCommand.endedAt");
        if (endedAt.isBefore(startedAt)) {
            SandboxValues.fail(
                    SandboxValidationCode.INVALID_TIME_RANGE,
                    "executedCommand.endedAt",
                    "endedAt must not be before startedAt");
        }
    }
}
