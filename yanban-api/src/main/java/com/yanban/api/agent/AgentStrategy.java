package com.yanban.api.agent;

public enum AgentStrategy {
    /** Server-resolved request; no runtime adapter may execute this value directly. */
    AUTO,
    DIRECT,
    SINGLE_STEP_REACT,
    PLAN_EXECUTE,
    PLAN_EXECUTE_WITH_REFLECTION,
    LONG_RUNNING_TOOL_TASK,
    WAIT_FOR_USER_CONFIRMATION
}
