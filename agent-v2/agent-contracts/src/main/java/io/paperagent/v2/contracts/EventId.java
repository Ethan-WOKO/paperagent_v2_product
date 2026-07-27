package io.paperagent.v2.contracts;

public record EventId(String value) {
    public EventId {
        value = Contracts.id(value, "eventId");
    }
}
