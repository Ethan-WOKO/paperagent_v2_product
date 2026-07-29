package com.yanban.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class DeepSeekModelProvider implements ChatModelProvider {

    private static final int MAX_TRANSPORT_RETRIES = 1;

    private final DeepSeekProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public DeepSeekModelProvider(DeepSeekProperties properties) {
        this(properties, WebClient.builder(), new ObjectMapper());
    }

    public DeepSeekModelProvider(DeepSeekProperties properties, WebClient.Builder webClientBuilder) {
        this(properties, webClientBuilder, new ObjectMapper());
    }

    public DeepSeekModelProvider(DeepSeekProperties properties, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        validateConfigured(request.apiKey(), request.apiUrl());
        DeepSeekChatRequest payload = toDeepSeekRequest(request, false);
        try {
            DeepSeekChatResponse response = webClient.post()
                    .uri(resolveApiUrl(request.apiUrl()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + resolveApiKey(request.apiKey()))
                    .headers(headers -> applyTraceHeader(headers, request.traceId()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse -> clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new ModelProviderException(
                                    "DeepSeek API error: HTTP " + clientResponse.statusCode().value() + " " + body))))
                    .bodyToMono(DeepSeekChatResponse.class)
                    .retryWhen(transportRetrySpec())
                    .block(properties.getTimeout());
            return fromDeepSeekResponse(response);
        } catch (WebClientResponseException ex) {
            throw new ModelProviderException("DeepSeek API error: HTTP " + ex.getStatusCode().value() + " " + ex.getResponseBodyAsString(), ex);
        } catch (ModelProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ModelProviderException("DeepSeek API request failed: " + transportFailureMessage(ex), ex);
        }
    }

    @Override
    public reactor.core.publisher.Flux<ChatChunk> streamChat(ChatRequest request) {
        validateConfigured(request.apiKey(), request.apiUrl());
        DeepSeekChatRequest payload = toDeepSeekRequest(request, true);
        return webClient.post()
                .uri(resolveApiUrl(request.apiUrl()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + resolveApiKey(request.apiKey()))
                .headers(headers -> applyTraceHeader(headers, request.traceId()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new ModelProviderException(
                                "DeepSeek API error: HTTP " + clientResponse.statusCode().value() + " " + body))))
                .bodyToFlux(String.class)
                .retryWhen(transportRetrySpec())
                .flatMapIterable(this::parseSseChunk)
                .onErrorMap(ex -> ex instanceof ModelProviderException
                        ? ex
                        : new ModelProviderException("DeepSeek API stream failed: " + transportFailureMessage(ex), ex));
    }

    private Retry transportRetrySpec() {
        return Retry.backoff(MAX_TRANSPORT_RETRIES, Duration.ofMillis(250))
                .maxBackoff(Duration.ofSeconds(1))
                .filter(this::isTransientTransportError);
    }

    private boolean isTransientTransportError(Throwable ex) {
        Throwable root = rootCause(ex);
        return ex instanceof WebClientRequestException
                || root instanceof IOException
                || root instanceof SocketException
                || root instanceof TimeoutException
                || root.getClass().getName().contains("PrematureCloseException");
    }

    private String transportFailureMessage(Throwable ex) {
        Throwable root = rootCause(ex);
        String message = root.getMessage();
        String detail = StringUtils.hasText(message) ? message : root.getClass().getSimpleName();
        return detail + ". This is a transport/network failure before a usable DeepSeek response was received.";
    }

    private Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void validateConfigured(
            String apiKeyOverride, String apiUrlOverride) {
        if (!StringUtils.hasText(resolveApiUrl(apiUrlOverride))) {
            throw new ModelProviderException("DeepSeek apiUrl is not configured");
        }
        if (!StringUtils.hasText(resolveApiKey(apiKeyOverride))) {
            throw new ModelProviderException("DeepSeek apiKey is not configured");
        }
    }

    private void applyTraceHeader(HttpHeaders headers, String traceId) {
        if (StringUtils.hasText(traceId)) {
            headers.set("X-Trace-Id", traceId);
        }
    }

    private String resolveApiKey(String apiKeyOverride) {
        return StringUtils.hasText(apiKeyOverride) ? apiKeyOverride : properties.getApiKey();
    }

    private String resolveApiUrl(String apiUrlOverride) {
        return StringUtils.hasText(apiUrlOverride)
                ? apiUrlOverride : properties.getApiUrl();
    }

    private DeepSeekChatRequest toDeepSeekRequest(ChatRequest request, boolean stream) {
        String model = StringUtils.hasText(request.model()) ? request.model() : properties.getModel();
        Double temperature = request.temperature() != null ? request.temperature() : properties.getTemperature();
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.getMaxTokens();
        List<DeepSeekMessage> messages = request.messages().stream()
                .map(message -> new DeepSeekMessage(message.role(), message.content(), message.toolCalls(), message.toolCallId()))
                .toList();
        return new DeepSeekChatRequest(model, messages, temperature, maxTokens, stream, request.tools(),
                request.responseFormat(), request.thinking());
    }

    private ChatResponse fromDeepSeekResponse(DeepSeekChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ModelProviderException("DeepSeek API returned no choices");
        }
        DeepSeekChoice choice = response.choices().get(0);
        DeepSeekMessage message = choice.message();
        if (message == null) {
            throw new ModelProviderException("DeepSeek API returned empty message");
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
            JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                    ? root.path("choices").get(0)
                    : null;
            if (choice == null) {
                return List.of();
            }
            List<ChatChunk> chunks = new ArrayList<>();
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
            throw new ModelProviderException("Failed to parse DeepSeek SSE chunk", ex);
        }
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
    private record DeepSeekChatRequest(
            String model,
            List<DeepSeekMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            Boolean stream,
            List<ToolSpec> tools,
            @JsonProperty("response_format") ChatRequest.ResponseFormat responseFormat,
            ChatRequest.Thinking thinking
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DeepSeekMessage(
            String role,
            String content,
            @JsonProperty("tool_calls") List<ToolCall> toolCalls,
            @JsonProperty("tool_call_id") String toolCallId
    ) {
    }

    private record DeepSeekChatResponse(List<DeepSeekChoice> choices, DeepSeekUsage usage) {
    }

    private record DeepSeekChoice(DeepSeekMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    private record DeepSeekUsage(
            @JsonProperty("prompt_tokens") Number promptTokens,
            @JsonProperty("completion_tokens") Number completionTokens,
            @JsonProperty("total_tokens") Number totalTokens
    ) {
    }
}
