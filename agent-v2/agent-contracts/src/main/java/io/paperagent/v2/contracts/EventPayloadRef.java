package io.paperagent.v2.contracts;

public record EventPayloadRef(String reference) implements EventPayload {
    public EventPayloadRef {
        reference = Contracts.id(reference, "eventPayload.reference");
    }
}
