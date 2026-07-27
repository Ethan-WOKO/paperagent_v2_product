package io.paperagent.v2.runtime.execution.interruption.composition;

public final class ActiveStepInterruptionCompositionValidationException
        extends IllegalArgumentException {
    private final ActiveStepInterruptionCompositionValidationCode code;
    private final String path;

    ActiveStepInterruptionCompositionValidationException(
            ActiveStepInterruptionCompositionValidationCode code,
            String path) {
        super("active-Step interruption composition validation failed: code="
                + code + ", path=" + path);
        this.code = code;
        this.path = path;
    }

    public ActiveStepInterruptionCompositionValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
