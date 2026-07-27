package io.paperagent.v2.providers;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class ProviderValues {
    private ProviderValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(ProviderValidationCode.REQUIRED_VALUE_MISSING, path, "value is required");
        }
        return value;
    }

    static String text(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            fail(ProviderValidationCode.REQUIRED_TEXT_BLANK, path, "text must not be blank");
        }
        return value;
    }

    static String id(String value, String path) {
        text(value, path);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            fail(ProviderValidationCode.INVALID_ID, path, "ID contains unsupported characters");
        }
        return value;
    }

    static <T> List<T> list(Collection<? extends T> values, String path) {
        required(values, path);
        rejectNullElements(values, path);
        return List.copyOf(values);
    }

    static <K, V> Map<K, V> map(Map<? extends K, ? extends V> values, String path) {
        required(values, path);
        if (values.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            fail(
                    ProviderValidationCode.NULL_COLLECTION_ELEMENT,
                    path,
                    "map keys and values must not be null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    static <T, K> void unique(
            Collection<T> values,
            Function<T, K> key,
            String path) {
        Set<K> seen = new LinkedHashSet<>();
        for (T value : values) {
            if (!seen.add(key.apply(value))) {
                fail(
                        ProviderValidationCode.DUPLICATE_ID,
                        path,
                        "duplicate identifier: " + key.apply(value));
            }
        }
    }

    static void fail(ProviderValidationCode code, String path, String message) {
        throw new ProviderValidationException(code, path, message);
    }

    private static void rejectNullElements(Collection<?> values, String path) {
        for (Object value : values) {
            if (value == null) {
                fail(
                        ProviderValidationCode.NULL_COLLECTION_ELEMENT,
                        path,
                        "collection must not contain null elements");
            }
        }
    }
}
