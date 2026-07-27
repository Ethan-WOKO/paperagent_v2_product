package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;

import java.util.Objects;
import java.util.Optional;

/**
 * Caller-owned execution authority for one authenticated recovery attempt.
 */
public record AuthenticatedAgentTurnExecutionStartRecoveryCommand(
        Optional<FreshExecutionStartAttempt> attempt) {

    public AuthenticatedAgentTurnExecutionStartRecoveryCommand {
        attempt = Objects.requireNonNull(attempt, "attempt");
    }
}
