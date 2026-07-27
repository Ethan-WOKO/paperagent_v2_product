package com.yanban.api.agent.v2.workspace;

import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextLeaseAttempt;

import java.util.Objects;
import java.util.Optional;

/**
 * Caller-owned lease authority for one authenticated context composition.
 */
public record AuthenticatedAgentTurnPlanExecutionContextCommand(
        Optional<PlanExecutionContextLeaseAttempt> attempt) {

    public AuthenticatedAgentTurnPlanExecutionContextCommand {
        attempt = Objects.requireNonNull(attempt, "attempt");
    }
}
