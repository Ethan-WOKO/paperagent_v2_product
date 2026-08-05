package com.yanban.api.agent.v2.loop;

import com.yanban.agent.v2.adapter.provider.DeterministicProductStepTurnAdapter;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
import com.yanban.api.agent.v2.context.V2ExecutionContextSource;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.kernel.DefaultSingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NaturalLanguageStepKernelFactory {
    private final ModelProvider provider;
    private final EffectIntentRepository intents;
    private final V2EffectHistorySource history;
    private final V2ExecutionContextSource contextSource;

    public NaturalLanguageStepKernelFactory(
            ModelProvider provider, EffectIntentRepository intents) {
        this(provider, intents, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NaturalLanguageStepKernelFactory(
            ModelProvider provider, EffectIntentRepository intents,
            V2EffectHistorySource history,
            V2ExecutionContextSource contextSource) {
        this.provider = provider;
        this.intents = intents;
        this.history = history;
        this.contextSource = contextSource;
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

    public AutonomousKernel createAutonomous(
            ModelProvider requestProvider,
            boolean replayLatestForReplan) {
        return createAutonomous(
                requestProvider, replayLatestForReplan, List.of());
    }

    public AutonomousKernel createAutonomous(
            ModelProvider requestProvider,
            boolean replayLatestForReplan,
            List<String> suppliedContextFacts) {
        if (history == null) {
            throw new IllegalStateException(
                    "V2 effect history is unavailable");
        }
        var turn = new AutonomousNaturalLanguageStepTurnAdapter(
                requestProvider, history, contextSource, autonomousTools(),
                replayLatestForReplan, suppliedContextFacts);
        return new AutonomousKernel(
                new DefaultSingleTurnStepKernel(turn, intents), turn);
    }

    public AutonomousKernel createAutonomous(
            boolean replayLatestForReplan) {
        return createAutonomous(provider, replayLatestForReplan);
    }

    public AutonomousKernel createAutonomous(
            boolean replayLatestForReplan,
            List<String> suppliedContextFacts) {
        return createAutonomous(
                provider, replayLatestForReplan, suppliedContextFacts);
    }

    private static List<ToolDescriptor> autonomousTools() {
        return V2ProductToolCatalog.descriptors();
    }

    public V2ExecutionContextSource contextSource() {
        return contextSource;
    }

    static ToolDescriptor descriptor(ToolId id) {
        return V2ProductToolCatalog.requireDescriptor(id);
    }

    public record AutonomousKernel(
            SingleTurnStepKernel kernel,
            AutonomousNaturalLanguageStepTurnAdapter turn) {
        public List<String> diagnostics() {
            return turn.diagnostics();
        }
    }
}
