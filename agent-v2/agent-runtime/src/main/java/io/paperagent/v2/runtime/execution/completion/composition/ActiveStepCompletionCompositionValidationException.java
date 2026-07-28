package io.paperagent.v2.runtime.execution.completion.composition;

public final class ActiveStepCompletionCompositionValidationException
        extends IllegalArgumentException {
    private final ActiveStepCompletionCompositionValidationCode code;
    private final String path;

    ActiveStepCompletionCompositionValidationException(
            ActiveStepCompletionCompositionValidationCode code,
            String path) {
        super("active-Step completion composition validation failed: code="
                + code + ", path=" + path);
        this.code = code;
        this.path = path;
    }

    public ActiveStepCompletionCompositionValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
