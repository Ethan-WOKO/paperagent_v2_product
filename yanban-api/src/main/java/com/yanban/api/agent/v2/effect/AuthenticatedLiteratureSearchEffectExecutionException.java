package com.yanban.api.agent.v2.effect;

public final class AuthenticatedLiteratureSearchEffectExecutionException
        extends RuntimeException {
    private final String path;

    public AuthenticatedLiteratureSearchEffectExecutionException(String path) {
        super("Authenticated literature effect failed closed at " + path);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
