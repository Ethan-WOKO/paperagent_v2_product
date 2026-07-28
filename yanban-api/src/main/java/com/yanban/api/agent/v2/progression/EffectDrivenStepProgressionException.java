package com.yanban.api.agent.v2.progression;

/** Sanitized fail-closed product composition failure. */
public final class EffectDrivenStepProgressionException
        extends IllegalStateException {
    private final String path;

    EffectDrivenStepProgressionException(String path) {
        super("effect-driven Step progression rejected");
        this.path = path;
    }

    public String path() {
        return path;
    }
}
