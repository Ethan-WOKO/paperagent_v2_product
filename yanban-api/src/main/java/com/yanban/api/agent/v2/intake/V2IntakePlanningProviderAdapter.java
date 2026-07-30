package com.yanban.api.agent.v2.intake;

import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelProviderResult;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.providers.ProviderFailureCode;
import io.paperagent.v2.providers.UsageMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Credential-confined adapter for the pre-bootstrap planning call.
 *
 * <p>The stable Step provider adapter requires an authoritative persisted
 * PlanId, which intentionally does not exist during intake. This sibling
 * adapter therefore accepts only an owner-resolved endpoint and a no-tools
 * ModelRequest with no TaskFrame or Plan references.
 */
final class V2IntakePlanningProviderAdapter implements ModelProvider {
    private final ChatModelProvider delegate;
    private final UserSettingsService.ModelEndpoint endpoint;

    V2IntakePlanningProviderAdapter(
            ChatModelProvider delegate,
            UserSettingsService.ModelEndpoint endpoint) {
        if (delegate == null || endpoint == null) {
            throw new IllegalArgumentException(
                    "V2 intake provider configuration is invalid");
        }
        this.delegate = delegate;
        this.endpoint = endpoint;
    }

    @Override
    public ModelProviderResult complete(ModelRequest request) {
        if (request == null
                || request.cancellationRequested()
                || !request.availableTools().isEmpty()
                || request.taskFrameId().isPresent()
                || request.planId().isPresent()
                || request.planRevisionId().isPresent()
                || request.stepId().isPresent()) {
            return failure(ProviderFailureCode.INVALID_REQUEST);
        }
        try {
            List<ChatMessage> messages = request.messages().stream()
                    .map(V2IntakePlanningProviderAdapter::message)
                    .toList();
            ChatResponse response = delegate.chat(new ChatRequest(
                    endpoint.providerKey(),
                    endpoint.modelName(),
                    messages,
                    request.generationOptions().temperature(),
                    request.generationOptions().maxOutputTokens(),
                    List.of(),
                    endpoint.apiKey(),
                    endpoint.apiUrl(),
                    ChatRequest.ResponseFormat.jsonObject(),
                    ChatRequest.Thinking.disabled(),
                    request.correlationId().value()));
            if (response == null || response.message() == null
                    || response.toolCalls() != null
                    && !response.toolCalls().isEmpty()) {
                return failure(ProviderFailureCode.PROTOCOL_VIOLATION);
            }
            String text = response.assistantText();
            if (text == null || text.isBlank()) {
                return failure(ProviderFailureCode.PROTOCOL_VIOLATION);
            }
            ChatResponse.Usage usage = response.usage();
            return new ModelResponse(
                    Optional.of(text),
                    List.of(),
                    finishReason(response.finishReason()),
                    new UsageMetadata(
                            nonNegative(usage == null
                                    ? null : usage.promptTokens()),
                            nonNegative(usage == null
                                    ? null : usage.completionTokens()),
                            0,
                            Map.of()),
                    Map.of());
        } catch (RuntimeException failure) {
            return failure(ProviderFailureCode.UNAVAILABLE);
        }
    }

    private static ChatMessage message(ModelMessage value) {
        return switch (value.role()) {
            case SYSTEM -> ChatMessage.system(value.content());
            case USER -> ChatMessage.user(value.content());
            case ASSISTANT -> ChatMessage.assistant(value.content());
            case TOOL_FACT -> ChatMessage.process(value.content());
        };
    }

    private static FinishReason finishReason(String value) {
        if ("stop".equalsIgnoreCase(value)) {
            return FinishReason.STOP;
        }
        if ("length".equalsIgnoreCase(value)) {
            return FinishReason.MAX_OUTPUT_TOKENS;
        }
        if ("content_filter".equalsIgnoreCase(value)) {
            return FinishReason.CONTENT_FILTERED;
        }
        return FinishReason.STOP;
    }

    private static long nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value.longValue();
    }

    private static ProviderFailure failure(ProviderFailureCode code) {
        return new ProviderFailure(
                code, "V2 intake model call failed", Map.of());
    }
}
