package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.PersistedEffectResult;

import java.util.Objects;

public record ProductEffectExecutionClaimResult(
        PersistedEffectResult result,
        boolean replayed) {
    public ProductEffectExecutionClaimResult {
        Objects.requireNonNull(result, "result");
    }
}
