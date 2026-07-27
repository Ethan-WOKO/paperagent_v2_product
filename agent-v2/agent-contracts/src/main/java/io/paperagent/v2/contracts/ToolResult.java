package io.paperagent.v2.contracts;

import java.time.Instant;
import java.util.Optional;

public record ToolResult(
        ToolCallId toolCallId,
        ToolResultStatus status,
        Optional<ContractValue> value,
        Optional<String> failureCode,
        Instant completedAt) {

    public ToolResult {
        Contracts.required(toolCallId, "toolResult.toolCallId");
        Contracts.required(status, "toolResult.status");
        value = Contracts.required(value, "toolResult.value");
        failureCode = Contracts.required(failureCode, "toolResult.failureCode");
        Contracts.required(completedAt, "toolResult.completedAt");
        if (status == ToolResultStatus.SUCCESS && (value.isEmpty() || failureCode.isPresent())
                || status != ToolResultStatus.SUCCESS && failureCode.isEmpty()) {
            Contracts.fail(ViolationCode.INCONSISTENT_REFERENCE, "toolResult",
                    "success requires a value and no failure code; non-success requires a failure code");
        }
        failureCode = failureCode.map(code -> Contracts.id(code, "toolResult.failureCode"));
    }
}
