package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChainContextCanonicalJson {
    private ChainContextCanonicalJson() {
    }

    static CanonicalJson canonicalObject(Map<String, ? extends ChainContextValue> fields) {
        String json = encode(ChainContextValue.object(fields));
        return new CanonicalJson(1, ChainContextDigests.sha256(json), json);
    }

    static String encode(ChainContextValue value) {
        StringBuilder result = new StringBuilder();
        append(result, value);
        return result.toString();
    }

    static Map<String, Object> parseObject(String json) {
        Object value = new Reader(json).read();
        if (!(value instanceof Map<?, ?>)) {
            throw invalid("canonical JSON root must be an object");
        }
        if (!json.equals(encode(fromParsed(value)))) {
            throw invalid("stored JSON is not in canonical form");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    private static ChainContextValue fromParsed(Object value) {
        if (value == null) {
            return ChainContextValue.nil();
        }
        if (value instanceof String text) {
            return ChainContextValue.text(text);
        }
        if (value instanceof Long number) {
            return ChainContextValue.number(number);
        }
        if (value instanceof Boolean bool) {
            return ChainContextValue.bool(bool);
        }
        if (value instanceof List<?> list) {
            return ChainContextValue.array(list.stream()
                    .map(ChainContextCanonicalJson::fromParsed).toList());
        }
        if (value instanceof Map<?, ?> map) {
            java.util.TreeMap<String, ChainContextValue> fields = new java.util.TreeMap<>();
            map.forEach((key, fieldValue) -> fields.put((String) key, fromParsed(fieldValue)));
            return ChainContextValue.object(fields);
        }
        throw invalid("unsupported stored canonical JSON value");
    }

    private static void append(StringBuilder result, ChainContextValue value) {
        if (value instanceof ChainContextValue.Text text) {
            quote(result, text.value());
        } else if (value instanceof ChainContextValue.NumberValue number) {
            result.append(number.value());
        } else if (value instanceof ChainContextValue.BooleanValue bool) {
            result.append(bool.value());
        } else if (value instanceof ChainContextValue.NullValue) {
            result.append("null");
        } else if (value instanceof ChainContextValue.ArrayValue array) {
            result.append('[');
            for (int index = 0; index < array.values().size(); index++) {
                if (index > 0) result.append(',');
                append(result, array.values().get(index));
            }
            result.append(']');
        } else if (value instanceof ChainContextValue.ObjectValue object) {
            result.append('{');
            List<Map.Entry<String, ChainContextValue>> entries = object.values().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList();
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) result.append(',');
                quote(result, entries.get(index).getKey());
                result.append(':');
                append(result, entries.get(index).getValue());
            }
            result.append('}');
        } else {
            throw new IllegalArgumentException("unsupported canonical context value");
        }
    }

    private static void quote(StringBuilder result, String value) {
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        result.append('"');
    }

    private static ChainContextException invalid(String message) {
        return new ChainContextException(
                ChainContextErrorCode.CONTEXT_MODULE_DIGEST_MISMATCH, message);
    }

    private static final class Reader {
        private final String input;
        private int offset;

        private Reader(String input) {
            this.input = java.util.Objects.requireNonNull(input, "json");
        }

        private Object read() {
            Object value = value();
            whitespace();
            if (offset != input.length()) throw invalid("trailing canonical JSON content");
            return value;
        }

        private Object value() {
            whitespace();
            if (offset >= input.length()) throw invalid("unexpected end of canonical JSON");
            return switch (input.charAt(offset)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            offset++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            while (true) {
                String key = string();
                if (result.containsKey(key)) throw invalid("duplicate canonical JSON key");
                whitespace(); expect(':');
                result.put(key, value());
                whitespace();
                if (take('}')) return result;
                expect(','); whitespace();
            }
        }

        private List<Object> array() {
            offset++;
            ArrayList<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                whitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            whitespace(); expect('"');
            StringBuilder result = new StringBuilder();
            while (offset < input.length()) {
                char character = input.charAt(offset++);
                if (character == '"') return result.toString();
                if (character == '\\') {
                    if (offset >= input.length()) throw invalid("unterminated canonical JSON escape");
                    char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(unicode());
                        default -> throw invalid("invalid canonical JSON escape");
                    }
                } else {
                    if (character < 0x20) throw invalid("control character in canonical JSON string");
                    result.append(character);
                }
            }
            throw invalid("unterminated canonical JSON string");
        }

        private char unicode() {
            if (offset + 4 > input.length()) throw invalid("incomplete unicode escape");
            try {
                char result = (char) Integer.parseInt(input.substring(offset, offset + 4), 16);
                offset += 4;
                return result;
            } catch (NumberFormatException failure) {
                throw invalid("invalid unicode escape");
            }
        }

        private Object number() {
            int start = offset;
            if (take('-') && offset >= input.length()) throw invalid("invalid number");
            while (offset < input.length() && Character.isDigit(input.charAt(offset))) offset++;
            if (start == offset || (input.charAt(start) == '-' && start + 1 == offset)) {
                throw invalid("unexpected canonical JSON token");
            }
            try {
                return Long.parseLong(input.substring(start, offset));
            } catch (NumberFormatException failure) {
                throw invalid("canonical JSON integer out of range");
            }
        }

        private Object literal(String literal, Object value) {
            if (!input.startsWith(literal, offset)) throw invalid("unexpected canonical JSON token");
            offset += literal.length();
            return value;
        }

        private void whitespace() {
            while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) offset++;
        }

        private boolean take(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw invalid("expected '" + expected + "'");
        }
    }
}
