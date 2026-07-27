package io.paperagent.v2.runtime.execution.start;

public final class FreshExecutionStartValidationException
        extends IllegalArgumentException {
    private final FreshExecutionStartValidationCode code;
    private final String path;

    FreshExecutionStartValidationException(
            FreshExecutionStartValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public FreshExecutionStartValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
