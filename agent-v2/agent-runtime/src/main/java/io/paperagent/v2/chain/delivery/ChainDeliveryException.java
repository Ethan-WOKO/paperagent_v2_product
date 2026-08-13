package io.paperagent.v2.chain.delivery;

import java.util.Objects;

public final class ChainDeliveryException extends RuntimeException {
    public enum Code {
        SOURCE_INVALID,
        PROPOSAL_INVALID,
        CONTENT_AUTHORITY_INVALID,
        DELIVERY_REPLAY_MISMATCH,
        DELIVERY_EVENT_PREFIX_INVALID,
        MESSAGE_ATTEMPT_INVALID
    }

    private final Code code;

    public ChainDeliveryException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
