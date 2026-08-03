package com.yanban.api.agent.v2.context.runtime;

@FunctionalInterface
public interface V2ProviderCallGuard {
    void requireReady(V2ContextBoundaryPrepared prepared);
}
