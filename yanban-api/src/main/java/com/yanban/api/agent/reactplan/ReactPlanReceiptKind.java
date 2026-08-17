package com.yanban.api.agent.reactplan;

/** Product classification of a formal terminal Receipt. */
public enum ReactPlanReceiptKind {
    TASK_SUCCEEDED,
    TASK_FAILED,
    SYSTEM_FAILED,
    CANCELLED,
    TIMED_OUT
}
