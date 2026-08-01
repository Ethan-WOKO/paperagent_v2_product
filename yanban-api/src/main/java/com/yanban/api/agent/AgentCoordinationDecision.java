package com.yanban.api.agent;

import java.util.Objects;

/** Immutable audit decision made before any runtime adapter is entered. */
public record AgentCoordinationDecision(
        AgentStrategy selectedStrategy,
        boolean explicitPlanRequest,
        boolean degraded,
        AgentStrategy degradedFrom,
        String reason,
        AgentStrategySelection strategySelection
) {
    public AgentCoordinationDecision(AgentStrategy selectedStrategy,
                                     boolean explicitPlanRequest,
                                     boolean degraded,
                                     AgentStrategy degradedFrom,
                                     String reason) {
        this(selectedStrategy, explicitPlanRequest, degraded, degradedFrom, reason, null);
    }

    public AgentCoordinationDecision {
        Objects.requireNonNull(selectedStrategy, "selectedStrategy must not be null");
        reason = reason == null || reason.isBlank() ? "selected" : reason;
        if (strategySelection != null && (strategySelection.selectedStrategy() != selectedStrategy
                || strategySelection.degraded() != degraded
                || strategySelection.degradedFrom() != degradedFrom)) {
            throw new IllegalArgumentException("coordination decision must match strategy selection audit");
        }
    }
}
