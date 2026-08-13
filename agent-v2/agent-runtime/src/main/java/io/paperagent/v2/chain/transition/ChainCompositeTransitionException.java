package io.paperagent.v2.chain.transition;

/** Fail-closed composite-transition identity or recovery error. */
public final class ChainCompositeTransitionException
        extends RuntimeException {
    private final String code;

    public ChainCompositeTransitionException(
            String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
