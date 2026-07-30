package com.yanban.api.agent.v2.loop;

import com.yanban.agent.v2.adapter.provider.DeterministicProductStepTurnAdapter;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.kernel.DefaultSingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NaturalLanguageStepKernelFactory {
    private final ModelProvider provider;
    private final EffectIntentRepository intents;

    public NaturalLanguageStepKernelFactory(
            ModelProvider provider, EffectIntentRepository intents) {
        this.provider = provider;
        this.intents = intents;
    }

    public SingleTurnStepKernel create(Map<PlanStepId, ToolId> bindings) {
        return create(provider, bindings);
    }

    public SingleTurnStepKernel create(
            ModelProvider requestProvider,
            Map<PlanStepId, ToolId> bindings) {
        Map<PlanStepId, ToolId> frozen = Map.copyOf(bindings);
        var selector = (com.yanban.agent.v2.adapter.provider.ProductStepToolSelector)
                input -> {
                    ToolId selected = frozen.get(input.activeStep().id());
                    return selected == null
                            ? List.of()
                            : List.of(descriptor(selected));
                };
        var turn = new DeterministicProductStepTurnAdapter(
                requestProvider, selector,
                new com.yanban.agent.v2.adapter.provider
                        .ProductStepTurnConfiguration(2048, 0.2d));
        return new DefaultSingleTurnStepKernel(turn, intents);
    }

    static ToolDescriptor descriptor(ToolId id) {
        if (!Set.of("literature.search", "project.read", "project.search",
                "project.candidate.compose").contains(id.value())) {
            throw new IllegalArgumentException(
                    "NATURAL_LANGUAGE_CAPABILITY_UNAVAILABLE");
        }
        String description = switch (id.value()) {
            case "project.read" ->
                    "Read one UTF-8 Project file. Arguments must be exactly "
                            + "{\"path\":\"normalized/existing/path\"}.";
            case "project.search" ->
                    "Search all Project text files for one literal string. "
                            + "Arguments must be exactly "
                            + "{\"query\":\"literal text up to 256 chars\","
                            + "\"maxResults\":10}; maxResults is 1-20.";
            case "project.candidate.compose" ->
                    "Prepare reviewed Project changes in the isolated "
                            + "Workspace. Arguments must be exactly "
                            + "{\"operation\":\"compose\",\"paths\":["
                            + "\"normalized/existing/path\"]}; "
                            + "include 1-4 paths.";
            default ->
                    "Execute the exact frozen V2 capability for this Step.";
        };
        return new ToolDescriptor(id, description, Set.of());
    }
}
