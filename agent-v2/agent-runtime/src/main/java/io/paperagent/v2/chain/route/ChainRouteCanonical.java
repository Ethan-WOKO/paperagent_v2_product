package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.ChainPersistenceRecords;

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

final class ChainRouteCanonical {
    private ChainRouteCanonical() {
    }

    static ChainPersistenceRecords.CanonicalJson canonical(Object value) {
        String json = json(toTree(value));
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    static String payload(Object value) {
        return payload(value, null);
    }

    static String payload(Object value, String answerBodyRef) {
        return json(toTree(value, answerBodyRef));
    }

    static String jsonList(List<String> values) {
        return json(new ArrayList<>(values));
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object toTree(Object value) {
        return toTree(value, null);
    }

    private static Object toTree(Object value, String answerBodyRef) {
        if (value == null || value instanceof String
                || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?> collection) {
            List<Object> elements = new ArrayList<>();
            collection.forEach(element -> elements.add(
                    toTree(element, answerBodyRef)));
            return elements;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> fields = new TreeMap<>();
            map.forEach((key, fieldValue) ->
                    fields.put(key.toString(),
                            toTree(fieldValue, answerBodyRef)));
            return fields;
        }
        if (value.getClass().isArray()) {
            List<Object> elements = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                elements.add(toTree(
                        Array.get(value, index), answerBodyRef));
            }
            return elements;
        }
        if (value.getClass().isRecord()) {
            TreeMap<String, Object> fields = new TreeMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    String name = component.getName();
                    if (answerBodyRef != null
                            && "inlineAnswerBody".equals(name)) {
                        fields.put("answerBodyRef", answerBodyRef);
                    } else {
                        fields.put(name, toTree(
                                component.getAccessor().invoke(value),
                                answerBodyRef));
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("cannot canonicalize route payload", failure);
                }
            }
            return fields;
        }
        throw new IllegalArgumentException(
                "unsupported route canonical value " + value.getClass().getName());
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
            output.append('{');
            var entries = map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString())).toList();
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) output.append(',');
                quote(output, entries.get(index).getKey().toString());
                output.append(':');
                append(output, entries.get(index).getValue());
            }
            output.append('}');
        } else {
            throw new IllegalArgumentException("unsupported canonical JSON value");
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
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
