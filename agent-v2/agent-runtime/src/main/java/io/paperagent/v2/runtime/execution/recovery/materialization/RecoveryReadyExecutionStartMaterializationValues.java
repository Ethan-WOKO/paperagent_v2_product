package io.paperagent.v2.runtime.execution.recovery.materialization;

final class RecoveryReadyExecutionStartMaterializationValues {
    private RecoveryReadyExecutionStartMaterializationValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    RecoveryReadyExecutionStartMaterializationValidationCode
                            .REQUIRED_VALUE_MISSING,
                    path,
                    "value is required");
        }
        return value;
    }

    static RecoveryReadyExecutionStartMaterializationValidationException
            failure(
                    RecoveryReadyExecutionStartMaterializationValidationCode
                            code,
                    String path,
                    String message) {
        return new RecoveryReadyExecutionStartMaterializationValidationException(
                code,
                path,
                message);
    }
}
