package io.paperagent.v2.runtime.execution.kernel;

public final class SingleTurnStepKernelValidationException extends IllegalArgumentException {
    private final SingleTurnStepKernelValidationCode code;
    private final String path;

    SingleTurnStepKernelValidationException(
            SingleTurnStepKernelValidationCode code,
            String path) {
        super("Single-turn Step kernel validation failure: code="
                + SingleTurnStepKernelValues.requiredInternal(code, "code")
                + ", path="
                + SingleTurnStepKernelValues.validationPath(path));
        this.code = code;
        this.path = path;
    }

    public SingleTurnStepKernelValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
