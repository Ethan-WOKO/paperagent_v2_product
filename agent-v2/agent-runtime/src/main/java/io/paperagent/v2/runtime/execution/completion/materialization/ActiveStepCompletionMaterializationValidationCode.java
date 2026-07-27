package io.paperagent.v2.runtime.execution.completion.materialization;

public enum ActiveStepCompletionMaterializationValidationCode {
    REQUIRED_VALUE_MISSING,
    INVALID_TEXT,
    EVENT_SELF_CAUSATION,
    EVENT_ID_CONFLICT,
    DUPLICATE_RECEIPT_ID,
    RECEIPT_OVERLAP,
    TIME_REGRESSION,
    REVISION_ID_REUSE,
    REVISION_NUMBER_OVERFLOW
}
