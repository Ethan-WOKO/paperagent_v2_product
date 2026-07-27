package com.yanban.api.agent.v2.workspace;

import java.util.Objects;

public final class AuthenticatedProjectVersionSourceBindingException extends RuntimeException {

    private final AuthenticatedProjectVersionSourceBindingCode code;
    private final String path;

    public AuthenticatedProjectVersionSourceBindingException(
            AuthenticatedProjectVersionSourceBindingCode code,
            String path
    ) {
        super(Objects.requireNonNull(code, "code").name() + " at " + requirePath(path));
        this.code = code;
        this.path = path;
    }

    public AuthenticatedProjectVersionSourceBindingCode code() {
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
