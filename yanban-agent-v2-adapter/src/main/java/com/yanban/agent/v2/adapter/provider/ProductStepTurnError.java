package com.yanban.agent.v2.adapter.provider;

/** Stable, sanitized product Step-turn failure categories. */
public enum ProductStepTurnError {
    INVALID_CONFIGURATION,
    INVALID_AUTHORITY,
    PROVIDER_FAILURE,
    MALFORMED_RESPONSE,
    MULTIPLE_TOOL_CALLS,
    UNKNOWN_TOOL
}
