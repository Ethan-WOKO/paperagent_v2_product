package io.paperagent.v2.contracts;

public record ToolId(String value) {
    public ToolId {
        value = Contracts.id(value, "toolId");
    }
}
