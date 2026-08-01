package com.yanban.api.agent;

public interface RuntimeAdapter {

    default boolean supports(AgentStrategy strategy) {
        return false;
    }

    default boolean supports(AgentRuntimeRequest request) {
        return request != null && supports(request.strategy());
    }

    AgentRuntimeResult run(AgentRuntimeRequest request);
}
