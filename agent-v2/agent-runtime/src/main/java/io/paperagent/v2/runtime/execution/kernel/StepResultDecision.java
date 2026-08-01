package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.ReceiptId;
import java.util.List;

/**
 * Untrusted model proposal that the current Step has produced a result.
 *
 * <p>This decision does not complete the Step. Product persistence and a
 * later reflection decision must accept it before progression.</p>
 */
public record StepResultDecision(
        String resultText,
        List<ReceiptId> evidenceReceiptIds)
        implements StepTurnDecision {
    private static final int MAX_RESULT_CHARACTERS = 20_000;

    public StepResultDecision {
        if (resultText == null || resultText.isBlank()
                || resultText.length() > MAX_RESULT_CHARACTERS) {
            throw SingleTurnStepKernelValues.failure(
                    SingleTurnStepKernelValidationCode
                            .REQUIRED_VALUE_MISSING,
                    "stepResultDecision.resultText");
        }
        resultText = resultText.strip();
        evidenceReceiptIds = List.copyOf(
                SingleTurnStepKernelValues.required(
                        evidenceReceiptIds,
                        "stepResultDecision.evidenceReceiptIds"));
        if (evidenceReceiptIds.stream().anyMatch(
                java.util.Objects::isNull)
                || evidenceReceiptIds.stream().distinct().count()
                        != evidenceReceiptIds.size()) {
            throw SingleTurnStepKernelValues.failure(
                    SingleTurnStepKernelValidationCode
                            .REQUIRED_VALUE_MISSING,
                    "stepResultDecision.evidenceReceiptIds");
        }
    }

    public StepResultDecision(String resultText) {
        this(resultText, List.of());
    }

    @Override
    public String toString() {
        return "StepResultDecision[resultText=<redacted>, "
                + "evidenceReceiptIds=<redacted>]";
    }
}
