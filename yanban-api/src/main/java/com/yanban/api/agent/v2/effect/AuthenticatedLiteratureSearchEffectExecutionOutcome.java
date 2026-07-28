package com.yanban.api.agent.v2.effect;

import io.paperagent.v2.persistence.PersistedEffectResult;

import java.util.Objects;

public record AuthenticatedLiteratureSearchEffectExecutionOutcome(
        PersistedEffectResult result,
        boolean replayed) {
    public AuthenticatedLiteratureSearchEffectExecutionOutcome {
        Objects.requireNonNull(result, "result");
    }
}
