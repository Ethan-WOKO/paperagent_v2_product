package com.yanban.api.agent.v2.persistence;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
class ProductEffectOutcomeSystemTimeSource
        implements ProductEffectOutcomeTimeSource {
    private final Clock clock = Clock.systemUTC();

    @Override
    public Instant observe() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
