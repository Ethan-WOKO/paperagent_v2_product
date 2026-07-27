package io.paperagent.v2.providers;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class ProviderFixtures {
    private ProviderFixtures() {
    }

    static ModelRequest request(String id) {
        return request(id, false);
    }

    static ModelRequest request(String id, boolean cancellationRequested) {
        return new ModelRequest(
                new ModelRequestId(id),
                new CorrelationId("correlation-" + id),
                List.of(
                        new ModelMessage(MessageRole.SYSTEM, "Answer precisely."),
                        new ModelMessage(MessageRole.USER, "Question " + id)),
                List.of(tool("search")),
                options(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                cancellationRequested);
    }

    static GenerationOptions options() {
        return new GenerationOptions(
                256,
                4,
                0.0d,
                OptionalLong.of(42L),
                Map.of("candidateCount", "1"));
    }

    static ToolDescriptor tool(String id) {
        return new ToolDescriptor(
                new ToolId(id),
                "Tool " + id,
                Set.of(Capability.READ_PROJECT));
    }

    static ProposedToolCall proposedCall(String callId, String toolId, String query) {
        return new ProposedToolCall(
                callId,
                new ToolId(toolId),
                new ObjectValue(Map.of("query", new TextValue(query))));
    }

    static UsageMetadata usage() {
        return new UsageMetadata(12, 7, 2, Map.of("reasoningTokens", 1L));
    }

    static ModelResponse textResponse(String text) {
        return new ModelResponse(
                Optional.of(text),
                List.of(),
                FinishReason.STOP,
                usage(),
                Map.of("model", "scripted"));
    }

    static ProviderFailure failure(ProviderFailureCode code) {
        return new ProviderFailure(code, "failure " + code, Map.of("source", "test"));
    }
}
