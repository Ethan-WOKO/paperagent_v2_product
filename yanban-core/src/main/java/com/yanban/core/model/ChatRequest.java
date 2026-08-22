package com.yanban.core.model;

import java.time.Duration;
import java.util.List;

public record ChatRequest(
        String provider,
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        List<ToolSpec> tools,
        String apiKey,
        String apiUrl,
        ResponseFormat responseFormat,
        Thinking thinking,
        String traceId,
        Duration timeout
) {
    public ChatRequest(String provider,
                       String model,
                       List<ChatMessage> messages,
                       Double temperature,
                       Integer maxTokens,
                       List<ToolSpec> tools,
                       String apiKey,
                       String apiUrl,
                       ResponseFormat responseFormat,
                       Thinking thinking,
                       String traceId) {
        this(provider, model, messages, temperature, maxTokens, tools, apiKey, apiUrl,
                responseFormat, thinking, traceId, null);
    }

    public ChatRequest(String provider,
                       String model,
                       List<ChatMessage> messages,
                       Double temperature,
                       Integer maxTokens,
                       List<ToolSpec> tools,
                       String apiKey) {
        this(provider, model, messages, temperature, maxTokens, tools, apiKey, null, null, null, null, null);
    }

    public ChatRequest(String provider,
                       String model,
                       List<ChatMessage> messages,
                       Double temperature,
                       Integer maxTokens,
                       List<ToolSpec> tools,
                       String apiKey,
                       ResponseFormat responseFormat,
                       Thinking thinking) {
        this(provider, model, messages, temperature, maxTokens, tools, apiKey, null, responseFormat, thinking, null, null);
    }

    public ChatRequest(String provider,
                       String model,
                       List<ChatMessage> messages,
                       Double temperature,
                       Integer maxTokens,
                       List<ToolSpec> tools,
                       String apiKey,
                       ResponseFormat responseFormat,
                       Thinking thinking,
                       String traceId) {
        this(provider, model, messages, temperature, maxTokens, tools, apiKey, null, responseFormat, thinking, traceId, null);
    }

    public ChatRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }

    public static ChatRequest simple(String model, List<ChatMessage> messages) {
        return new ChatRequest(null, model, messages, null, null, null, null);
    }

    public record ResponseFormat(String type) {
        public static ResponseFormat jsonObject() {
            return new ResponseFormat("json_object");
        }
    }

    public record Thinking(String type) {
        public static Thinking disabled() {
            return new Thinking("disabled");
        }
    }
}
