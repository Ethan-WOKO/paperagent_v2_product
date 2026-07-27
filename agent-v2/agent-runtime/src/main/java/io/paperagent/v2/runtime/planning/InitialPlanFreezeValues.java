package io.paperagent.v2.runtime.planning;

import java.util.ArrayList;
import java.util.List;

final class InitialPlanFreezeValues {
    private InitialPlanFreezeValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(
                    InitialPlanFreezeValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static <T> List<T> list(List<T> values, String path) {
        required(values, path);
        List<T> snapshot = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                fail(
                        InitialPlanFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                        path,
                        "collection must not contain null elements");
            }
            snapshot.add(value);
        }
        return List.copyOf(snapshot);
    }

    static void fail(
            InitialPlanFreezeValidationCode code,
            String path,
            String message) {
        throw new InitialPlanFreezeValidationException(code, path, message);
    }
}
