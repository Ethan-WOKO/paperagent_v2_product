package com.yanban.api.agent.v2.execution;

/** Sanitized product composition failure with no provider or authority payload. */
public final class AuthenticatedAgentTurnStepTurnCompositionException
        extends RuntimeException {
    private final AuthenticatedAgentTurnStepTurnCompositionCode code;
    private final String path;

    public AuthenticatedAgentTurnStepTurnCompositionException(
            AuthenticatedAgentTurnStepTurnCompositionCode code,
            String path) {
        super("authenticated Step turn failed at " + path + " (" + code + ")");
        if (code == null || path == null || path.isBlank()) {
            throw new IllegalArgumentException("failure metadata is required");
        }
        this.code = code;
        this.path = path;
    }

    public AuthenticatedAgentTurnStepTurnCompositionCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepTurnCompositionException[code="
                + code + ", path=" + path + "]";
    }
}
