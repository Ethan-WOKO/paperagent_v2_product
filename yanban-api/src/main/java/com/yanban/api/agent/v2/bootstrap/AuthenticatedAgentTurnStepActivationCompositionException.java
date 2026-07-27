package com.yanban.api.agent.v2.bootstrap;

import java.util.Objects;

public final class AuthenticatedAgentTurnStepActivationCompositionException
        extends RuntimeException {
    private final AuthenticatedAgentTurnStepActivationCompositionCode code;
    private final String path;

    public AuthenticatedAgentTurnStepActivationCompositionException(
            AuthenticatedAgentTurnStepActivationCompositionCode code,
            String path) {
        this(code, path, null);
    }

    public AuthenticatedAgentTurnStepActivationCompositionException(
            AuthenticatedAgentTurnStepActivationCompositionCode code,
            String path,
            Throwable cause) {
        super("authenticated Agent-turn Step activation failed: code="
                + Objects.requireNonNull(code, "code")
                + ", path="
                + requirePath(path), cause);
        this.code = code;
        this.path = requirePath(path);
    }

    public AuthenticatedAgentTurnStepActivationCompositionCode code() {
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
