package com.yanban.api.agent.v2.effect.project;

/** Sanitized failure at one Project effect authority boundary. */
public final class ProjectEffectExecutionException
        extends IllegalStateException {
    private final String stage;

    ProjectEffectExecutionException(String stage) {
        super("V2 Project evidence execution failed");
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
