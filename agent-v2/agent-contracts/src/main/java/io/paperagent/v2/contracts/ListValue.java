package io.paperagent.v2.contracts;

import java.util.List;

public record ListValue(List<ContractValue> values) implements ContractValue {
    public ListValue {
        values = Contracts.list(values, "listValue.values");
    }
}
