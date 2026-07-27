package io.paperagent.v2.sandbox;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SandboxValues {
    private SandboxValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(SandboxValidationCode.REQUIRED_VALUE_MISSING, path, "value is required");
        }
        return value;
    }

    static String text(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            fail(SandboxValidationCode.REQUIRED_TEXT_BLANK, path, "text must not be blank");
        }
        return value;
    }

    static String boundedText(String value, String path, int maxLength) {
        text(value, path);
        return boundedValue(value, path, maxLength);
    }

    static String boundedValue(String value, String path, int maxLength) {
        required(value, path);
        if (value.indexOf('\0') >= 0) {
            fail(
                    SandboxValidationCode.INVALID_TEXT_CHARACTER,
                    path,
                    "text must not contain NUL");
        }
        if (value.length() > maxLength) {
            fail(
                    SandboxValidationCode.BOUND_EXCEEDED,
                    path,
                    "text exceeds maximum length " + maxLength);
        }
        return value;
    }

    static String id(String value, String path) {
        text(value, path);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            fail(SandboxValidationCode.INVALID_ID, path, "ID contains unsupported characters");
        }
        return value;
    }

    static String environmentName(String value, String path) {
        boundedText(value, path, SandboxLimits.MAX_ENVIRONMENT_NAME_LENGTH);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            fail(
                    SandboxValidationCode.INVALID_ENVIRONMENT_NAME,
                    path,
                    "environment name contains unsupported characters");
        }
        return value;
    }

    static <T> List<T> list(Collection<? extends T> values, String path) {
        required(values, path);
        for (T value : values) {
            if (value == null) {
                fail(
                        SandboxValidationCode.NULL_COLLECTION_ELEMENT,
                        path,
                        "collection must not contain null elements");
            }
        }
        return List.copyOf(values);
    }

    static <K, V> Map<K, V> map(Map<? extends K, ? extends V> values, String path) {
        required(values, path);
        for (Map.Entry<? extends K, ? extends V> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                fail(
                        SandboxValidationCode.NULL_COLLECTION_ELEMENT,
                        path,
                        "map keys and values must not be null");
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    static Map<String, String> boundedMetadata(
            Map<String, String> values,
            String path) {
        Map<String, String> copy = map(values, path);
        if (copy.size() > SandboxLimits.MAX_METADATA_ENTRIES) {
            fail(
                    SandboxValidationCode.BOUND_EXCEEDED,
                    path,
                    "metadata exceeds maximum entry count "
                            + SandboxLimits.MAX_METADATA_ENTRIES);
        }
        copy.forEach((key, value) -> {
            boundedText(
                    key,
                    path + ".key",
                    SandboxLimits.MAX_METADATA_KEY_LENGTH);
            boundedText(
                    value,
                    path + ".value",
                    SandboxLimits.MAX_METADATA_VALUE_LENGTH);
        });
        return copy;
    }

    static void fail(SandboxValidationCode code, String path, String message) {
        throw new SandboxValidationException(code, path, message);
    }
}
