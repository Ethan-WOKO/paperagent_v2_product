package io.paperagent.v2.runtime.execution.activation.materialization;

final class CommittedStepActivationMaterializationValues {
    private CommittedStepActivationMaterializationValues() {
    }

    static <T> T required(T value, String path) {
        if (value == null) {
            throw failure(
                    CommittedStepActivationMaterializationValidationCode
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
                    CommittedStepActivationMaterializationValidationCode
                            .INVALID_IDENTIFIER,
                    path,
                    "identifier must not be blank");
        }
        return value;
    }

    static CommittedStepActivationMaterializationValidationException
            stepNotEligible() {
        return failure(
                CommittedStepActivationMaterializationValidationCode
                        .STEP_NOT_ELIGIBLE,
                "committedStepActivationMaterializationRequest.stepId",
                "Step is not eligible for activation");
    }

    static CommittedStepActivationMaterializationValidationException failure(
            CommittedStepActivationMaterializationValidationCode code,
            String path,
            String message) {
        return new CommittedStepActivationMaterializationValidationException(
                code,
                path,
                message);
    }
}
