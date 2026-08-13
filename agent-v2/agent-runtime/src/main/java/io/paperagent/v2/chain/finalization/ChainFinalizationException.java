package io.paperagent.v2.chain.finalization;

import java.util.Objects;

public final class ChainFinalizationException extends RuntimeException {
    public enum Code {
        READINESS_NOT_FOUND,
        AUTHORITY_PREFIX_INVALID,
        CHECK_REPLAY_MISMATCH,
        PUBLISH_RESULT_INVALID,
        TASK_OUTCOME_INVALID
    }

    private final Code code;

    public ChainFinalizationException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
