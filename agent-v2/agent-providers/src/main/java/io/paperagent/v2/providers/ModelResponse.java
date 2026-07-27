package io.paperagent.v2.providers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ModelResponse(
        Optional<String> assistantText,
        List<ProposedToolCall> proposedToolCalls,
        FinishReason finishReason,
        UsageMetadata usage,
        Map<String, String> metadata) implements ModelProviderResult {

    public ModelResponse {
        assistantText = ProviderValues.required(
                assistantText,
                "modelResponse.assistantText");
        if (assistantText.isPresent()) {
            assistantText = Optional.of(ProviderValues.text(
                    assistantText.get(),
                    "modelResponse.assistantText"));
        }
        proposedToolCalls = ProviderValues.list(
                proposedToolCalls,
                "modelResponse.proposedToolCalls");
        ProviderValues.unique(
                proposedToolCalls,
                ProposedToolCall::providerCallId,
                "modelResponse.proposedToolCalls");
        ProviderValues.required(finishReason, "modelResponse.finishReason");
        ProviderValues.required(usage, "modelResponse.usage");
        metadata = ProviderValues.map(metadata, "modelResponse.metadata");
        metadata.forEach((key, value) -> {
            ProviderValues.text(key, "modelResponse.metadata.key");
            ProviderValues.text(value, "modelResponse.metadata.value");
        });
        validateCombination(assistantText, proposedToolCalls, finishReason);
    }

    private static void validateCombination(
            Optional<String> assistantText,
            List<ProposedToolCall> proposedToolCalls,
            FinishReason finishReason) {
        if (assistantText.isEmpty() && proposedToolCalls.isEmpty()) {
            invalid("response must contain assistant text or at least one proposed tool call");
        }
        if (finishReason == FinishReason.TOOL_CALLS && proposedToolCalls.isEmpty()) {
            invalid("TOOL_CALLS finish reason requires proposed tool calls");
        }
        if (finishReason != FinishReason.TOOL_CALLS && !proposedToolCalls.isEmpty()) {
            invalid("proposed tool calls require TOOL_CALLS finish reason");
        }
    }

    private static void invalid(String message) {
        ProviderValues.fail(
                ProviderValidationCode.INVALID_RESPONSE_COMBINATION,
                "modelResponse",
                message);
    }
}
