package com.yanban.api.agent;

/** Executes the one permitted repair turn; implementations may not alter the request authority. */
public interface CompletionRepairExecutor {
    AgentRuntimeResult repair(RuntimeAdapter adapter, AgentRuntimeRequest repairRequest);
}
