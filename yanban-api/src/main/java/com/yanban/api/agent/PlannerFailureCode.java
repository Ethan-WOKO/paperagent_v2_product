package com.yanban.api.agent;

/** Fail-closed planner outcomes. None of these represents an executable plan. */
public enum PlannerFailureCode {
    MODEL_CALL_FAILED,
    EMPTY_RESPONSE,
    INVALID_PLAN,
    NO_STEPS
}
