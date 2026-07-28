package com.yanban.api.agent.v2.effect;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class SystemLiteratureSearchEffectExecutionTimeSource
        implements LiteratureSearchEffectExecutionTimeSource {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
