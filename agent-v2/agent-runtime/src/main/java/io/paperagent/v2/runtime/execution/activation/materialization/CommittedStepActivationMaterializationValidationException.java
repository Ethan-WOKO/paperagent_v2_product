package io.paperagent.v2.runtime.execution.activation.materialization;

/**
 * A deterministic committed-Step materialization validation failure with an
 * inspectable code and path.
 */
public final class CommittedStepActivationMaterializationValidationException
        extends IllegalArgumentException {
    private final CommittedStepActivationMaterializationValidationCode code;
    private final String path;

    CommittedStepActivationMaterializationValidationException(
            CommittedStepActivationMaterializationValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public CommittedStepActivationMaterializationValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
