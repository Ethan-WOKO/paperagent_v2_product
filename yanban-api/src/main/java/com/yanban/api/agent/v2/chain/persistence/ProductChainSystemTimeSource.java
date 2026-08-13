package com.yanban.api.agent.v2.chain.persistence;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
final class ProductChainSystemTimeSource implements ProductChainTimeSource {
    @Override
    public Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
