package io.paperagent.v2.contracts;

public record FinalSynthesisId(String value) {
    public FinalSynthesisId {
        value = Contracts.id(value, "finalSynthesisId");
    }
}
