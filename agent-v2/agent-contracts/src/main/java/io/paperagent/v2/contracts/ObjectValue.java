package io.paperagent.v2.contracts;

import java.util.Map;

public record ObjectValue(Map<String, ContractValue> values) implements ContractValue {
    public ObjectValue {
        values = Contracts.map(values, "objectValue.values");
        values.keySet().forEach(key -> Contracts.text(key, "objectValue.values.key"));
    }
}
