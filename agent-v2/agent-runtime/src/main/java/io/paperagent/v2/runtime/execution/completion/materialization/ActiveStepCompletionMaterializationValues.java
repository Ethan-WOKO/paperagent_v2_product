package io.paperagent.v2.runtime.execution.completion.materialization;

final class ActiveStepCompletionMaterializationValues {
    private ActiveStepCompletionMaterializationValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .REQUIRED_VALUE_MISSING,
                    ActiveStepCompletionMaterializationStage.INPUT,
                    path);
        }
        return value;
    }

    static String text(String value, String path) {
        required(value, path);
        if (value.isBlank()) {
            throw validation(
                    ActiveStepCompletionMaterializationValidationCode
                            .INVALID_TEXT,
                    ActiveStepCompletionMaterializationStage.INPUT,
                    path);
        }
        return value;
    }

    static ActiveStepCompletionMaterializationValidationException validation(
            ActiveStepCompletionMaterializationValidationCode code,
            ActiveStepCompletionMaterializationStage stage,
            String path) {
        return new ActiveStepCompletionMaterializationValidationException(
                code, stage, path);
    }

    static ActiveStepCompletionMaterializationProtocolException protocol(
            ActiveStepCompletionMaterializationProtocolCode code,
            ActiveStepCompletionMaterializationStage stage,
            String path) {
        return new ActiveStepCompletionMaterializationProtocolException(
                code, stage, path);
    }
}
