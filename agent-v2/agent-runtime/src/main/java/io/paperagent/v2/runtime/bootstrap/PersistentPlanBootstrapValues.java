package io.paperagent.v2.runtime.bootstrap;

final class PersistentPlanBootstrapValues {
    private PersistentPlanBootstrapValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            fail(
                    PersistentPlanBootstrapValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static void fail(
            PersistentPlanBootstrapValidationCode code,
            String path,
            String message) {
        throw new PersistentPlanBootstrapValidationException(code, path, message);
    }
}
