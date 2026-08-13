package io.paperagent.v2.chain.model;

public final class ChainModelProtocolException extends IllegalStateException {
    public enum Code {
        CONTEXT_NOT_FOUND,
        CONTEXT_NOT_COMPLETE,
        CONTEXT_IDENTITY_MISMATCH,
        CONTEXT_REQUEST_DIGEST_MISMATCH,
        INVOCATION_REPLAY_MISMATCH,
        PROPOSAL_REPLAY_MISMATCH,
        ATTEMPT_PREFIX_INVALID,
        SOURCE_REF_NOT_VISIBLE,
        CONTENT_REPLAY_MISMATCH
    }

    private final Code code;

    public ChainModelProtocolException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
