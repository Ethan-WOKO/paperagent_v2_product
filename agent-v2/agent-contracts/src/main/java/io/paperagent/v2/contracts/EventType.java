package io.paperagent.v2.contracts;

public record EventType(String value) {
    public EventType {
        value = Contracts.id(value, "eventType");
    }
}
