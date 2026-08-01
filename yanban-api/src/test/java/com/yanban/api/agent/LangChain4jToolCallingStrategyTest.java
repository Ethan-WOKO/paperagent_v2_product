package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class LangChain4jToolCallingStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void suppressesDeepSeekDsmlProtocolFromUserFacingAssistantContent() {
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                mock(LangChain4jChatModelAdapter.class), mock(LangChain4jToolProvider.class), objectMapper);

        String sanitized = strategy.safeAssistantContent(
                "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"project_read_file\">");

        assertThat(sanitized).doesNotContainIgnoringCase("DSML", "project_read_file")
                .contains("No Candidate was created", "no Project files were changed");
        assertThat(strategy.safeAssistantContent("正常的 Project 回答")).isEqualTo("正常的 Project 回答");
    }

    @Test
    void executesAllowedToolAndReturnsFinalAnswer() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("search_web")
                                .arguments("{\"query\":\"latest radar paper\"}")
                                .build())))
                        .tokenUsage(new TokenUsage(10, 5, 15))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Here is the answer with sources."))
                        .tokenUsage(new TokenUsage(6, 7, 13))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(request(List.of("search_web"), 2, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Here is the answer with sources.");
        assertThat(result.toolTrace()).hasSize(1);
        assertThat(result.toolTrace().get(0)).contains("tool=search_web");
        assertThat(result.totalTokens()).isEqualTo(28);
    }

    @Test
    void firstSuccessfulProjectCandidateProposalForcesToolsDisabledFinalSummary() {
        ToolRegistry registry = new ToolRegistry().register(
                new StubToolExecutor(ProjectCandidateProposalToolExecutor.TOOL_NAME, objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("candidate-1", ProjectCandidateProposalToolExecutor.TOOL_NAME,
                        "{\"query\":\"Runner.java\"}"))
                .thenReturn(answer("Candidate Runner.java is validated and remains NOT_APPLIED."));
        AgentRuntimeRequest request = request(
                List.of(ProjectCandidateProposalToolExecutor.TOOL_NAME), 12, 1)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(registry), objectMapper).run(request);

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).contains("Runner.java", "NOT_APPLIED");
        assertThat(result.toolTrace()).hasSize(1);
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(2)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        assertThat(requests.getAllValues().get(0).parameters().toolSpecifications()).hasSize(1);
        assertThat(requests.getAllValues().get(1).parameters().toolSpecifications()).isEmpty();
    }

    @Test
    void truncatedFinalAnswerGetsOneToolsDisabledCompactRewrite() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Incomplete final answer"))
                        .finishReason(FinishReason.LENGTH).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Compact complete final answer."))
                        .finishReason(FinishReason.STOP).build());

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(new ToolRegistry()), objectMapper)
                .run(request(List.of(), 0, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Compact complete final answer.");
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.NONE);
        verify(chatModel, org.mockito.Mockito.times(2)).chat(any(ChatRequest.class), any(AgentRuntimeRequest.class));
    }

    @Test
    void structurallyTruncatedFinalAnswerIsRewrittenEvenWhenProviderReportsStop() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Evidence-backed stages:\nif snr_db is"))
                        .finishReason(FinishReason.STOP)
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Compact evidence-backed stage summary completed."))
                        .finishReason(FinishReason.STOP)
                        .build());

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(new ToolRegistry()), objectMapper)
                .run(request(List.of(), 0, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Compact evidence-backed stage summary completed.");
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.NONE);
        assertThat(result.fallbacks()).anyMatch(value -> value.contains("Model output truncated"));
        verify(chatModel, org.mockito.Mockito.times(2)).chat(any(ChatRequest.class), any(AgentRuntimeRequest.class));
    }

    @Test
    void projectRuntimeUsesItsLastRoundForSynthesisWithoutClaimingBudgetExhaustion() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "search_web", "{\"query\":\"radar\"}"))
                .thenReturn(toolCall("call-2", "search_web", "{\"query\":\"waveform\"}"))
                .thenReturn(answer("Complete synthesis from reserved round."));
        AgentRuntimeRequest request = request(List.of("search_web"), 3, 1)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request);

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Complete synthesis from reserved round.");
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.NONE);
    }

    @Test
    void lastReasoningRoundToolCallStillGetsOneToolsDisabledSynthesis() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(toolRequest("call-1", "search_web", "{\"query\":\"radar\"}")))
                .thenReturn(toolCalls(toolRequest("call-2", "search_web", "{\"query\":\"radar waveform\"}")))
                .thenReturn(toolCalls(toolRequest("call-3", "search_web", "{\"query\":\"radar constraint\"}")))
                .thenReturn(answer("Final synthesis from the completed tool result."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED);
        assertThat(result.assistantContent()).isEqualTo("Final synthesis from the completed tool result.");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(4)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        assertThat(requests.getAllValues().get(3).parameters().toolSpecifications()).isEmpty();
    }

    @Test
    void finalSynthesisTransportTimeoutPreservesToolEvidenceAsControlledPartial() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(toolRequest("call-1", "search_web", "{\"query\":\"radar\"}")))
                .thenReturn(toolCalls(toolRequest("call-2", "search_web", "{\"query\":\"constraint\"}")))
                .thenThrow(new IllegalStateException("Timeout on blocking read for 60000000000 NANOSECONDS"));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 1, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.assistantContent())
                .contains("only partially complete")
                .contains("Successful tool observations: 1");
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.fallbacks()).anyMatch(value -> value.contains("Final synthesis unavailable"));
    }

    @Test
    void projectRequestReceivesReadSearchEvidenceInstruction() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(answer("bounded answer"));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(new ToolRegistry()), objectMapper);
        AgentRuntimeRequest projectRequest = request(List.of(), 0, 0)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        strategy.run(projectRequest);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture(), any(AgentRuntimeRequest.class));
        List<String> systemPrompts = requestCaptor.getValue().messages().stream()
                .filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage)
                .map(message -> ((dev.langchain4j.data.message.SystemMessage) message).text())
                .toList();
        assertThat(systemPrompts.stream().map(prompt -> prompt.replaceAll("\\s+", " ")).toList())
                .anySatisfy(prompt -> assertThat(prompt)
                        .contains("project_manifest", "project_read_file", "project_search", "specialized Project research tool")
                        .contains("array-valued relativePaths")
                        .contains("derive it from the matching typed items")
                        .contains("equals the entries you enumerate")
                        .contains("omit the numeric total"));
    }

    @Test
    void explicitMissingProjectFileStopsBeforeAlternativeSearchAndUsesBoundedSynthesis() {
        ToolRegistry registry = new ToolRegistry()
                .register(new MissingProjectReadStubToolExecutor(objectMapper))
                .register(new StubToolExecutor("project_search", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        String missingPath = "good_code/s2/__worker10_11_missing_boundary_test__.py";
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("missing-read", "project_read_file",
                        "{\"relativePath\":\"" + missingPath + "\"}"))
                .thenReturn(answer("The requested code file is missing, so code findings and comparison are unavailable."));
        AgentRuntimeRequest projectRequest = request(
                "Read " + missingPath + " and compare it with the paper.",
                List.of("project_read_file", "project_search"), 12, 1)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(projectRequest);

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).singleElement()
                .satisfies(trace -> assertThat(trace)
                        .contains("tool=project_read_file", "success=false", missingPath));
        assertThat(result.fallbacks()).contains(ProjectMaterialScope.MISSING_TARGET_PREFIX + " " + missingPath);
        assertThat(result.assistantContent()).contains("missing").doesNotContain("alternative implementation");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(2)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        assertThat(requests.getAllValues().get(1).parameters().toolSpecifications()).isEmpty();
        assertThat(requests.getAllValues().get(1).messages().stream()
                .filter(dev.langchain4j.data.message.SystemMessage.class::isInstance)
                .map(dev.langchain4j.data.message.SystemMessage.class::cast)
                .map(dev.langchain4j.data.message.SystemMessage::text)
                .toList()).anySatisfy(prompt -> assertThat(prompt)
                .contains("do not search for, read, or use alternative files"));
    }

    @Test
    void preservesArrayParametersWhenConvertingRegistryToolSchema() {
        ToolRegistry registry = new ToolRegistry().register(new ArrayStubToolExecutor(objectMapper));

        var specification = toolProvider(registry)
                .provideTools(request(List.of("array_tool"), 1, 1))
                .tools().keySet().stream().findFirst().orElseThrow();

        assertThat(specification.parameters().properties().get("relativePaths"))
                .isInstanceOfSatisfying(JsonArraySchema.class, array ->
                        assertThat(array.items()).isInstanceOf(JsonStringSchema.class));
    }

    @Test
    void projectRequestReceivesCoherentMarkdownFormattingInstruction() {
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(answer("bounded answer"));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel, toolProvider(new ToolRegistry()), objectMapper);
        AgentRuntimeRequest projectRequest = request(List.of(), 0, 0)
                .withProjectContext(new ProjectRuntimeContext(8L, 42L));

        strategy.run(projectRequest);

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture(), any(AgentRuntimeRequest.class));
        String projectPrompt = requestCaptor.getValue().messages().stream()
                .filter(message -> message instanceof dev.langchain4j.data.message.SystemMessage)
                .map(message -> ((dev.langchain4j.data.message.SystemMessage) message).text())
                .filter(prompt -> prompt.contains("authenticated read-only Project"))
                .findFirst()
                .orElseThrow();
        assertThat(projectPrompt.replaceAll("\\s+", " "))
                .contains("one coherent final answer")
                .contains("short standalone phrase")
                .contains("space after the heading marker")
                .contains("Never format a complete sentence or paragraph as a heading")
                .contains("hyphen followed by a space");
    }

    @Test
    void allDuplicateStepReusesResultAndTerminatesWithEvidenceBasedAnswer() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("search_web")
                                .arguments("{\"query\":\"latest radar paper\"}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-2")
                                .name("search_web")
                                .arguments("{\"query\":\"latest radar paper\"}")
                                .build())))
                        .build())
                .thenReturn(answer("Answer from the first search result."));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Answer from the first search result.");
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.toolTrace().get(1)).contains("reused=true", "Duplicate tool call blocked");
        assertThat(result.fallbacks()).anyMatch(value -> value.contains("No new tool progress"));

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(3)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        assertThat(requests.getAllValues().get(2).parameters().toolSpecifications()).isEmpty();
    }

    @Test
    void mixedNewAndDuplicateCallsInOneAssistantStepPreserveNewResultsAndContinue() throws Exception {
        ToolRegistry registry = new ToolRegistry()
                .register(new ProjectResearchStubToolExecutor("project_latex_outline", objectMapper))
                .register(new ProjectResearchStubToolExecutor("project_cross_material_search", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(
                        toolRequest("call-1", "project_latex_outline", "{\"query\":\"paper.tex\"}"),
                        toolRequest("call-2", "project_cross_material_search", "{\"query\":\"objective function\"}"),
                        toolRequest("call-3", "project_cross_material_search", "{\"query\":\"power constraint\"}")))
                .thenReturn(toolCalls(
                        toolRequest("call-4", "project_cross_material_search", "{\"query\":\"objective\"}"),
                        toolRequest("call-5", "project_cross_material_search", "{\"query\":\"power constraint\"}")))
                .thenReturn(answer("Synthesis from the outline and all distinct searches."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("project_latex_outline", "project_cross_material_search"), 6, 1)
                        .withProjectContext(new ProjectRuntimeContext(8L, 42L)));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).contains("all distinct searches");
        assertThat(result.toolTrace()).hasSize(5);
        assertThat(result.toolTrace()).filteredOn(value -> value.contains("reused=true"))
                .singleElement().asString().contains("power constraint", "Duplicate tool call blocked");
        assertThat(result.toolTrace()).filteredOn(value -> !value.contains("reused=true")).hasSize(4);
        assertThat(result.errorMessage()).isNull();

        EvidenceLedger evidence = ResearchProjectEvidenceAdapter.extract(objectMapper, result.messages(), 0,
                new ProjectRuntimeContext(8L, 42L),
                Set.of("project_latex_outline", "project_cross_material_search"));
        assertThat(evidence.evidence()).hasSize(4);
        assertThat(evidence.evidence()).extracting(EvidenceRef::chunk)
                .containsExactlyInAnyOrder("tool:call-1", "tool:call-2", "tool:call-3", "tool:call-4");

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(3)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        var reusedMessage = requests.getAllValues().get(2).messages().stream()
                .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
                .filter(message -> "call-5".equals(message.id()))
                .findFirst().orElseThrow();
        var reusedNode = objectMapper.readTree(reusedMessage.text());
        assertThat(reusedNode.path("reused").asBoolean()).isTrue();
        assertThat(reusedNode.path("originalToolCallId").asText()).isEqualTo("call-3");
        assertThat(reusedNode.path("message").asText()).contains("original tool result");
        assertThat(reusedNode.has("result")).isFalse();
    }

    @Test
    void canonicalArgumentsPreserveWhitespaceInsideJsonStrings() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "search_web", "{\"query\":\"a b\"}"))
                .thenReturn(toolCall("call-2", "search_web", "{\"query\":\"ab\"}"))
                .thenReturn(answer("Both searches completed."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).hasSize(2);
    }

    @Test
    void canonicalArgumentsBlockEquivalentJsonWithDifferentKeyOrder() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "search_web", "{\"query\":\"x\",\"topK\":1}"))
                .thenReturn(toolCall("call-2", "search_web", "{\"topK\":1,\"query\":\"x\"}"))
                .thenReturn(answer("Canonical duplicate reused."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).anyMatch(value -> value.contains("reused=true"));
    }

    @Test
    void allowsRepeatedPollingStatusToolCalls() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("literature_search_status", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("literature_search_status")
                                .arguments("{\"taskId\":3}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-2")
                                .name("literature_search_status")
                                .arguments("{\"taskId\":3}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Task is still running."))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(request(List.of("literature_search_status"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Task is still running.");
        assertThat(result.toolTrace()).hasSize(2);
    }

    @Test
    void terminalAsyncStateCannotBePolledAgain() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("terminal_status", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "terminal_status", "{\"taskId\":3}"))
                .thenReturn(toolCall("call-2", "terminal_status", "{\"taskId\":3}"))
                .thenReturn(answer("The task was already terminal."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("terminal_status"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).anyMatch(value -> value.contains("reused=true")
                && value.contains("after terminal state"));
    }

    @Test
    void unknownAsyncStateCannotAuthorizeAnotherPoll() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("unknown_status", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "unknown_status", "{\"taskId\":3}"))
                .thenReturn(toolCall("call-2", "unknown_status", "{\"taskId\":3}"))
                .thenReturn(answer("The earlier unknown status is the only available observation."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("unknown_status"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).anyMatch(value -> value.contains("reused=true")
                && value.contains("no observable non-terminal state"));
    }

    @Test
    void permitsOneBoundedRetryOnlyAfterChangingFailedArguments() {
        ToolRegistry registry = new ToolRegistry().register(new RetriableStubToolExecutor("start_export", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("call-1").name("start_export").arguments("{\"clientRequestId\":\"retry-1\"}").build()))).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                        .id("call-2").name("start_export").arguments("{\"clientRequestId\":\"retry-2\"}").build()))).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Export started.")).build());

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("start_export"), 3, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.toolTrace().get(0)).contains("success=false");
        assertThat(result.toolTrace().get(1)).contains("success=true");
    }

    @Test
    void identicalFailedCallIsNotExecutedAgain() {
        ToolRegistry registry = new ToolRegistry().register(new RetriableStubToolExecutor("start_export", objectMapper, false));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCall("call-1", "start_export", "{\"clientRequestId\":\"retry-1\"}"))
                .thenReturn(toolCall("call-2", "start_export", "{\"clientRequestId\":\"retry-1\"}"))
                .thenReturn(answer("The bounded retry result is final."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("start_export"), 4, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.toolTrace().get(1)).contains("reused=true", "Duplicate failed tool call blocked");
    }

    @Test
    void descriptorVisibilityPreventsPolicyOrOldAliasFromReexposingInternalTool() {
        ToolRegistry registry = new ToolRegistry().register(new InternalStubToolExecutor("literature_search_status", objectMapper));
        LangChain4jToolProvider provider = toolProvider(registry);

        assertThat(provider.provideTools(request(List.of("literature_search_status"), 1, 1)).tools()).isEmpty();
    }

    @Test
    void providerExposesOnlyTheNormalChatResearchPolicy() {
        ToolRegistry registry = new ToolRegistry()
                .register(new InternalStubToolExecutor("search_web", objectMapper))
                .register(new InternalStubToolExecutor("recommend_literature", objectMapper))
                .register(new InternalStubToolExecutor("search_knowledge", objectMapper))
                .register(new InternalStubToolExecutor("paper_task_cancel", objectMapper))
                .register(new InternalStubToolExecutor("literature_search_start", objectMapper));

        assertThat(toolProvider(registry).provideTools(request(
                List.of("search_web", "recommend_literature", "search_knowledge", "paper_task_cancel", "literature_search_start"),
                6, 1)).tools().keySet())
                .extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                .containsExactlyInAnyOrder("search_web", "recommend_literature", "search_knowledge");
    }

    @Test
    void routingPromptDoesNotRecommendToolsOutsideTheCurrentSpecifications() {
        ToolRegistry registry = new ToolRegistry()
                .register(new InternalStubToolExecutor("search_web", objectMapper))
                .register(new InternalStubToolExecutor("paper_task_cancel", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class))).thenReturn(answer("Direct answer."));

        new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web", "paper_task_cancel"), 6, 1));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture(), any(AgentRuntimeRequest.class));
        String systemPrompt = requestCaptor.getValue().messages().stream()
                .filter(dev.langchain4j.data.message.SystemMessage.class::isInstance)
                .map(dev.langchain4j.data.message.SystemMessage.class::cast)
                .map(dev.langchain4j.data.message.SystemMessage::text)
                .reduce("", String::concat);
        assertThat(systemPrompt).doesNotContain("paper_task_cancel", "literature_search_start");
        assertThat(requestCaptor.getValue().parameters().toolSpecifications()).extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                .containsExactly("search_web");
    }

    @Test
    void providerCompatibilityOverloadRequiresResolvedRuntimePolicy() {
        LangChain4jToolProvider provider = toolProvider(new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper)));

        assertThatThrownBy(() -> provider.provideTools(null, java.util.Set.of("search_web")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordsStructuredToolFailureAsFailureRatherThanSuccessfulExecution() {
        ToolRegistry registry = new ToolRegistry().register(new FailingStubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1").name("search_web").arguments("{}").build())))
                        .build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("Tool failed; answer is limited.")).build());

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("search_web"), 2, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).singleElement().satisfies(trace ->
                assertThat(trace).contains("success=false").contains("upstream timed out"));
    }

    @Test
    void runtimeBudgetComesOnlyFromResolvedPolicy() {
        ToolRegistry registry = new ToolRegistry().register(new StubToolExecutor("search_web", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-1")
                                .name("search_web")
                                .arguments("{\"query\":\"polarization FDA-MIMO\"}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call-2")
                                .name("search_web")
                                .arguments("{\"query\":\"polarization diversity radar\"}")
                                .build())))
                        .build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Final answer based on the first search result."))
                        .build());
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );

        AgentRuntimeResult result = strategy.run(requestWithPolicy(
                new ResolvedToolPolicy(List.of("search_web"), 1, 2, "test_policy"), 99, 99));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.assistantContent()).isEqualTo("Final answer based on the first search result.");
        assertThat(result.fallbacks()).contains("Tool-call budget exceeded: maxToolCalls=1");
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.toolTrace().get(1)).contains("success=false error=Tool-call budget exceeded");
    }

    @Test
    void scopeRefinementValidationDoesNotConsumeExecutionBudget() throws Exception {
        ToolRegistry registry = new ToolRegistry().register(new ScopeRefinementStubToolExecutor(objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(toolRequest("call-1", "project_cross_material_search",
                        "{\"query\":\"objective\"}")))
                .thenReturn(toolCalls(
                        toolRequest("call-2", "project_cross_material_search",
                                "{\"query\":\"objective\",\"relativePaths\":[\"paper.tex\"]}"),
                        toolRequest("call-3", "project_cross_material_search",
                                "{\"query\":\"constraint\",\"relativePaths\":[\"code.py\"]}")))
                .thenReturn(answer("Both scoped searches completed."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("project_cross_material_search"), 2, 1));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.NONE);
        assertThat(result.assistantContent()).isEqualTo("Both scoped searches completed.");
        assertThat(result.toolTrace()).hasSize(3);
        assertThat(result.fallbacks()).contains(
                "Tool scope refinement required; execution budget was not consumed: project_cross_material_search");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(3)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        var repairMessage = requests.getAllValues().get(1).messages().stream()
                .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
                .filter(message -> "call-1".equals(message.id()))
                .findFirst().orElseThrow();
        JsonNode repair = objectMapper.readTree(repairMessage.text()).path("repairContext");
        assertThat(repair.path("failedTool").asText()).isEqualTo("project_cross_material_search");
        assertThat(repair.path("arguments").path("query").asText()).isEqualTo("objective");
        assertThat(repair.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(repair.path("errorMessage").asText()).contains("requires relativePaths");
        assertThat(repair.path("retryable").asBoolean()).isTrue();
        assertThat(repair.path("remainingAttempts").asInt()).isEqualTo(1);
    }

    @Test
    void batchLargerThanRemainingBudgetExecutesOnlyRemainingCallsAndSynthesizesFromEvidence() throws Exception {
        ToolRegistry registry = new ToolRegistry().register(
                new ProjectResearchStubToolExecutor("project_cross_material_search", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(
                        toolRequest("call-1", "project_cross_material_search", "{\"query\":\"q1\"}"),
                        toolRequest("call-2", "project_cross_material_search", "{\"query\":\"q2\"}"),
                        toolRequest("call-3", "project_cross_material_search", "{\"query\":\"q3\"}"),
                        toolRequest("call-4", "project_cross_material_search", "{\"query\":\"q4\"}")))
                .thenReturn(toolCalls(
                        toolRequest("call-5", "project_cross_material_search", "{\"query\":\"q5\"}"),
                        toolRequest("call-6", "project_cross_material_search", "{\"query\":\"q6\"}"),
                        toolRequest("call-7", "project_cross_material_search", "{\"query\":\"q7\"}")))
                .thenReturn(answer("Partial synthesis from the six completed observations; the seventh search was not executed."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("project_cross_material_search"), 6, 1)
                        .withProjectContext(new ProjectRuntimeContext(8L, 42L)));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.toolTrace()).hasSize(7);
        assertThat(result.toolTrace()).filteredOn(value -> value.contains("success=true")).hasSize(6);
        assertThat(result.toolTrace()).filteredOn(value -> value.contains("success=false"))
                .singleElement().asString().contains("q7", "Tool-call budget exceeded");

        EvidenceLedger evidence = ResearchProjectEvidenceAdapter.extract(objectMapper, result.messages(), 0,
                new ProjectRuntimeContext(8L, 42L), Set.of("project_cross_material_search"));
        assertThat(evidence.evidence()).hasSize(6);
        assertThat(evidence.evidence()).extracting(EvidenceRef::chunk)
                .containsExactlyInAnyOrder("tool:call-1", "tool:call-2", "tool:call-3",
                        "tool:call-4", "tool:call-5", "tool:call-6");

        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, org.mockito.Mockito.times(3)).chat(requests.capture(), any(AgentRuntimeRequest.class));
        ChatRequest synthesis = requests.getAllValues().get(2);
        assertThat(synthesis.parameters().toolSpecifications()).isEmpty();
        var skipped = synthesis.messages().stream()
                .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
                .filter(message -> "call-7".equals(message.id()))
                .findFirst().orElseThrow();
        JsonNode skippedResult = objectMapper.readTree(skipped.text());
        assertThat(skippedResult.path("executed").asBoolean()).isFalse();
        assertThat(skippedResult.path("skipped").asBoolean()).isTrue();
        assertThat(skippedResult.path("controlledStop").asBoolean()).isTrue();
        assertThat(skippedResult.path("errorCode").asText()).isEqualTo("TOOL_CALL_BUDGET_EXHAUSTED");
        assertThat(skippedResult.path("outcome").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void productionProjectBudgetExtendsAfterUniqueSuccessfulProgress() {
        ToolRegistry registry = new ToolRegistry().register(
                new ProjectResearchStubToolExecutor("project_cross_material_search", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(
                        toolRequest("p1", "project_cross_material_search", "{\"query\":\"q1\"}"),
                        toolRequest("p2", "project_cross_material_search", "{\"query\":\"q2\"}"),
                        toolRequest("p3", "project_cross_material_search", "{\"query\":\"q3\"}"),
                        toolRequest("p4", "project_cross_material_search", "{\"query\":\"q4\"}"),
                        toolRequest("p5", "project_cross_material_search", "{\"query\":\"q5\"}"),
                        toolRequest("p6", "project_cross_material_search", "{\"query\":\"q6\"}"),
                        toolRequest("p7", "project_cross_material_search", "{\"query\":\"q7\"}"),
                        toolRequest("p8", "project_cross_material_search", "{\"query\":\"q8\"}"),
                        toolRequest("p9", "project_cross_material_search", "{\"query\":\"q9\"}"),
                        toolRequest("p10", "project_cross_material_search", "{\"query\":\"q10\"}"),
                        toolRequest("p11", "project_cross_material_search", "{\"query\":\"q11\"}"),
                        toolRequest("p12", "project_cross_material_search", "{\"query\":\"q12\"}"),
                        toolRequest("p13", "project_cross_material_search", "{\"query\":\"q13\"}")))
                .thenReturn(answer("All thirteen observations synthesized."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("project_cross_material_search"), 12, 1)
                        .withProjectContext(new ProjectRuntimeContext(8L, 42L)));

        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).hasSize(13);
        assertThat(result.fallbacks()).contains("Project tool budget extended after verified progress: 12->16");
    }

    @Test
    void productionProjectBudgetDoesNotExtendAfterUniqueEmptyResults() {
        ToolRegistry registry = new ToolRegistry().register(
                new EmptyProjectResearchStubToolExecutor("project_cross_material_search", objectMapper));
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.chat(any(ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(toolCalls(
                        toolRequest("e1", "project_cross_material_search", "{\"query\":\"q1\"}"),
                        toolRequest("e2", "project_cross_material_search", "{\"query\":\"q2\"}"),
                        toolRequest("e3", "project_cross_material_search", "{\"query\":\"q3\"}"),
                        toolRequest("e4", "project_cross_material_search", "{\"query\":\"q4\"}"),
                        toolRequest("e5", "project_cross_material_search", "{\"query\":\"q5\"}"),
                        toolRequest("e6", "project_cross_material_search", "{\"query\":\"q6\"}"),
                        toolRequest("e7", "project_cross_material_search", "{\"query\":\"q7\"}"),
                        toolRequest("e8", "project_cross_material_search", "{\"query\":\"q8\"}"),
                        toolRequest("e9", "project_cross_material_search", "{\"query\":\"q9\"}"),
                        toolRequest("e10", "project_cross_material_search", "{\"query\":\"q10\"}"),
                        toolRequest("e11", "project_cross_material_search", "{\"query\":\"q11\"}"),
                        toolRequest("e12", "project_cross_material_search", "{\"query\":\"q12\"}"),
                        toolRequest("e13", "project_cross_material_search", "{\"query\":\"q13\"}")))
                .thenReturn(answer("The twelve completed searches returned no matches; the thirteenth was not executed."));

        AgentRuntimeResult result = new LangChain4jToolCallingStrategy(chatModel, toolProvider(registry), objectMapper)
                .run(request(List.of("project_cross_material_search"), 12, 1)
                        .withProjectContext(new ProjectRuntimeContext(8L, 42L)));

        assertThat(result.success()).isTrue();
        assertThat(result.runtimeStopSignal()).isEqualTo(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.toolTrace()).hasSize(13);
        assertThat(result.toolTrace()).filteredOn(value -> value.contains("success=true")).hasSize(12);
        assertThat(result.toolTrace().get(12)).contains("success=false", "Tool-call budget exceeded");
        assertThat(result.fallbacks()).doesNotContain("Project tool budget extended after verified progress: 12->16");
    }

    @Test
    void annotatedToolReceivesUserIdAsToolMemoryId() {
        ToolRegistry registry = new ToolRegistry().register(new UserAwareStubToolExecutor("search_knowledge", objectMapper));
        LangChain4jToolProvider provider = toolProvider(registry);

        String content = provider
                .provideTools(request(List.of("search_knowledge"), 2, 1), java.util.Set.of("search_knowledge"))
                .toolExecutorByName("search_knowledge")
                .execute(ToolExecutionRequest.builder()
                        .id("call-user")
                        .name("search_knowledge")
                        .arguments("{\"query\":\"project name\"}")
                        .build(), 8L);

        assertThat(content).contains("\"userId\":8");
    }

    @Test
    void streamsFinalAnswerWhenTokenConsumerIsPresent() {
        ToolRegistry registry = new ToolRegistry();
        LangChain4jChatModelAdapter chatModel = mock(LangChain4jChatModelAdapter.class);
        when(chatModel.stream(any(dev.langchain4j.model.chat.request.ChatRequest.class), any(AgentRuntimeRequest.class)))
                .thenReturn(Flux.just(
                        ChatChunk.token("Hello"),
                        ChatChunk.token(" world"),
                        ChatChunk.done("stop")
                ));
        LangChain4jToolCallingStrategy strategy = new LangChain4jToolCallingStrategy(
                chatModel,
                toolProvider(registry),
                objectMapper
        );
        List<String> tokens = new ArrayList<>();

        AgentRuntimeResult result = strategy.run(request(List.of(), 0, 1, tokens::add));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Hello world");
        assertThat(tokens).containsExactly("Hello", " world");
    }

    private AgentRuntimeRequest request(List<String> allowedTools, Integer maxToolCalls, Integer maxDuplicateToolCalls) {
        return request(allowedTools, maxToolCalls, maxDuplicateToolCalls, null);
    }

    private AgentRuntimeRequest request(String userMessage, List<String> allowedTools,
                                        Integer maxToolCalls, Integer maxDuplicateToolCalls) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT, 4L, List.of(), 8L, userMessage, "deepseek", "deepseek-v4-flash",
                null, null, 3, false, null, null, null, null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                allowedTools, maxToolCalls, maxDuplicateToolCalls, "trace-tool", null, null);
    }

    private AgentRuntimeRequest request(List<String> allowedTools,
                                        Integer maxToolCalls,
                                        Integer maxDuplicateToolCalls,
                                        java.util.function.Consumer<String> tokenConsumer) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                4L,
                List.of(),
                8L,
                "help me search",
                "deepseek",
                "deepseek-v4-flash",
                null,
                null,
                3,
                false,
                null,
                null,
                null,
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                allowedTools,
                maxToolCalls,
                maxDuplicateToolCalls,
                "trace-tool",
                tokenConsumer,
                null
        );
    }

    private AgentRuntimeRequest requestWithPolicy(ResolvedToolPolicy toolPolicy,
                                                  Integer legacyMaxToolCalls,
                                                  Integer legacyMaxDuplicateToolCalls) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT,
                4L,
                List.of(),
                8L,
                "help me search",
                "deepseek",
                "deepseek-v4-flash",
                null,
                null,
                3,
                false,
                null,
                null,
                null,
                null,
                AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                toolPolicy,
                legacyMaxToolCalls,
                legacyMaxDuplicateToolCalls,
                "trace-tool",
                null,
                null
        );
    }

    private LangChain4jToolProvider toolProvider(ToolRegistry registry) {
        return new LangChain4jToolProvider(
                registry,
                objectMapper,
                new AgentLangChain4jTools(registry, objectMapper)
        );
    }

    private ChatResponse toolCall(String id, String name, String arguments) {
        return toolCalls(toolRequest(id, name, arguments));
    }

    private ChatResponse toolCalls(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private ToolExecutionRequest toolRequest(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    private ChatResponse answer(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).build();
    }

    private static final class StubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private StubToolExecutor(String name, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties")
                    .putObject("query")
                    .put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition(name, "stub tool", parameters);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            boolean polling = definition.name().endsWith("_status");
            return new ToolDescriptor(definition.name(), "v-test", "test", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                    List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.SideEffectType.NONE,
                    ToolDescriptor.ConfirmationPolicy.NEVER,
                    polling ? ToolDescriptor.AsyncMode.EXTERNAL_TASK : ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.NONE,
                    polling ? ToolDescriptor.RepeatPolicy.POLL_UNTIL_TERMINAL : ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT,
                    true);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("query", call.arguments().path("query").asText());
            output.put("source", "stub");
            if (definition.name().endsWith("_status")) {
                output.put("status", definition.name().startsWith("terminal_") ? "DONE"
                        : definition.name().startsWith("unknown_") ? "MYSTERY" : "RUNNING");
            }
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static final class MissingProjectReadStubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;

        private MissingProjectReadStubToolExecutor(ObjectMapper objectMapper) {
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties").putObject("relativePath").put("type", "string");
            parameters.putArray("required").add("relativePath");
            this.definition = new ToolDefinition("project_read_file", "read Project file", parameters);
        }

        @Override public ToolDefinition definition() { return definition; }

        @Override public ToolDescriptor descriptor() { return visibleSyncDescriptor(definition.name()); }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.failure(call.id(), call.name(), com.yanban.core.tool.ToolErrorCode.NOT_FOUND,
                    "Project file not found");
        }
    }

    private static final class ArrayStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;

        private ArrayStubToolExecutor(ObjectMapper objectMapper) {
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties").putObject("relativePaths")
                    .put("type", "array").putObject("items").put("type", "string");
            parameters.putArray("required").add("relativePaths");
            this.definition = new ToolDefinition("array_tool", "array schema tool", parameters);
        }

        @Override public ToolDefinition definition() { return definition; }
        @Override public ToolDescriptor descriptor() { return visibleSyncDescriptor(definition.name()); }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), call.name(), call.arguments());
        }
    }

    private static final class ProjectResearchStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private ProjectResearchStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties").putObject("query").put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition(name, "project research stub", parameters);
        }

        @Override public ToolDefinition definition() { return definition; }
        @Override public ToolDescriptor descriptor() { return visibleSyncDescriptor(definition.name()); }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("status", "COMPLETE");
            output.putArray("items");
            ObjectNode evidence = output.putArray("evidenceRefs").addObject();
            String query = call.arguments().path("query").asText();
            int line = query.matches("q\\d+") ? Integer.parseInt(query.substring(1)) : 1;
            evidence.put("projectVersion", "a".repeat(64));
            evidence.put("relativePath", definition.name().equals("project_latex_outline") ? "paper.tex" : "notes.txt");
            evidence.put("fileHash", "b".repeat(64));
            evidence.putObject("range").put("startLine", line).put("endLine", line);
            evidence.put("parserVersion", "test@1");
            evidence.put("trustLabel", "SERVER_ATTESTED_METADATA");
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static final class EmptyProjectResearchStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private EmptyProjectResearchStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties").putObject("query").put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition(name, "empty project research stub", parameters);
        }

        @Override public ToolDefinition definition() { return definition; }
        @Override public ToolDescriptor descriptor() { return visibleSyncDescriptor(definition.name()); }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("status", "EMPTY");
            output.putArray("items");
            output.putArray("evidenceRefs");
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static final class ScopeRefinementStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private ScopeRefinementStubToolExecutor(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            ObjectNode properties = parameters.putObject("properties");
            properties.putObject("query").put("type", "string");
            properties.putObject("relativePaths").put("type", "array").putObject("items").put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition("project_cross_material_search", "scope refinement stub", parameters);
        }

        @Override public ToolDefinition definition() { return definition; }
        @Override public ToolDescriptor descriptor() { return visibleSyncDescriptor(definition.name()); }

        @Override
        public ToolResult execute(ToolCall call) {
            if (!call.arguments().path("relativePaths").isArray()) {
                return ToolResult.failure(call.id(), call.name(), com.yanban.core.tool.ToolErrorCode.VALIDATION_ERROR,
                        "Large Project requires relativePaths before execution");
            }
            return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode().put("status", "COMPLETE"));
        }
    }

    private static final class FailingStubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;

        private FailingStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.definition = new ToolDefinition(name, "failing " + name,
                    objectMapper.createObjectNode().put("type", "object"));
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return visibleSyncDescriptor(definition.name());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.failure(call.id(), call.name(), com.yanban.core.tool.ToolErrorCode.TIMEOUT,
                    "upstream timed out");
        }
    }

    private static final class UserAwareStubToolExecutor implements ToolExecutor {

        private final ToolDefinition definition;
        private final ObjectMapper objectMapper;

        private UserAwareStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("type", "object");
            parameters.putObject("properties")
                    .putObject("query")
                    .put("type", "string");
            parameters.putArray("required").add("query");
            this.definition = new ToolDefinition(name, "user aware stub tool", parameters);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return visibleSyncDescriptor(definition.name());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            ObjectNode output = objectMapper.createObjectNode();
            output.put("query", call.arguments().path("query").asText());
            output.put("userId", ToolExecutionContext.getCurrentUserId());
            return ToolResult.success(call.id(), call.name(), output);
        }
    }

    private static final class RetriableStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;
        private int calls;
        private final boolean succeedOnSecondCall;

        private RetriableStubToolExecutor(String name, ObjectMapper objectMapper) {
            this(name, objectMapper, true);
        }

        private RetriableStubToolExecutor(String name, ObjectMapper objectMapper, boolean succeedOnSecondCall) {
            this.definition = new ToolDefinition(name, "retriable " + name,
                    objectMapper.createObjectNode().put("type", "object"));
            this.succeedOnSecondCall = succeedOnSecondCall;
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(definition.name(), "v-test", "export", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                    List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.SideEffectType.CREATE,
                    ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                    ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY, ToolDescriptor.RepeatPolicy.ALLOW_LIMITED, true);
        }

        @Override
        public ToolResult execute(ToolCall call) {
            calls++;
            return calls == 1 || !succeedOnSecondCall
                    ? ToolResult.failure(call.id(), call.name(), com.yanban.core.tool.ToolErrorCode.TIMEOUT, "temporary timeout")
                    : ToolResult.success(call.id(), call.name(), new ObjectMapper().createObjectNode().put("started", true));
        }
    }

    private static final class InternalStubToolExecutor implements ToolExecutor {
        private final ToolDefinition definition;

        private InternalStubToolExecutor(String name, ObjectMapper objectMapper) {
            this.definition = new ToolDefinition(name, "internal " + name,
                    objectMapper.createObjectNode().put("type", "object"));
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(call.id(), call.name(), new ObjectMapper().createObjectNode());
        }
    }

    private static ToolDescriptor visibleSyncDescriptor(String name) {
        return new ToolDescriptor(name, "v-test", "test", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), ToolDescriptor.SideEffectType.NONE,
                ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
    }
}
