package com.yanban.api.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentRuntimeMode;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentStrategy;
import com.yanban.api.agent.AgentToolCallingMode;
import com.yanban.api.agent.LangChain4jToolProvider;
import com.yanban.api.agent.ProjectRuntimeContext;
import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.worker.WorkerBudget;
import com.yanban.core.agent.worker.WorkerMaterialAssignment;
import com.yanban.core.agent.worker.WorkerMaterialType;
import com.yanban.core.agent.worker.WorkerServerAuthority;
import com.yanban.core.agent.worker.WorkerTaskAttestation;
import com.yanban.core.agent.worker.WorkerTaskAttestor;
import com.yanban.core.agent.worker.WorkerTaskPacket;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchRuntimeScope;
import com.yanban.core.research.ResearchToolContracts;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControlledWorkerToolProviderIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void providerRecordsOnlyExecutorReachedCallsAndRejectsAnOutOfScopePath() {
        ToolRegistry registry = new ToolRegistry().register(new ReadOnlyLatexTool(mapper));
        LangChain4jToolProvider provider = new LangChain4jToolProvider(registry, mapper, null);
        AgentRuntimeRequest runtimeRequest = runtimeRequest();
        var executor = provider.provideTools(runtimeRequest)
                .toolExecutorByName(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        WorkerTaskAttestation task = task();

        try (ControlledWorkerExecutionScope scope = ControlledWorkerExecutionScope.open(task)) {
            String accepted = executor.execute(toolRequest("call-1", "paper/main.tex"), 7L);
            String rejected = executor.execute(toolRequest("call-2", "src/Other.java"), 7L);
            ControlledWorkerExecutionScope.Snapshot snapshot = scope.snapshot();

            assertThat(accepted).contains("COMPLETE", "paper/main.tex");
            assertThat(rejected).contains("success\":false", "outside its assignment");
            assertThat(snapshot.executions()).singleElement().satisfies(execution -> {
                assertThat(execution.toolName()).isEqualTo(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
                assertThat(execution.requestedPaths())
                        .containsExactly(ProjectRelativePath.of("paper/main.tex"));
                assertThat(execution.success()).isTrue();
            });
            assertThat(snapshot.rejection()).contains("outside its assignment");
        }
    }

    private ToolExecutionRequest toolRequest(String id, String path) {
        return ToolExecutionRequest.builder().id(id)
                .name(ResearchToolContracts.PROJECT_LATEX_OUTLINE)
                .arguments("{\"relativePaths\":[\"" + path + "\"],\"includeFormulaReferences\":true}")
                .build();
    }

    private AgentRuntimeRequest runtimeRequest() {
        return new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 9L, List.of(), 7L,
                "Analyze assigned paper.", "test", "model", 0.0, 512, 2, true,
                null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                        1, 1, "controlled-worker-test"), 1, 1, "trace-worker-provider", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 21L));
    }

    private WorkerTaskAttestation task() {
        ProjectVersionRef version = new ProjectVersionRef("a".repeat(64));
        WorkerBudget budget = new WorkerBudget(1, 1, 1, 16, 10_000, 10_000);
        AgentRunIdentity identity = new AgentRunIdentity("TEST", "run", 7L, 9L, 21L);
        ResearchRuntimeScope scope = new ResearchRuntimeScope(21L, 7L,
                Set.of(WorkerServerAuthority.REQUIRED_READ_CAPABILITY), version);
        WorkerServerAuthority authority = WorkerServerAuthority.serverResolved(identity, scope,
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), budget);
        WorkerTaskPacket packet = new WorkerTaskPacket("worker-paper", identity.runId(), version,
                List.of(new WorkerMaterialAssignment(ProjectRelativePath.of("paper/main.tex"),
                        WorkerMaterialType.PAPER)), "Analyze assigned paper.",
                List.of("Inspect assigned material"),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                List.of(ControlledWorkerDispatchPlanner.FINDING_KEY), budget, List.of());
        return WorkerTaskAttestor.attestServerResolved(authority, packet);
    }

    private static final class ReadOnlyLatexTool implements ToolExecutor {
        private final ToolDefinition definition;
        private final ObjectMapper mapper;

        private ReadOnlyLatexTool(ObjectMapper mapper) {
            this.mapper = mapper;
            ObjectNode parameters = mapper.createObjectNode().put("type", "object");
            parameters.putObject("properties").putObject("relativePaths")
                    .put("type", "array").putObject("items").put("type", "string");
            parameters.putArray("required").add("relativePaths");
            this.definition = new ToolDefinition(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                    "read-only LaTeX outline", parameters);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(definition.name(), "test@1", "project-research",
                    List.of(ToolDescriptor.CapabilityProfile.PROJECT), List.of(),
                    List.of(ToolDescriptor.ResourceScope.PROJECT), ToolDescriptor.SideEffectType.READ_ONLY,
                    ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = mapper.createObjectNode().put("status", "COMPLETE");
            output.set("relativePaths", call.arguments().path("relativePaths"));
            output.putArray("evidenceRefs");
            return ToolResult.success(call.id(), call.name(), output);
        }
    }
}
