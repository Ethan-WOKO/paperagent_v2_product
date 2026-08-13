package io.paperagent.v2.chain.delivery;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ChainDeliveryCanonical {
    private ChainDeliveryCanonical() {
    }

    static String materializedPayload(Object value, String bodyRef) {
        return json(toTree(value, bodyRef));
    }

    static String jsonValue(Object value) {
        return json(toTree(value, null));
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object toTree(Object value, String bodyRef) {
        if (value == null || value instanceof String
                || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>();
            collection.forEach(element -> values.add(toTree(element, bodyRef)));
            return values;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> values = new TreeMap<>();
            map.forEach((key, element) -> values.put(
                    key.toString(), toTree(element, bodyRef)));
            return values;
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(toTree(Array.get(value, index), bodyRef));
            }
            return values;
        }
        if (value.getClass().isRecord()) {
            TreeMap<String, Object> fields = new TreeMap<>();
            for (RecordComponent component :
                    value.getClass().getRecordComponents()) {
                try {
                    String field = component.getName();
                    Object componentValue = component.getAccessor().invoke(value);
                    if ("inlineAnswerBody".equals(field)) {
                        fields.put("answerBodyRef", bodyRef);
                    } else {
                        fields.put(field, toTree(componentValue, bodyRef));
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(
                            "cannot canonicalize delivery payload", failure);
                }
            }
            return fields;
        }
        throw new IllegalArgumentException(
                "unsupported delivery payload " + value.getClass().getName());
    }

    private static String json(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            quote(output, text);
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) output.append(',');
                append(output, list.get(index));
            }
            output.append(']');
        } else if (value instanceof Map<?, ?> map) {
            var entries = map.entrySet().stream()
                    .sorted(Comparator.comparing(
                            entry -> entry.getKey().toString()))
                    .toList();
            output.append('{');
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) output.append(',');
                quote(output, entries.get(index).getKey().toString());
                output.append(':');
                append(output, entries.get(index).getValue());
            }
            output.append('}');
        } else {
            throw new IllegalArgumentException(
                    "unsupported canonical JSON value");
        }
    }

    private static void quote(StringBuilder output, String text) {
        output.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format(
                                "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
