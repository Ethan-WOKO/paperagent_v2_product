package com.yanban.api.agent.v2.workspace;

import java.util.Objects;

public final class AuthenticatedPlanExecutionContextCompositionException
        extends RuntimeException {

    private final AuthenticatedPlanExecutionContextCompositionCode code;
    private final String path;

    public AuthenticatedPlanExecutionContextCompositionException(
            AuthenticatedPlanExecutionContextCompositionCode code,
            String path) {
        super(Objects.requireNonNull(code, "code").name()
                + " at " + requirePath(path));
        this.code = code;
        this.path = path;
    }

    public AuthenticatedPlanExecutionContextCompositionCode code() {
        return code;
    }

    public String path() {
        return path;
    }

    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return path;
    }
}
