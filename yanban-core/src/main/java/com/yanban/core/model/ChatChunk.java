package com.yanban.core.model;

import java.util.List;

public record ChatChunk(
        String content,
        boolean done,
        String finishReason,
        List<ToolCallDelta> toolCallDeltas,
        ChatResponse.Usage usage
) {
    public ChatChunk(String content, boolean done, String finishReason) {
        this(content, done, finishReason, List.of(), null);
    }

    public ChatChunk(String content, boolean done, String finishReason, List<ToolCallDelta> toolCallDeltas) {
        this(content, done, finishReason, toolCallDeltas, null);
    }

    public ChatChunk {
        toolCallDeltas = toolCallDeltas == null ? List.of() : List.copyOf(toolCallDeltas);
    }

    public static ChatChunk token(String content) {
        return new ChatChunk(content, false, null);
    }

    public static ChatChunk toolCallDelta(ToolCallDelta delta) {
        return new ChatChunk(null, false, null, List.of(delta));
    }

    public static ChatChunk done(String finishReason) {
        return new ChatChunk(null, true, finishReason);
    }

    public static ChatChunk usage(ChatResponse.Usage usage) {
        return new ChatChunk(null, false, null, List.of(), usage);
    }

    public record ToolCallDelta(
            int index,
            String id,
            String type,
            String functionName,
            String argumentsDelta
    ) {
    }
}
