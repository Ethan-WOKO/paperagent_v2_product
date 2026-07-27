package io.paperagent.v2.contracts;

public record DiffId(String value) {
    public DiffId {
        value = Contracts.id(value, "diffId");
    }
}
