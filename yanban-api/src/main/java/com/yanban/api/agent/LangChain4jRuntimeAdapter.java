package com.yanban.api.agent;

import org.springframework.stereotype.Component;

@Component
public class LangChain4jRuntimeAdapter implements RuntimeAdapter {

    private final LangChain4jToolCallingStrategy toolCallingStrategy;

    public LangChain4jRuntimeAdapter(LangChain4jToolCallingStrategy toolCallingStrategy) {
        this.toolCallingStrategy = toolCallingStrategy;
    }

    @Override
    public boolean supports(AgentStrategy strategy) {
        return strategy == AgentStrategy.DIRECT || strategy == AgentStrategy.SINGLE_STEP_REACT;
    }

    @Override
    public boolean supports(AgentRuntimeRequest request) {
        return request != null && supports(request.strategy());
    }

    @Override
    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        AgentRuntimeResult result = toolCallingStrategy.run(request);
        DomainRuntimeFacts facts = DomainRuntimeFacts.fromTrustedToolTrace(
                result.toolTrace(), request.toolPolicy().allowedTools());
        return facts.toolOutcomes().isEmpty() ? result
                : result.withDomainRuntimeFacts(result.domainRuntimeFacts().merge(facts));
    }
}
