package com.yanban.api.agent.v2.tool;

import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates the deliberately small JSON-Schema subset used by V2 tools. */
final class V2ToolArgumentSchemaValidator {
    private V2ToolArgumentSchemaValidator() {
    }

    static boolean accepts(ObjectValue schema, ObjectValue arguments) {
        return schema != null
                && arguments != null
                && acceptsValue(schema, arguments);
    }

    private static boolean acceptsValue(
            ObjectValue schema, ContractValue value) {
        String type = text(schema.values(), "type");
        if (type == null || !matchesType(type, value)) {
            return false;
        }
        ContractValue constant = schema.values().get("const");
        if (constant != null && !constant.equals(value)) {
            return false;
        }
        return switch (type) {
            case "object" -> object(schema, (ObjectValue) value);
            case "array" -> array(schema, (ListValue) value);
            case "string" -> string(schema, (TextValue) value);
            case "integer" -> integer(schema, (NumberValue) value);
            case "boolean" -> true;
            default -> false;
        };
    }

    private static boolean object(ObjectValue schema, ObjectValue value) {
        if (!(schema.values().get("properties")
                instanceof ObjectValue properties)) {
            return false;
        }
        Set<String> required = required(schema.values().get("required"));
        if (required == null || !value.values().keySet().containsAll(required)) {
            return false;
        }
        boolean additional = booleanValue(
                schema.values().get("additionalProperties"), false);
        if (!additional
                && !properties.values().keySet()
                .containsAll(value.values().keySet())) {
            return false;
        }
        for (Map.Entry<String, ContractValue> entry
                : value.values().entrySet()) {
            ContractValue propertySchema = properties.values().get(
                    entry.getKey());
            if (propertySchema == null) {
                continue;
            }
            if (!(propertySchema instanceof ObjectValue objectSchema)
                    || !acceptsValue(objectSchema, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean array(ObjectValue schema, ListValue value) {
        Integer minimum = integerKeyword(schema.values(), "minItems");
        Integer maximum = integerKeyword(schema.values(), "maxItems");
        if (minimum == null || maximum == null
                || value.values().size() < minimum
                || value.values().size() > maximum
                || !(schema.values().get("items")
                instanceof ObjectValue itemSchema)) {
            return false;
        }
        if (booleanValue(schema.values().get("uniqueItems"), false)
                && new HashSet<>(value.values()).size()
                != value.values().size()) {
            return false;
        }
        return value.values().stream()
                .allMatch(item -> acceptsValue(itemSchema, item));
    }

    private static boolean string(ObjectValue schema, TextValue value) {
        Integer minimum = integerKeyword(schema.values(), "minLength");
        Integer maximum = integerKeyword(schema.values(), "maxLength");
        return (minimum == null || value.value().length() >= minimum)
                && (maximum == null || value.value().length() <= maximum);
    }

    private static boolean integer(ObjectValue schema, NumberValue value) {
        BigDecimal number = value.value();
        if (number.stripTrailingZeros().scale() > 0) {
            return false;
        }
        BigDecimal minimum = numberKeyword(schema.values(), "minimum");
        BigDecimal maximum = numberKeyword(schema.values(), "maximum");
        return (minimum == null || number.compareTo(minimum) >= 0)
                && (maximum == null || number.compareTo(maximum) <= 0);
    }

    private static boolean matchesType(String type, ContractValue value) {
        return switch (type) {
            case "object" -> value instanceof ObjectValue;
            case "array" -> value instanceof ListValue;
            case "string" -> value instanceof TextValue;
            case "integer" -> value instanceof NumberValue;
            case "boolean" -> value instanceof BooleanValue;
            default -> false;
        };
    }

    private static Set<String> required(ContractValue value) {
        if (!(value instanceof ListValue list)) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (ContractValue item : list.values()) {
            if (!(item instanceof TextValue text)
                    || !result.add(text.value())) {
                return null;
            }
        }
        return result;
    }

    private static String text(
            Map<String, ContractValue> values, String name) {
        ContractValue value = values.get(name);
        return value instanceof TextValue text ? text.value() : null;
    }

    private static Integer integerKeyword(
            Map<String, ContractValue> values, String name) {
        BigDecimal value = numberKeyword(values, name);
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    private static BigDecimal numberKeyword(
            Map<String, ContractValue> values, String name) {
        ContractValue value = values.get(name);
        return value instanceof NumberValue number ? number.value() : null;
    }

    private static boolean booleanValue(
            ContractValue value, boolean fallback) {
        return value instanceof BooleanValue bool
                ? bool.value() : fallback;
    }
}
