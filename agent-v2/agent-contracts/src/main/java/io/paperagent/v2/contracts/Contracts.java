package io.paperagent.v2.contracts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class Contracts {
    private Contracts() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(ViolationCode.REQUIRED_VALUE_MISSING, path, "value is required");
        }
        return value;
    }

    static String text(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            fail(ViolationCode.REQUIRED_TEXT_BLANK, path, "text must not be blank");
        }
        return value;
    }

    static String id(String value, String path) {
        text(value, path);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            fail(ViolationCode.INVALID_ID, path, "ID contains unsupported characters");
        }
        return value;
    }

    static <T> List<T> list(Collection<T> values, String path) {
        required(values, path);
        rejectNullElements(values, path);
        List<T> copy = List.copyOf(values);
        return copy;
    }

    static <T> Set<T> set(Collection<T> values, String path) {
        required(values, path);
        rejectNullElements(values, path);
        return Set.copyOf(new LinkedHashSet<>(values));
    }

    static <K, V> Map<K, V> map(Map<K, V> values, String path) {
        required(values, path);
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            fail(ViolationCode.NULL_COLLECTION_ELEMENT, path, "map keys and values must not be null");
        }
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    static <T, K> void unique(Collection<T> values, Function<T, K> key, String path) {
        Set<K> seen = new LinkedHashSet<>();
        for (T value : values) {
            if (!seen.add(key.apply(value))) {
                fail(ViolationCode.DUPLICATE_ID, path, "duplicate identifier: " + key.apply(value));
            }
        }
    }

    static ContractViolation violation(ViolationCode code, String path, String message) {
        return new ContractViolation(code, path, message);
    }

    static void requireNoViolations(Collection<ContractViolation> violations) {
        if (!violations.isEmpty()) {
            throw new ContractViolationException(List.copyOf(violations));
        }
    }

    static List<ContractViolation> violations() {
        return new ArrayList<>();
    }

    static void fail(ViolationCode code, String path, String message) {
        throw new ContractViolationException(List.of(violation(code, path, message)));
    }

    private static void rejectNullElements(Collection<?> values, String path) {
        if (values.stream().anyMatch(value -> value == null)) {
            fail(ViolationCode.NULL_COLLECTION_ELEMENT, path, "collection must not contain null elements");
        }
    }
}
