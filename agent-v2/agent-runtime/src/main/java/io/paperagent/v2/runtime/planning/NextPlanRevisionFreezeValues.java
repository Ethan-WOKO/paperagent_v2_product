package io.paperagent.v2.runtime.planning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NextPlanRevisionFreezeValues {
    private NextPlanRevisionFreezeValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(
                    NextPlanRevisionFreezeValidationCode.REQUIRED_VALUE_MISSING,
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
                        NextPlanRevisionFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                        path,
                        "collection must not contain null elements");
            }
            snapshot.add(value);
        }
        return List.copyOf(snapshot);
    }

    static <K, V> Map<K, V> map(Map<K, V> values, String path) {
        required(values, path);
        Map<K, V> snapshot = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                fail(
                        NextPlanRevisionFreezeValidationCode.NULL_COLLECTION_ELEMENT,
                        path,
                        "map keys and values must not be null");
            }
            snapshot.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(snapshot);
    }

    static void fail(
            NextPlanRevisionFreezeValidationCode code,
            String path,
            String message) {
        throw new NextPlanRevisionFreezeValidationException(code, path, message);
    }
}
