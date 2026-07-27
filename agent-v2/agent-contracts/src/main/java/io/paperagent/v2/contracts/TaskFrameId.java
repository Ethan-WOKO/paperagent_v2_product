package io.paperagent.v2.contracts;

public record TaskFrameId(String value) {
    public TaskFrameId {
        value = Contracts.id(value, "taskFrameId");
    }
}
