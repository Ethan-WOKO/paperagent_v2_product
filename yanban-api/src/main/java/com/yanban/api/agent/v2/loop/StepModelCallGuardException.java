package com.yanban.api.agent.v2.loop;

/** Safe marker that turns a context-gate failure into recovery pending. */
public final class StepModelCallGuardException extends RuntimeException {
    private final String code;

    public StepModelCallGuardException(String code) {
        super("step model call context is unavailable");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
