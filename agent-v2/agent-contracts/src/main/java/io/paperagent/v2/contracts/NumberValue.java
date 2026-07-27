package io.paperagent.v2.contracts;

import java.math.BigDecimal;

public record NumberValue(BigDecimal value) implements ContractValue {
    public NumberValue {
        Contracts.required(value, "numberValue.value");
    }
}
