package com.yanban.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A generic OpenAI-compatible chat model provider that resolves the API URL, API key,
 * and model name from each {@link ChatRequest}. Used for user-defined custom models.
 */
public class OpenAiCompatibleModelProvider implements ChatModelProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public OpenAiCompatibleModelProvider(ObjectMapper objectMapper) {
        this(WebClient.builder(), objectMapper, Duration.ofSeconds(60));
    }

    public OpenAiCompatibleModelProvider(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, Duration timeout) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public String providerName() {
        return "openai-compatible";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        validateConfigured(request);
        OpenAiChatRequest payload = toRequest(request, false);
        try {
            OpenAiChatResponse response = webClient.post()
                    .uri(request.apiUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.apiKey())
                    .headers(headers -> applyTraceHeader(headers, request.traceId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse -> clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new ModelProviderException(
                                    "OpenAI-compatible API error: HTTP " + clientResponse.statusCode().value() + " " + body))))
                    .bodyToMono(OpenAiChatResponse.class)
                    .block(timeout);
            return fromResponse(response);
        } catch (WebClientResponseException ex) {
            throw new ModelProviderException("OpenAI-compatible API error: HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString(), ex);
        } catch (ModelProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ModelProviderException("OpenAI-compatible API request failed", ex);
        }
    }

    @Override
    public Flux<ChatChunk> streamChat(ChatRequest request) {
        validateConfigured(request);
        OpenAiChatRequest payload = toRequest(request, true);
        return webClient.post()
                .uri(request.apiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.apiKey())
                .headers(headers -> applyTraceHeader(headers, request.traceId()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new ModelProviderException(
                                "OpenAI-compatible API error: HTTP " + clientResponse.statusCode().value() + " " + body))))
                .bodyToFlux(String.class)
                .flatMapIterable(this::parseSseChunk)
                .onErrorMap(ex -> ex instanceof ModelProviderException ? ex : new ModelProviderException("OpenAI-compatible API stream failed", ex));
    }

    private void validateConfigured(ChatRequest request) {
        if (!StringUtils.hasText(request.apiUrl())) {
            throw new ModelProviderException("Custom model apiUrl is not configured");
        }
        if (!StringUtils.hasText(request.apiKey())) {
            throw new ModelProviderException("Custom model apiKey is not configured");
        }
    }

    private void applyTraceHeader(HttpHeaders headers, String traceId) {
        if (StringUtils.hasText(traceId)) {
            headers.set("X-Trace-Id", traceId);
        }
    }

    private OpenAiChatRequest toRequest(ChatRequest request, boolean stream) {
        List<OpenAiMessage> messages = request.messages().stream()
                .map(message -> new OpenAiMessage(message.role(), message.content(), message.toolCalls(), message.toolCallId()))
                .toList();
        return new OpenAiChatRequest(request.model(), messages, request.temperature(), request.maxTokens(), stream,
                stream ? new StreamOptions(true) : null,
                request.tools(), request.responseFormat());
    }

    private ChatResponse fromResponse(OpenAiChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ModelProviderException("OpenAI-compatible API returned no choices");
        }
        OpenAiChoice choice = response.choices().get(0);
        OpenAiMessage message = choice.message();
        if (message == null) {
            throw new ModelProviderException("OpenAI-compatible API returned empty message");
        }
        ChatResponse.Usage usage = response.usage() == null ? null : new ChatResponse.Usage(
                intOrNull(response.usage().promptTokens()),
                intOrNull(response.usage().completionTokens()),
                intOrNull(response.usage().totalTokens())
        );
        return new ChatResponse(
                new ChatMessage(message.role(), message.content(), message.toolCalls(), message.toolCallId()),
                choice.finishReason(),
                usage
        );
    }

    private Integer intOrNull(Number number) {
        return number == null ? null : number.intValue();
    }

    private List<ChatChunk> parseSseChunk(String chunk) {
        List<ChatChunk> chunks = new ArrayList<>();
        if (!StringUtils.hasText(chunk)) {
            return chunks;
        }
        String[] lines = chunk.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            String data;
            if (line.startsWith("data:")) {
                data = line.substring("data:".length()).trim();
            } else if (line.startsWith("{") || "[DONE]".equals(line)) {
                data = line;
            } else {
                continue;
            }
            if (data.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                chunks.add(ChatChunk.done("stop"));
                continue;
            }
            chunks.addAll(parseSseData(data));
        }
        return chunks;
    }

    private List<ChatChunk> parseSseData(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            List<ChatChunk> chunks = new ArrayList<>();
            ChatResponse.Usage usage = parseUsage(root.path("usage"));
            if (usage != null) {
                chunks.add(ChatChunk.usage(usage));
            }
            JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                    ? root.path("choices").get(0)
                    : null;
            if (choice == null) {
                return chunks;
            }
            JsonNode delta = choice.path("delta");
            if (delta.hasNonNull("content")) {
                String content = delta.path("content").asText();
                if (!content.isEmpty()) {
                    chunks.add(ChatChunk.token(content));
                }
            }
            chunks.addAll(parseToolCallDeltas(delta.path("tool_calls")));
            if (choice.hasNonNull("finish_reason")) {
                chunks.add(ChatChunk.done(choice.path("finish_reason").asText()));
            }
            return chunks;
        } catch (Exception ex) {
            throw new ModelProviderException("Failed to parse OpenAI-compatible SSE chunk", ex);
        }
    }

    private ChatResponse.Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return null;
        }
        return new ChatResponse.Usage(
                jsonIntOrNull(usageNode.get("prompt_tokens")),
                jsonIntOrNull(usageNode.get("completion_tokens")),
                jsonIntOrNull(usageNode.get("total_tokens"))
        );
    }

    private Integer jsonIntOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.intValue() : null;
    }

    private List<ChatChunk> parseToolCallDeltas(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return List.of();
        }
        List<ChatChunk> chunks = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            int index = toolCallNode.path("index").asInt(0);
            JsonNode functionNode = toolCallNode.path("function");
            chunks.add(ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(
                    index,
                    textOrNull(toolCallNode.path("id")),
                    textOrNull(toolCallNode.path("type")),
                    textOrNull(functionNode.path("name")),
                    textOrNull(functionNode.path("arguments"))
            )));
        }
        return chunks;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isEmpty() ? null : value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OpenAiChatRequest(
            String model,
            List<OpenAiMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            Boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions,
            List<ToolSpec> tools,
            @JsonProperty("response_format") ChatRequest.ResponseFormat responseFormat
    ) {
    }

    private record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OpenAiMessage(
            String role,
            String content,
            @JsonProperty("tool_calls") List<ToolCall> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId
    ) {
    }

    private record OpenAiChatResponse(List<OpenAiChoice> choices, OpenAiUsage usage) {
    }

    private record OpenAiChoice(OpenAiMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    private record OpenAiUsage(
            @JsonProperty("prompt_tokens") Number promptTokens,
            @JsonProperty("completion_tokens") Number completionTokens,
            @JsonProperty("total_tokens") Number totalTokens
    ) {
    }
}
