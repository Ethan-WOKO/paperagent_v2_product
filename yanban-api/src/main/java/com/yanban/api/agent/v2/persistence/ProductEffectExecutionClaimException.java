package com.yanban.api.agent.v2.persistence;

public final class ProductEffectExecutionClaimException
        extends RuntimeException {
    private final String path;
    private final Long timingDeltaMillis;

    public ProductEffectExecutionClaimException(String path) {
        this(path, null);
    }

    public ProductEffectExecutionClaimException(
            String path, Long timingDeltaMillis) {
        super("Governed effect execution failed closed at " + path);
        this.path = path;
        this.timingDeltaMillis = timingDeltaMillis;
    }

    public String path() {
        return path;
    }

    public Long timingDeltaMillis() {
        return timingDeltaMillis;
    }
}
