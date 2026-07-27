package io.paperagent.v2.runtime.execution.completion.materialization;

public enum ActiveStepCompletionMaterializationStage {
    INPUT,
    RECOVERED_AUTHORITY,
    COMPLETION_FACT,
    EVENT,
    REVISION,
    CHECKPOINT
}
