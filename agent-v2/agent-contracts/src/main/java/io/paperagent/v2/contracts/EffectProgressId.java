package io.paperagent.v2.contracts;

/**
 * Opaque identity for one durable effect progress marker.
 */
public record EffectProgressId(String value) {
    public EffectProgressId {
        value = Contracts.id(value, "effectProgressId");
    }

    @Override
    public String toString() {
        return "EffectProgressId[value=<provided>]";
    }
}
