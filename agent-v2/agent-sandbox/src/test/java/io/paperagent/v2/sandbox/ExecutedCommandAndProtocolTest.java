package io.paperagent.v2.sandbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutedCommandAndProtocolTest {
    @Test
    void executedResultPreservesZeroAndNonZeroExitFacts() {
        ExecutedCommand zero = new ExecutedCommand(
                0,
                SandboxFixtures.STARTED_AT,
                SandboxFixtures.STARTED_AT.plusSeconds(2),
                new byte[]{0, 1, (byte) 255},
                new byte[]{2},
                true,
                false,
                Map.of("provider", "scripted"));
        ExecutedCommand nonZero = SandboxFixtures.executed(17, "failed");

        assertEquals(0, zero.exitCode());
        assertEquals(17, nonZero.exitCode());
        assertArrayEquals(new byte[]{0, 1, (byte) 255}, zero.stdout());
        assertArrayEquals(new byte[]{2}, zero.stderr());
        assertTrue(zero.stdoutTruncated());
        assertFalse(zero.stderrTruncated());
        assertEquals(SandboxFixtures.STARTED_AT, zero.startedAt());
        assertEquals(SandboxFixtures.STARTED_AT.plusSeconds(2), zero.endedAt());
        assertEquals(Map.of("provider", "scripted"), zero.metadata());
    }

    @Test
    void capturedArraysAndMetadataAreDefensivelyImmutable() {
        byte[] stdout = new byte[]{1, 2};
        byte[] stderr = new byte[]{3, 4};
        Map<String, String> metadata = new LinkedHashMap<>(Map.of("key", "value"));
        ExecutedCommand result = new ExecutedCommand(
                0,
                SandboxFixtures.STARTED_AT,
                SandboxFixtures.STARTED_AT,
                stdout,
                stderr,
                false,
                false,
                metadata);

        stdout[0] = 9;
        stderr[0] = 9;
        metadata.put("late", "mutation");
        byte[] returned = result.stdout();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2}, result.stdout());
        assertArrayEquals(new byte[]{3, 4}, result.stderr());
        assertEquals(Map.of("key", "value"), result.metadata());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.metadata().put("late", "mutation"));
    }

    @Test
    void equalResultsUseCapturedByteContentRatherThanArrayIdentity() {
        ExecutedCommand first = SandboxFixtures.executed(0, "same");
        ExecutedCommand second = SandboxFixtures.executed(0, "same");
        ExecutedCommand different = SandboxFixtures.executed(0, "different");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, different);
    }

    @Test
    void timeOrderMustBeNonNegative() {
        SandboxValidationException exception = SandboxFixtures.violation(
                () -> new ExecutedCommand(
                        0,
                        SandboxFixtures.STARTED_AT,
                        SandboxFixtures.STARTED_AT.minusSeconds(1),
                        new byte[0],
                        new byte[0],
                        false,
                        false,
                        Map.of()));

        assertEquals(SandboxValidationCode.INVALID_TIME_RANGE, exception.code());
        assertEquals("executedCommand.endedAt", exception.path());
    }

    @Test
    void capturedOutputAtOrUnderProfileLimitPassesAndOverLimitRejects() {
        SandboxRequest request = SandboxFixtures.request(
                "request-output",
                java.util.List.of("tool"),
                Map.of(),
                Map.of(),
                SandboxOperationIntent.COMMAND,
                SandboxFixtures.profile(
                        java.util.Set.of(io.paperagent.v2.contracts.Capability.EXECUTE_COMMAND),
                        java.util.Set.of(),
                        5),
                false);
        ExecutedCommand atLimit = new ExecutedCommand(
                0,
                SandboxFixtures.STARTED_AT,
                SandboxFixtures.STARTED_AT,
                new byte[]{1, 2, 3},
                new byte[]{4, 5},
                false,
                false,
                Map.of());
        ExecutedCommand underLimit = SandboxFixtures.executed(0, "1234");
        ExecutedCommand overLimit = SandboxFixtures.executed(0, "123456");

        SandboxProtocolValidator.validate(request, atLimit);
        SandboxProtocolValidator.validate(request, underLimit);
        SandboxValidationException exception = SandboxFixtures.violation(
                () -> SandboxProtocolValidator.validate(request, overLimit));
        assertEquals(SandboxValidationCode.OUTPUT_LIMIT_EXCEEDED, exception.code());
    }

    @Test
    void overflowShapedOutputCountsRejectWithoutAllocation() {
        SandboxValidationException exception = SandboxFixtures.violation(
                () -> SandboxProtocolValidator.validateCapturedByteCounts(
                        Long.MAX_VALUE,
                        1,
                        Long.MAX_VALUE));

        assertEquals(SandboxValidationCode.OUTPUT_LENGTH_OVERFLOW, exception.code());
        assertEquals(
                10,
                SandboxProtocolValidator.validateCapturedByteCounts(4, 6, 10));
    }

    @Test
    void negativeOutputCountsRejectWithStableCode() {
        SandboxValidationException exception = SandboxFixtures.violation(
                () -> SandboxProtocolValidator.validateCapturedByteCounts(-1, 0, 10));

        assertEquals(SandboxValidationCode.INVALID_OUTPUT_BYTE_COUNT, exception.code());
    }

    @Test
    void metadataIsBounded() {
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index <= SandboxLimits.MAX_METADATA_ENTRIES; index++) {
            tooMany.put("key-" + index, "value");
        }
        assertEquals(
                SandboxValidationCode.BOUND_EXCEEDED,
                SandboxFixtures.violation(() -> new SandboxFailure(
                        SandboxFailureCode.BACKEND_FAILURE,
                        "failure",
                        tooMany)).code());
        assertEquals(
                SandboxValidationCode.BOUND_EXCEEDED,
                SandboxFixtures.violation(() -> new ExecutedCommand(
                        0,
                        Instant.EPOCH,
                        Instant.EPOCH,
                        new byte[0],
                        new byte[0],
                        false,
                        false,
                        Map.of(
                                "key",
                                "x".repeat(SandboxLimits.MAX_METADATA_VALUE_LENGTH + 1)))).code());
    }

    @Test
    void allStableFailureCategoriesAreConstructibleAndInspectable() {
        assertEquals(
                Arrays.asList(SandboxFailureCode.values()),
                Arrays.stream(SandboxFailureCode.values())
                        .map(SandboxFixtures::failure)
                        .map(SandboxFailure::code)
                        .toList());
    }
}
