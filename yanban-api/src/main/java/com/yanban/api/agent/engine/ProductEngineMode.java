package com.yanban.api.agent.engine;

import java.util.Locale;

public enum ProductEngineMode {
    LEGACY,
    DSH,
    CODEX;

    static ProductEngineMode parse(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("unsupported product Engine mode");
        }
    }

    boolean external() {
        return this != LEGACY;
    }
}
