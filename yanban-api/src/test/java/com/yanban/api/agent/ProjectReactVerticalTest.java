package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileResponse;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.tool.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectReactVerticalTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String PROJECT_VERSION = "b".repeat(64);
    private static final String FILE_HASH = "a".repeat(64);

    @Test
    void coordinatorUsesTrustedProjectContextAndRealProviderReadReturnsUntrustedProvenance() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, PROJECT_VERSION, List.of()));
        when(projects.readFile(7L, 42L, "src/Main.java"))
                .thenReturn(new ProjectFileResponse("src/Main.java", "class Main {}", 13, Instant.EPOCH, FILE_HASH));
        ToolRegistry registry = new ToolRegistry().register(new ProjectReadFileToolExecutor(projects, json));
        LangChain4jChatModelAdapter model = mock(LangChain4jChatModelAdapter.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("read-1").name("project_read_file").arguments("{\"projectId\":42,\"relativePath\":\"src/Main.java\"}").build()))).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Observed the file.")).build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(model,
                new LangChain4jToolProvider(registry, json, new AgentLangChain4jTools(registry, json)), json);
        RuntimeAdapter adapter = new RuntimeAdapter() {
            public boolean supports(AgentStrategy value) { return value == AgentStrategy.PLAN_EXECUTE; }
            public AgentRuntimeResult run(AgentRuntimeRequest request) { return strategy.run(request); }
        };
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(new AgentRuntimeService(List.of(adapter)), new AgentStrategySelector());
        AgentRuntimeRequest request = request().withProjectContext(new ProjectRuntimeContext(7L, 42L));

        AgentCoordinationResult coordination = coordinator.coordinate(AgentCoordinationRequest.projectRead(request));
        AgentRuntimeResult result = coordination.runtimeResult();

        assertThat(coordination.decision().strategySelection().selectedStrategy())
                .isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(result.success()).isTrue();
        String toolContent = result.messages().stream().filter(m -> "tool".equals(m.role())).map(ChatMessage::content).findFirst().orElse("");
        assertThat(toolContent)
                .contains("\"projectVersion\":\"" + PROJECT_VERSION + "\"", "\"relativePath\":\"src/Main.java\"",
                        "\"hash\":\"" + FILE_HASH + "\"", "\"trust\":\"UNTRUSTED\"");
        verify(projects, times(1)).manifest(7L, 42L);
    }

    @Test
    void modelCannotSwitchProjectIdDuringActualReactToolCall() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, PROJECT_VERSION, List.of()));
        when(projects.readFile(7L, 42L, "x.txt"))
                .thenReturn(new ProjectFileResponse("x.txt", "trusted project", 15, Instant.EPOCH, FILE_HASH));
        ToolRegistry registry = new ToolRegistry().register(new ProjectReadFileToolExecutor(projects, json));
        LangChain4jChatModelAdapter model = mock(LangChain4jChatModelAdapter.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("read-2").name("project_read_file").arguments("{\"projectId\":99,\"relativePath\":\"x.txt\"}").build()))).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Limited.")).build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(model,
                new LangChain4jToolProvider(registry, json, new AgentLangChain4jTools(registry, json)), json);
        AgentRuntimeResult result = strategy.run(request().withProjectContext(new ProjectRuntimeContext(7L, 42L)).withStrategy(AgentStrategy.SINGLE_STEP_REACT));
        assertThat(result.toolTrace().get(0)).contains("success=true");
        verify(projects, times(1)).manifest(7L, 42L);
        org.mockito.Mockito.verify(projects).readFile(7L, 42L, "x.txt");
        org.mockito.Mockito.verify(projects, org.mockito.Mockito.never()).manifest(7L, 99L);
    }

    private AgentRuntimeRequest request() {
        return new AgentRuntimeRequest(null, 1L, List.of(), 7L, "read", "test", "model", null, null, 2, true,
                null, null, null, null, AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"), null, null, "trace", null, null);
    }
}
