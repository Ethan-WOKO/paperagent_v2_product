package io.paperagent.v2.runtime.execution.completion.materialization;

public final class ActiveStepCompletionMaterializationValidationException
        extends IllegalArgumentException {
    private final ActiveStepCompletionMaterializationValidationCode code;
    private final ActiveStepCompletionMaterializationStage stage;
    private final String path;

    ActiveStepCompletionMaterializationValidationException(
            ActiveStepCompletionMaterializationValidationCode code,
            ActiveStepCompletionMaterializationStage stage,
            String path) {
        super("active-Step completion materialization validation failed");
        this.code = code;
        this.stage = stage;
        this.path = path;
    }

    public ActiveStepCompletionMaterializationValidationCode code() {
        return code;
    }

    public ActiveStepCompletionMaterializationStage stage() {
        return stage;
    }

    public String path() {
        return path;
    }
}
