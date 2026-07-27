package io.paperagent.v2.runtime.execution.recovery.composition;

public final class StepRecoveryValidationException extends IllegalArgumentException {
    private final StepRecoveryValidationCode code;
    private final String path;

    StepRecoveryValidationException(
            StepRecoveryValidationCode code,
            String path) {
        super("Step recovery validation failure: code="
                + StepRecoveryCompositionValues.requiredInternal(code, "code")
                + ", path="
                + StepRecoveryCompositionValues.validationPath(path));
        this.code = code;
        this.path = path;
    }

    public StepRecoveryValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
