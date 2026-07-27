package io.paperagent.v2.contracts;

public record ToolCallId(String value) {
    public ToolCallId {
        value = Contracts.id(value, "toolCallId");
    }
}
