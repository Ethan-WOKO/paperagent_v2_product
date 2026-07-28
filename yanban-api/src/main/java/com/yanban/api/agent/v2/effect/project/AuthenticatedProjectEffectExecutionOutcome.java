package com.yanban.api.agent.v2.effect.project;

import io.paperagent.v2.persistence.PersistedEffectResult;

public record AuthenticatedProjectEffectExecutionOutcome(
        PersistedEffectResult result,
        boolean replayed) {
}
