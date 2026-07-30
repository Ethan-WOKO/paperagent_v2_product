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
        Map<PlanStepId, ToolId> frozen = Map.copyOf(bindings);
        var selector = (com.yanban.agent.v2.adapter.provider.ProductStepToolSelector)
                input -> {
                    ToolId selected = frozen.get(input.activeStep().id());
                    return selected == null
                            ? List.of()
                            : List.of(descriptor(selected));
                };
        var turn = new DeterministicProductStepTurnAdapter(
                provider, selector,
                new com.yanban.agent.v2.adapter.provider
                        .ProductStepTurnConfiguration(2048, 0.2d));
        return new DefaultSingleTurnStepKernel(turn, intents);
    }

    private static ToolDescriptor descriptor(ToolId id) {
        if (!Set.of("literature.search", "project.read", "project.search",
                "project.candidate.compose").contains(id.value())) {
            throw new IllegalArgumentException(
                    "NATURAL_LANGUAGE_CAPABILITY_UNAVAILABLE");
        }
        return new ToolDescriptor(id,
                "Execute the exact frozen V2 capability for this Step.",
                Set.of());
    }
}
