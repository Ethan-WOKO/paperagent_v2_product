package com.yanban.api.agent.v2.persistence;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
class SystemProductLeaseTimeSource implements ProductLeaseTimeSource {
    @Override
    public Instant observe() {
        return Instant.now();
    }
}
