package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;

import java.util.Objects;
import java.util.Optional;

/**
 * Caller-supplied content and execution authority for one fresh-start attempt.
 *
 * <p>Authenticated product identity is deliberately absent and is resolved by
 * the composer.
 */
public record AuthenticatedAgentTurnFreshExecutionStartCommand(
        ProductPersistentPlanBootstrapCommand bootstrapCommand,
        Optional<FreshExecutionStartAttempt> attempt) {

    public AuthenticatedAgentTurnFreshExecutionStartCommand {
        Objects.requireNonNull(bootstrapCommand, "bootstrapCommand");
        attempt = Objects.requireNonNull(attempt, "attempt");
    }
}
