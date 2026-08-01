package com.yanban.api.agent;

import org.springframework.stereotype.Component;

@Component
public class AdapterCompletionRepairExecutor implements CompletionRepairExecutor {
    @Override
    public AgentRuntimeResult repair(RuntimeAdapter adapter, AgentRuntimeRequest repairRequest) {
        return adapter.run(repairRequest);
    }
}
