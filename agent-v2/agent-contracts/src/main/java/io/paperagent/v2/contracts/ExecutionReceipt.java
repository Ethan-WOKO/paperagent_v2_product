package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record ExecutionReceipt(
        ReceiptId id,
        ToolCallId toolCallId,
        ReceiptStatus status,
        Instant startedAt,
        Instant endedAt,
        Optional<Integer> exitCode,
        Optional<String> resultCode,
        OutputCapture standardOutput,
        OutputCapture standardError,
        List<ArtifactRef> artifactReferences,
        Optional<DiffId> resultingDiff,
        List<EventId> eventReferences) {

    public ExecutionReceipt {
        Contracts.required(id, "receipt.id");
        Contracts.required(toolCallId, "receipt.toolCallId");
        Contracts.required(status, "receipt.status");
        Contracts.required(startedAt, "receipt.startedAt");
        Contracts.required(endedAt, "receipt.endedAt");
        if (endedAt.isBefore(startedAt)) {
            Contracts.fail(ViolationCode.INVALID_TIME_RANGE, "receipt.endedAt",
                    "receipt cannot end before it starts");
        }
        exitCode = Contracts.required(exitCode, "receipt.exitCode");
        resultCode = Contracts.required(resultCode, "receipt.resultCode")
                .map(value -> Contracts.id(value, "receipt.resultCode"));
        Contracts.required(standardOutput, "receipt.standardOutput");
        Contracts.required(standardError, "receipt.standardError");
        artifactReferences = Contracts.list(artifactReferences, "receipt.artifactReferences");
        resultingDiff = Contracts.required(resultingDiff, "receipt.resultingDiff");
        eventReferences = Contracts.list(eventReferences, "receipt.eventReferences");

        boolean valid = switch (status) {
            case SUCCESS -> resultCode.isEmpty() && exitCode.map(code -> code == 0).orElse(true);
            case FAILURE -> resultCode.isPresent() && exitCode.map(code -> code != 0).orElse(true);
            case CANCELLED, TIMEOUT -> resultCode.isPresent() && exitCode.isEmpty();
        };
        if (!valid) {
            Contracts.fail(ViolationCode.INVALID_RECEIPT, "receipt",
                    "status, exit code, and result code combination is inconsistent");
        }
    }
}
