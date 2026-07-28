package com.yanban.api.agent.v2.persistence;

public final class ProductEffectExecutionClaimException
        extends RuntimeException {
    private final String path;

    public ProductEffectExecutionClaimException(String path) {
        super("Governed effect execution failed closed at " + path);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
