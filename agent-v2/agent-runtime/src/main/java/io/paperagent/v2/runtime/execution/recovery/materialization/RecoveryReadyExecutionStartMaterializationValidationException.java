package io.paperagent.v2.runtime.execution.recovery.materialization;

/**
 * A deterministic recovery-ready materialization failure with an inspectable
 * code and path.
 */
public final class RecoveryReadyExecutionStartMaterializationValidationException
        extends IllegalArgumentException {
    private final RecoveryReadyExecutionStartMaterializationValidationCode code;
    private final String path;

    RecoveryReadyExecutionStartMaterializationValidationException(
            RecoveryReadyExecutionStartMaterializationValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public RecoveryReadyExecutionStartMaterializationValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
