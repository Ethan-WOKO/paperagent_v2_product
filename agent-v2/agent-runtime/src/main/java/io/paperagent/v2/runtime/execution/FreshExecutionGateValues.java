package io.paperagent.v2.runtime.execution;

final class FreshExecutionGateValues {
    private FreshExecutionGateValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    FreshExecutionGateValidationCode.REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static FreshExecutionGateValidationException failure(
            FreshExecutionGateValidationCode code,
            String path,
            String message) {
        return new FreshExecutionGateValidationException(code, path, message);
    }
}
