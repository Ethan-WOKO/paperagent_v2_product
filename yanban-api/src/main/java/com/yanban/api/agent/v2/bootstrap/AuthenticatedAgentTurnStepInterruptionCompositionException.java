package com.yanban.api.agent.v2.bootstrap;

import java.util.Objects;

/** Sanitized product-boundary failure for authenticated interruption composition. */
public final class AuthenticatedAgentTurnStepInterruptionCompositionException
        extends IllegalStateException {
    private final AuthenticatedAgentTurnStepInterruptionCompositionCode code;
    private final String path;

    public AuthenticatedAgentTurnStepInterruptionCompositionException(
            AuthenticatedAgentTurnStepInterruptionCompositionCode code,
            String path) {
        this(code, path, null);
    }

    public AuthenticatedAgentTurnStepInterruptionCompositionException(
            AuthenticatedAgentTurnStepInterruptionCompositionCode code,
            String path,
            Throwable cause) {
        super("authenticated Agent-turn Step interruption failed: code="
                        + Objects.requireNonNull(code, "code")
                        + ", path=" + requirePath(path),
                sanitized(cause));
        this.code = code;
        this.path = path;
    }

    public AuthenticatedAgentTurnStepInterruptionCompositionCode code() {
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

    private static Throwable sanitized(Throwable cause) {
        if (cause == null) {
            return null;
        }
        return new SanitizedCollaboratorException(cause.getClass().getName());
    }

    private static final class SanitizedCollaboratorException
            extends RuntimeException {
        private SanitizedCollaboratorException(String originalType) {
            super("collaborator exception details redacted [type="
                    + originalType + "]", null, false, false);
        }
    }
}
