package com.yanban.api.agent;

/**
 * Trust boundary of a request entering the Runtime Coordinator.  User-authored
 * text is always CHAT; only server-side API adapters may create PLAN requests.
 */
public enum AgentRequestCapability {
    CHAT,
    /** Project identity and READ_ONLY capability were attached by an authenticated server adapter. */
    PROJECT_READ,
    TRUSTED_PROJECT_PLAN_READ,
    TRUSTED_PLAN_API,
    LEGACY_PLAN_REFLECT
}
