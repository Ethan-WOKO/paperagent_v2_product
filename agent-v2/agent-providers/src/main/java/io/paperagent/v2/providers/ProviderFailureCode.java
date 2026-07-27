package io.paperagent.v2.providers;

/**
 * Stable one-turn provider failure categories.
 */
public enum ProviderFailureCode {
    INVALID_REQUEST,
    UNAVAILABLE,
    RATE_LIMITED,
    TIMEOUT,
    CANCELLED,
    PROTOCOL_VIOLATION,
    SCRIPTED_MISMATCH,
    SCRIPTED_OUT_OF_ORDER,
    SCRIPTED_EXHAUSTED,
    SCRIPTED_UNCONSUMED
}
