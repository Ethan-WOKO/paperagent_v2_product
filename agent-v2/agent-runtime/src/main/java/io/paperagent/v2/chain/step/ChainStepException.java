package io.paperagent.v2.chain.step;

/** Fail-closed Step authority or state derivation error. */
public final class ChainStepException extends RuntimeException {
    private final String code;

    public ChainStepException(String code, String message) {
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
