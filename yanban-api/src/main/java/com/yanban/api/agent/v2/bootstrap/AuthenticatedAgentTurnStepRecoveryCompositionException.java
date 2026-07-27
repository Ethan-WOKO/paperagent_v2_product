package com.yanban.api.agent.v2.bootstrap;

import java.util.Objects;

public final class AuthenticatedAgentTurnStepRecoveryCompositionException
        extends RuntimeException {
    private final AuthenticatedAgentTurnStepRecoveryCompositionCode code;
    private final String path;

    public AuthenticatedAgentTurnStepRecoveryCompositionException(
            AuthenticatedAgentTurnStepRecoveryCompositionCode code,
            String path) {
        super("authenticated Agent-turn Step recovery failed: code="
                + Objects.requireNonNull(code, "code")
                + ", path="
                + requirePath(path));
        this.code = code;
        this.path = requirePath(path);
    }

    public AuthenticatedAgentTurnStepRecoveryCompositionCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    private static String requirePath(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return path;
    }
}
