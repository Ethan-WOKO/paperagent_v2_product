package io.paperagent.v2.runtime.execution.completion.materialization;

public final class ActiveStepCompletionMaterializationProtocolException
        extends IllegalStateException {
    private final ActiveStepCompletionMaterializationProtocolCode code;
    private final ActiveStepCompletionMaterializationStage stage;
    private final String path;

    ActiveStepCompletionMaterializationProtocolException(
            ActiveStepCompletionMaterializationProtocolCode code,
            ActiveStepCompletionMaterializationStage stage,
            String path) {
        super("active-Step completion materialization protocol failed");
        this.code = code;
        this.stage = stage;
        this.path = path;
    }

    public ActiveStepCompletionMaterializationProtocolCode code() {
        return code;
    }

    public ActiveStepCompletionMaterializationStage stage() {
        return stage;
    }

    public String path() {
        return path;
    }
}
