package io.paperagent.v2.runtime.execution;

/**
 * A deterministic execution-start materialization validation failure with an
 * inspectable code and path.
 */
public final class ExecutionStartMaterializationValidationException
        extends IllegalArgumentException {
    private final ExecutionStartMaterializationValidationCode code;
    private final String path;

    ExecutionStartMaterializationValidationException(
            ExecutionStartMaterializationValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public ExecutionStartMaterializationValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
