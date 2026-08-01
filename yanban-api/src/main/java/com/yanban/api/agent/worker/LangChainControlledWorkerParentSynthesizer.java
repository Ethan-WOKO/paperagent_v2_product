package com.yanban.api.agent.worker;

import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentRuntimeResult;
import com.yanban.api.agent.LangChain4jToolCallingStrategy;
import org.springframework.stereotype.Component;

@Component
class LangChainControlledWorkerParentSynthesizer implements ControlledWorkerParentSynthesizer {

    private final LangChain4jToolCallingStrategy toolCallingStrategy;

    LangChainControlledWorkerParentSynthesizer(LangChain4jToolCallingStrategy toolCallingStrategy) {
        this.toolCallingStrategy = toolCallingStrategy;
    }

    @Override
    public AgentRuntimeResult synthesize(AgentRuntimeRequest request) {
        return toolCallingStrategy.run(request);
    }
}
