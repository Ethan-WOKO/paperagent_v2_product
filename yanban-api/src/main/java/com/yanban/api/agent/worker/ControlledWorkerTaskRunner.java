package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentRuntimeRequest;

interface ControlledWorkerTaskRunner {
    ControlledWorkerTaskRun run(AgentRuntimeRequest parent, ControlledWorkerDispatch.Task task);
}
