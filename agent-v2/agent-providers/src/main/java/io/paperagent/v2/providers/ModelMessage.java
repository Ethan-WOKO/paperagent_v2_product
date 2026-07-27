package io.paperagent.v2.providers;

public record ModelMessage(MessageRole role, String content) {
    public ModelMessage {
        ProviderValues.required(role, "modelMessage.role");
        content = ProviderValues.text(content, "modelMessage.content");
    }
}
