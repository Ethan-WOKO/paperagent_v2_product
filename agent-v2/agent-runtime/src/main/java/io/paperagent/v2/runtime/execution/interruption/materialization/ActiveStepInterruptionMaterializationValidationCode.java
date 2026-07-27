package io.paperagent.v2.runtime.execution.interruption.materialization;

public enum ActiveStepInterruptionMaterializationValidationCode {
    REQUIRED_VALUE_MISSING,
    INVALID_IDENTIFIER,
    EVENT_SELF_CAUSATION,
    CHECKPOINT_TIME_REGRESSION
}
