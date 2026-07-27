package io.paperagent.v2.runtime.execution;

/**
 * A deterministic fresh-execution gate validation failure with an inspectable
 * code and path.
 */
public final class FreshExecutionGateValidationException
        extends IllegalArgumentException {
    private final FreshExecutionGateValidationCode code;
    private final String path;

    FreshExecutionGateValidationException(
            FreshExecutionGateValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public FreshExecutionGateValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
