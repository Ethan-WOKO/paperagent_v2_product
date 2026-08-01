package com.yanban.api.agent;

/** Server-owned provenance for an audited strategy decision. */
public enum AgentStrategySelectionOrigin {
    SERVER_AUTO,
    LLM_ROUTER,
    ROUTER_FALLBACK,
    EXPLICIT_OVERRIDE,
    EXPLICIT_FALLBACK,
    TRUSTED_CAPABILITY,
    UNSPECIFIED
}
