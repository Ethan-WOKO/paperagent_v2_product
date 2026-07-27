package io.paperagent.v2.runtime.execution.activation.composition;

public final class StepActivationCompositionValidationException
        extends IllegalArgumentException {
    private final StepActivationCompositionValidationCode code;
    private final String path;

    StepActivationCompositionValidationException(
            StepActivationCompositionValidationCode code,
            String path) {
        super("Step activation composition validation failure: code="
                + StepActivationCompositionValues.requiredInternal(code, "code")
                + ", path="
                + StepActivationCompositionValues.validationPath(path));
        this.code = code;
        this.path = StepActivationCompositionValues.validationPath(path);
    }

    public StepActivationCompositionValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
