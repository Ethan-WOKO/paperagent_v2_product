package io.paperagent.v2.runtime.execution;

final class ExecutionStartMaterializationValues {
    private ExecutionStartMaterializationValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    ExecutionStartMaterializationValidationCode
                            .REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static String identifier(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw failure(
                    ExecutionStartMaterializationValidationCode
                            .INVALID_IDENTIFIER,
                    path,
                    "identifier must not be blank");
        }
        return value;
    }

    static ExecutionStartMaterializationValidationException failure(
            ExecutionStartMaterializationValidationCode code,
            String path,
            String message) {
        return new ExecutionStartMaterializationValidationException(
                code,
                path,
                message);
    }
}
