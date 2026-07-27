package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ReceiptValidationTest {
    @Test
    void acceptsSuccessFailureCancellationAndTimeout() {
        receipt(ReceiptStatus.SUCCESS, Optional.of(0), Optional.empty());
        receipt(ReceiptStatus.FAILURE, Optional.of(1), Optional.of("COMMAND_FAILED"));
        receipt(ReceiptStatus.CANCELLED, Optional.empty(), Optional.of("USER_CANCELLED"));
        receipt(ReceiptStatus.TIMEOUT, Optional.empty(), Optional.of("TIME_LIMIT"));
    }

    @Test
    void rejectsEndBeforeStart() {
        ContractViolationException exception = ContractFixtures.violation(() -> new ExecutionReceipt(
                new ReceiptId("receipt-1"),
                new ToolCallId("call-1"),
                ReceiptStatus.SUCCESS,
                T0.plusSeconds(2),
                T0,
                Optional.of(0),
                Optional.empty(),
                OutputCapture.empty(),
                OutputCapture.empty(),
                List.of(),
                Optional.empty(),
                List.of()));
        assertEquals(ViolationCode.INVALID_TIME_RANGE, exception.primaryCode());
    }

    @Test
    void rejectsInvalidStatusAndExitCombination() {
        ContractViolationException exception = ContractFixtures.violation(
                () -> receipt(ReceiptStatus.SUCCESS, Optional.of(1), Optional.of("FAILED")));
        assertEquals(ViolationCode.INVALID_RECEIPT, exception.primaryCode());
    }

    @Test
    void rejectsOversizedInlineOutput() {
        ContractViolationException exception = ContractFixtures.violation(
                () -> OutputCapture.inline("x".repeat(OutputCapture.MAX_INLINE_CHARACTERS + 1), false));
        assertEquals(ViolationCode.OUTPUT_LIMIT_EXCEEDED, exception.primaryCode());
    }

    @Test
    void rejectsInlineAndArtifactOutputCombination() {
        ContractViolationException exception = ContractFixtures.violation(() -> new OutputCapture(
                Optional.of("output"), Optional.of(new ArtifactRef("artifact-1")), false));
        assertEquals(ViolationCode.INVALID_RECEIPT, exception.primaryCode());
    }

    private static ExecutionReceipt receipt(
            ReceiptStatus status,
            Optional<Integer> exitCode,
            Optional<String> resultCode) {
        return new ExecutionReceipt(
                new ReceiptId("receipt-" + status.name().toLowerCase()),
                new ToolCallId("call-1"),
                status,
                T0,
                T0.plusSeconds(1),
                exitCode,
                resultCode,
                OutputCapture.inline("stdout", false),
                OutputCapture.empty(),
                List.of(),
                Optional.empty(),
                List.of(new EventId("event-1")));
    }
}
