package com.yanban.api.agent.v2.persistence;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
final class ProductActiveStepReplanSystemTimeSource
        implements ProductActiveStepReplanTimeSource {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
