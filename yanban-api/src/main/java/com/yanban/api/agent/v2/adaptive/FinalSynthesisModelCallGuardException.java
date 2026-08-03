package com.yanban.api.agent.v2.adaptive;

/** Safe final-synthesis context failure classification. */
public final class FinalSynthesisModelCallGuardException
        extends RuntimeException {
    private final String code;

    public FinalSynthesisModelCallGuardException(String code) {
        super("final synthesis context is unavailable");
        this.code = code;
    }

    public String code() { return code; }
}
