package com.yanban.core.model;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        List<ChatImage> images
) {
    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
    }
    public ChatMessage {
        images = images == null ? List.of() : List.copyOf(images);
        if (!images.isEmpty() && !"user".equals(role)) throw new IllegalArgumentException("Images require a user message");
    }
    public Object providerContent() {
        if (images.isEmpty()) return content;
        java.util.List<java.util.Map<String,Object>> parts = new java.util.ArrayList<>();
        if (content != null && !content.isBlank()) parts.add(java.util.Map.of("type", "text", "text", content));
        for (ChatImage image : images) parts.add(java.util.Map.of("type", "image_url", "image_url", java.util.Map.of("url", image.dataUrl())));
        return parts;
    }
    public static String responseText(Object content) {
        if (content == null) return null;
        if (content instanceof String text) return text;
        throw new ModelProviderException("Unsupported model response content");
    }
    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM.value(), content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER.value(), content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT.value(), content, null, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(ChatRole.TOOL.value(), content, null, toolCallId);
    }

    public static ChatMessage process(String content) {
        return new ChatMessage(ChatRole.PROCESS.value(), content, null, null);
    }
}
