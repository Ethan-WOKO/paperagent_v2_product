package io.paperagent.v2.runtime.execution.interruption.materialization;

public final class ActiveStepInterruptionMaterializationValidationException
        extends IllegalArgumentException {
    private final ActiveStepInterruptionMaterializationValidationCode code;
    private final ActiveStepInterruptionMaterializationStage stage;
    private final String path;

    ActiveStepInterruptionMaterializationValidationException(
            ActiveStepInterruptionMaterializationValidationCode code,
            ActiveStepInterruptionMaterializationStage stage,
            String path) {
        super("active-Step interruption materialization validation failed");
        this.code = code;
        this.stage = stage;
        this.path = path;
    }

    public ActiveStepInterruptionMaterializationValidationCode code() {
        return code;
    }

    public ActiveStepInterruptionMaterializationStage stage() {
        return stage;
    }

    public String path() {
        return path;
    }
}
