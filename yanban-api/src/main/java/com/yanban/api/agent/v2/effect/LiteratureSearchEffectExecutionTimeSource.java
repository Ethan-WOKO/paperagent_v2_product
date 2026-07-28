package com.yanban.api.agent.v2.effect;

import java.time.Instant;

@FunctionalInterface
public interface LiteratureSearchEffectExecutionTimeSource {
    Instant now();
}
