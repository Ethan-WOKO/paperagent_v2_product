package io.paperagent.v2.chain.route;

import java.util.Objects;

public final class ChainRouteException extends RuntimeException {
    public enum Code {
        PROPOSAL_NOT_ACCEPTED,
        PROPOSAL_PAYLOAD_MISMATCH,
        ROUTE_REPLAY_MISMATCH,
        ROUTE_MONOTONICITY_VIOLATION,
        DIRECT_BOUNDARY_VIOLATION,
        FORMAL_FACTS_INCONSISTENT,
        PLAN_SOURCE_INVALID,
        PLAN_COMMIT_MISMATCH
    }

    private final Code code;

    public ChainRouteException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
