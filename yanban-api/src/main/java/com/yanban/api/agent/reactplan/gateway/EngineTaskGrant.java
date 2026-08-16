package com.yanban.api.agent.reactplan.gateway;

import java.time.Instant;

public record EngineTaskGrant(String value, Instant expiresAt) {
    public EngineTaskGrant {
        if (value == null || value.length() < 32 || value.length() > 4096 || expiresAt == null) {
            throw new IllegalArgumentException("engine task grant is invalid");
        }
    }
}
