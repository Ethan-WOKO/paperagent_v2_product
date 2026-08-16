package com.yanban.api.agent.reactplan;

public enum ReactPlanProgressPhase {
    READY_TO_EXECUTE,
    EXECUTING,
    READY_TO_DELIVER,
    COMPLETED,
    SYSTEM_FAILURE,
    CANCELLED,
    TIMED_OUT
}
