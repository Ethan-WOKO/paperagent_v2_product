package com.yanban.api.agent.reactplan;

public enum ReactPlanCompletionDecision {
    READY,
    WAITING_FOR_RECEIPT,
    WAITING_FOR_DELIVERY,
    DELIVERY_BINDING_MISMATCH,
    SYSTEM_FAILURE,
    CANCELLED,
    TIMED_OUT
}
