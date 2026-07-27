package io.paperagent.v2.contracts;

public record TextValue(String value) implements ContractValue {
    public TextValue {
        Contracts.required(value, "textValue.value");
    }
}
