package com.yanban.api.agent;

/** The terminal reason of a synchronous coordinator invocation. */
public enum AgentStopReason {
    COMPLETED,
    RUNTIME_FAILED,
    RUNTIME_EXCEPTION,
    NO_RUNTIME_ADAPTER,
    POLICY_REJECTED,
    TOOL_CALL_BUDGET_EXHAUSTED,
    MAX_STEPS_BUDGET_EXHAUSTED,
    MODEL_OUTPUT_TRUNCATED,
    PLAN_PARTIAL,
    CANCELLED,
    TIMED_OUT,
    PAUSED,
    WAITING_FOR_USER
}
