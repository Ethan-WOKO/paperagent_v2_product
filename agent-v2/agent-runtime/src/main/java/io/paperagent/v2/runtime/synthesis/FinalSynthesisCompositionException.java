package io.paperagent.v2.runtime.synthesis;

/** Bounded failure without collaborator payloads or credentials. */
public final class FinalSynthesisCompositionException extends RuntimeException {
    private final String stage;

    public FinalSynthesisCompositionException(String stage) {
        super("final synthesis composition failed at " + stage);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
