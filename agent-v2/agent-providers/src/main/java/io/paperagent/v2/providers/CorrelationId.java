package io.paperagent.v2.providers;

public record CorrelationId(String value) {
    public CorrelationId {
        value = ProviderValues.id(value, "correlationId");
    }
}
