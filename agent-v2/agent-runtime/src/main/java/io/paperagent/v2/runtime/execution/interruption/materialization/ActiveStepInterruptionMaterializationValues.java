package io.paperagent.v2.runtime.execution.interruption.materialization;

final class ActiveStepInterruptionMaterializationValues {
    private ActiveStepInterruptionMaterializationValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw validation(
                    ActiveStepInterruptionMaterializationValidationCode
                            .REQUIRED_VALUE_MISSING,
                    ActiveStepInterruptionMaterializationStage.INPUT,
                    path);
        }
        return value;
    }

    static String identifier(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw validation(
                    ActiveStepInterruptionMaterializationValidationCode
                            .INVALID_IDENTIFIER,
                    ActiveStepInterruptionMaterializationStage.INPUT,
                    path);
        }
        return value;
    }

    static ActiveStepInterruptionMaterializationValidationException validation(
            ActiveStepInterruptionMaterializationValidationCode code,
            ActiveStepInterruptionMaterializationStage stage,
            String path) {
        return new ActiveStepInterruptionMaterializationValidationException(
                code, stage, path);
    }

    static ActiveStepInterruptionMaterializationProtocolException protocol(
            ActiveStepInterruptionMaterializationProtocolCode code,
            ActiveStepInterruptionMaterializationStage stage,
            String path) {
        return new ActiveStepInterruptionMaterializationProtocolException(
                code, stage, path);
    }
}
