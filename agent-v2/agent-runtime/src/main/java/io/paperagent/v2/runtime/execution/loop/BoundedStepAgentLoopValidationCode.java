package io.paperagent.v2.runtime.execution.loop;

public enum BoundedStepAgentLoopValidationCode {
    REQUIRED_VALUE_MISSING,
    INVALID_MAX_TURNS,
    INVALID_TURNS_EXECUTED,
    INVALID_DURABLE_INTENT_COUNT,
    NULL_DURABLE_INTENT
}
