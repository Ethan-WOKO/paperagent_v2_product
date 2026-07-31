package com.yanban.api.agent.sandbox;

/** Sanitized failure at one V2 sandbox authority boundary. */
public final class V2SandboxEffectExecutionException
        extends IllegalStateException {
    private final String stage;

    V2SandboxEffectExecutionException(String stage) {
        super("V2 sandbox effect execution failed");
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
