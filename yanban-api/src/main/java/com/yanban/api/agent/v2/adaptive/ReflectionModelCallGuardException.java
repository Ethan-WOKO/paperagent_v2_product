package com.yanban.api.agent.v2.adaptive;

/** Marks a recoverable context boundary failure before Reflection provider IO. */
public final class ReflectionModelCallGuardException extends RuntimeException {
    private final String code;

    public ReflectionModelCallGuardException(String code) {
        super("reflection model context is unavailable");
        this.code = code;
    }

    public String code() { return code; }
}
