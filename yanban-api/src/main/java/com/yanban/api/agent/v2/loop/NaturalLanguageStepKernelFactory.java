package com.yanban.api.agent.v2.loop;

import com.yanban.agent.v2.adapter.provider.DeterministicProductStepTurnAdapter;
import com.yanban.api.agent.v2.persistence.V2EffectHistorySource;
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
    private final V2EffectHistorySource history;

    public NaturalLanguageStepKernelFactory(
            ModelProvider provider, EffectIntentRepository intents) {
        this(provider, intents, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NaturalLanguageStepKernelFactory(
            ModelProvider provider, EffectIntentRepository intents,
            V2EffectHistorySource history) {
        this.provider = provider;
        this.intents = intents;
        this.history = history;
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
        if (history == null) {
            throw new IllegalStateException(
                    "V2 effect history is unavailable");
        }
        var turn = new AutonomousNaturalLanguageStepTurnAdapter(
                requestProvider, history, autonomousTools(),
                replayLatestForReplan);
        return new AutonomousKernel(
                new DefaultSingleTurnStepKernel(turn, intents), turn);
    }

    public AutonomousKernel createAutonomous(
            boolean replayLatestForReplan) {
        return createAutonomous(provider, replayLatestForReplan);
    }

    private static List<ToolDescriptor> autonomousTools() {
        return List.of(
                descriptor(new ToolId("literature.search")),
                descriptor(new ToolId("project.read")),
                descriptor(new ToolId("project.search")),
                descriptor(new ToolId("project.candidate.compose")),
                descriptor(new ToolId("sandbox.execute")));
    }

    static ToolDescriptor descriptor(ToolId id) {
        if (!Set.of("literature.search", "project.read", "project.search",
                "project.candidate.compose", "sandbox.execute")
                .contains(id.value())) {
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
                            + "Workspace and create the only durable source "
                            + "for a reviewable Candidate. Use this for any "
                            + "Project file creation or modification; "
                            + "sandbox.execute cannot create a Candidate. "
                            + "Arguments must be exactly "
                            + "{\"operation\":\"compose\",\"paths\":["
                            + "\"normalized/existing/path\"]}; "
                            + "include 1-4 paths.";
            case "sandbox.execute" ->
                    "Run Project code in the existing isolated E2B Sandbox. "
                            + "When a prior completed Plan Step created a "
                            + "Candidate, run that resulting isolated "
                            + "Workspace instead of recreating the Candidate. "
                            + "This proves execution only and cannot create "
                            + "or update a Project Candidate. "
                            + "Arguments must be exactly "
                            + "{\"paths\":[\"normalized/path\"],"
                            + "\"argv\":[\"yanban-runner\",\"java\","
                            + "\"normalized/path.java\"]}. Supported argv "
                            + "profiles include yanban-runner java/python/c/"
                            + "cpp, Maven test/verify, java, javac, and "
                            + "bounded git checks. Java runner arguments may "
                            + "append --dependency=group:artifact:version; "
                            + "dependencies are prepared before offline run.";
            default ->
                    "Execute the exact frozen V2 capability for this Step.";
        };
        return new ToolDescriptor(id, description, Set.of());
    }

    public record AutonomousKernel(
            SingleTurnStepKernel kernel,
            AutonomousNaturalLanguageStepTurnAdapter turn) {
        public List<String> diagnostics() {
            return turn.diagnostics();
        }
    }
}
