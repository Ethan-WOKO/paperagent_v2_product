package io.paperagent.v2.contracts;

/**
 * Framework-neutral structured data for tool and event facts.
 */
public sealed interface ContractValue
        permits TextValue, NumberValue, BooleanValue, NullValue, ListValue, ObjectValue {
}
