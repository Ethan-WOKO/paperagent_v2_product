package io.paperagent.v2.runtime.execution.recovery.composition;

public final class ExecutionStartRecoveryValidationException
        extends IllegalArgumentException {
    private final ExecutionStartRecoveryValidationCode code;
    private final String path;

    ExecutionStartRecoveryValidationException(
            ExecutionStartRecoveryValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public ExecutionStartRecoveryValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
