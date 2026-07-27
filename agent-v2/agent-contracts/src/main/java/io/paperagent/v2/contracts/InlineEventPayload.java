package io.paperagent.v2.contracts;

public record InlineEventPayload(ObjectValue value) implements EventPayload {
    public InlineEventPayload {
        Contracts.required(value, "eventPayload.value");
    }
}
