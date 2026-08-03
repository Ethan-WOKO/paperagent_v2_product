package com.yanban.api.agent.v2.context.runtime;

public final class V2PlannerContextBoundaryException extends RuntimeException {
    private final String code;

    public V2PlannerContextBoundaryException(String code) {
        super("planner context boundary failed");
        this.code = code;
    }

    public String code() { return code; }
}
