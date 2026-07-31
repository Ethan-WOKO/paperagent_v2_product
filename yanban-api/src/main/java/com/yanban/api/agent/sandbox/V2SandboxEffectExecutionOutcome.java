package com.yanban.api.agent.sandbox;

import io.paperagent.v2.persistence.PersistedEffectResult;

public record V2SandboxEffectExecutionOutcome(
        PersistedEffectResult result,
        boolean replayed) {
}
