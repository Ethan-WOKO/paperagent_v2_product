package com.yanban.api.agent.v2;

import java.util.Objects;

public final class AgentTurnProductContextResolutionException extends RuntimeException {

    private final AgentTurnProductContextResolutionCode code;
    private final String path;

    public AgentTurnProductContextResolutionException(AgentTurnProductContextResolutionCode code, String path) {
        super(Objects.requireNonNull(code, "code").name() + " at " + requirePath(path));
        this.code = code;
        this.path = path;
    }

    public AgentTurnProductContextResolutionCode code() {
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
