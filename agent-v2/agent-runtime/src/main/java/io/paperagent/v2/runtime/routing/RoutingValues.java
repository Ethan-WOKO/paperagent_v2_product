package io.paperagent.v2.runtime.routing;

import java.util.LinkedHashSet;
import java.util.Set;

final class RoutingValues {
    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

    private RoutingValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(
                    RoutingValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static String id(String value, String path) {
        required(value, path);
        if (!value.matches(ID_PATTERN)) {
            fail(
                    RoutingValidationCode.INVALID_ID,
                    path,
                    "ID contains unsupported characters");
        }
        return value;
    }

    static <T> Set<T> set(Set<T> values, String path) {
        required(values, path);
        if (values.stream().anyMatch(value -> value == null)) {
            fail(
                    RoutingValidationCode.NULL_COLLECTION_ELEMENT,
                    path,
                    "collection must not contain null elements");
        }
        return Set.copyOf(new LinkedHashSet<>(values));
    }

    static void fail(
            RoutingValidationCode code,
            String path,
            String message) {
        throw new RoutingValidationException(code, path, message);
    }
}
