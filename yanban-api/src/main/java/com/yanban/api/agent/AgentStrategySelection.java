package com.yanban.api.agent;

import java.util.List;
import java.util.Objects;

/** Complete server-side strategy decision without free-form chain-of-thought. */
public record AgentStrategySelection(
        AgentStrategy requestedStrategy,
        AgentStrategy selectedStrategy,
        boolean explicitOverride,
        boolean degraded,
        AgentStrategy degradedFrom,
        List<AgentStrategy> serverCandidates,
        AgentOrchestrationRequirements orchestration,
        String reason
) {
    public AgentStrategySelection {
        requestedStrategy = requestedStrategy == null ? AgentStrategy.AUTO : requestedStrategy;
        Objects.requireNonNull(selectedStrategy, "selectedStrategy must not be null");
        serverCandidates = serverCandidates == null ? List.of() : List.copyOf(serverCandidates);
        orchestration = orchestration == null ? AgentOrchestrationRequirements.empty() : orchestration;
        reason = reason == null || reason.isBlank() ? "selected" : reason;
        if (selectedStrategy == AgentStrategy.AUTO || !serverCandidates.contains(selectedStrategy)) {
            throw new IllegalArgumentException("selected strategy must be an executable server candidate");
        }
        if (!degraded && degradedFrom != null) {
            throw new IllegalArgumentException("degradedFrom requires degraded=true");
        }
    }
}
