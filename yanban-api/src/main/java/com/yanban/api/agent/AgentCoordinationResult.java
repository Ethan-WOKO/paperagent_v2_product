package com.yanban.api.agent;

import com.yanban.core.agent.AgentTaskWorkspace;
import java.util.Objects;

/** Structured outcome of a synchronous Coordinator invocation. */
public record AgentCoordinationResult(
        AgentCoordinationDecision decision,
        AgentRuntimeResult runtimeResult,
        AgentRunProjection runProjection,
        AgentTaskWorkspace taskWorkspace
) {
    public AgentCoordinationResult(AgentCoordinationDecision decision, AgentRuntimeResult runtimeResult,
                                   AgentRunProjection runProjection) {
        this(decision, runtimeResult, runProjection, null);
    }

    public AgentCoordinationResult {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(runtimeResult, "runtimeResult must not be null");
        Objects.requireNonNull(runProjection, "runProjection must not be null");
    }
}
