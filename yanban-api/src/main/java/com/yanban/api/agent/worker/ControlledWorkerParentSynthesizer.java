package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentRuntimeResult;

interface ControlledWorkerParentSynthesizer {
    AgentRuntimeResult synthesize(AgentRuntimeRequest request);
}
