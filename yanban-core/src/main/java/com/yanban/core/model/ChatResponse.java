package com.yanban.core.model;

import java.util.List;

public record ChatResponse(
        ChatMessage message,
        String finishReason,
        Usage usage
) {
    public String assistantText() {
        return message == null ? null : message.content();
    }

    public List<ToolCall> toolCalls() {
        return message == null ? null : message.toolCalls();
    }

    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                        Integer cacheHitTokens, Integer cacheMissTokens) {
        public Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
            this(promptTokens, completionTokens, totalTokens, null, null);
        }
    }
}
