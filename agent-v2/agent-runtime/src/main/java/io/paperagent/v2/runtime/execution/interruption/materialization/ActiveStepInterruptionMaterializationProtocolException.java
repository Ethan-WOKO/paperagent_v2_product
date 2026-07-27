package io.paperagent.v2.runtime.execution.interruption.materialization;

public final class ActiveStepInterruptionMaterializationProtocolException
        extends IllegalStateException {
    private final ActiveStepInterruptionMaterializationProtocolCode code;
    private final ActiveStepInterruptionMaterializationStage stage;
    private final String path;

    ActiveStepInterruptionMaterializationProtocolException(
            ActiveStepInterruptionMaterializationProtocolCode code,
            ActiveStepInterruptionMaterializationStage stage,
            String path) {
        super("active-Step interruption materialization protocol failed");
        this.code = code;
        this.stage = stage;
        this.path = path;
    }

    public ActiveStepInterruptionMaterializationProtocolCode code() {
        return code;
    }

    public ActiveStepInterruptionMaterializationStage stage() {
        return stage;
    }

    public String path() {
        return path;
    }
}
