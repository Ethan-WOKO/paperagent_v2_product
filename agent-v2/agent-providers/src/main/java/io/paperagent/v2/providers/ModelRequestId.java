package io.paperagent.v2.providers;

public record ModelRequestId(String value) {
    public ModelRequestId {
        value = ProviderValues.id(value, "modelRequestId");
    }
}
