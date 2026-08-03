package com.yanban.api.agent.v2.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.api.agent.AgentContextBuildRequest;
import com.yanban.api.agent.AgentContextBuilder;
import com.yanban.api.agent.AgentContextDebugView;
import com.yanban.api.agent.AgentContextPackage;
import com.yanban.api.agent.AgentContextSection;
import com.yanban.api.agent.AgentExperimentContext;
import com.yanban.api.agent.AgentExperimentRequest;
import com.yanban.api.agent.AgentExperimentService;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.api.agent.AgentRagExperimentResult;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnPlanBootstrapComposer;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveExecutionService;
import com.yanban.api.agent.v2.adaptive.V2AdaptiveExecutionResult;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.runtime.V2ContextBoundaryPrepared;
import com.yanban.api.agent.v2.context.runtime.V2PlannerContextBoundaryException;
import com.yanban.api.agent.v2.context.runtime.V2PlannerContextBoundaryFactory;
import com.yanban.api.agent.v2.context.runtime.V2PlannerCallMaterial;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.memory.LongTermMemoryRetrievalService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionSummary;
import com.yanban.core.agent.AgentSessionSummaryService;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.providers.*;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class V2NaturalLanguageTurnServiceTest {
    private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    private final V2TurnIntakeTransactions transactions = mock(V2TurnIntakeTransactions.class);
    private final AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
    private final AgentContextBuilder contextBuilder = mock(AgentContextBuilder.class);
    private final AgentSessionSummaryService summaries = mock(AgentSessionSummaryService.class);
    private final LongTermMemoryRetrievalService memories = mock(LongTermMemoryRetrievalService.class);
    private final AgentExperimentService experiments = mock(AgentExperimentService.class);
    private final SkillsService skills = mock(SkillsService.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final AuthenticatedAgentTurnPlanBootstrapComposer bootstraps =
            mock(AuthenticatedAgentTurnPlanBootstrapComposer.class);
    private final ChatModelProvider model = mock(ChatModelProvider.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentSession session = new AgentSession(
            7L, "session", "deepseek", "legacy-pro", 20, false,
            AgentSessionScope.WORKSPACE, null);
    private final V2TurnIntakeEntity intake = new V2TurnIntakeEntity(
            7L, 9L, "request-1", "a".repeat(64), "question",
            false, "skill-1", "{}", 11L, 12L,
            "deepseek", "model", Instant.EPOCH);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(session));
        when(transactions.open(
                eq(7L), eq(9L), eq("request-1"), any(), eq("question"),
                eq(false), eq("skill-1"), any(),
                any(), any()))
                .thenReturn(intake);
        when(transactions.locked(eq(intake), any())).thenAnswer(invocation -> {
            Function<V2TurnIntakeEntity, Object> operation =
                    invocation.getArgument(1);
            synchronized (intake) {
                return operation.apply(intake);
            }
        });
        when(contexts.resolve(7L, 12L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "12", 7L, 9L, null),
                        Optional.empty()));
        when(skills.resolveEnabledSkill(7L, "skill-1"))
                .thenReturn(new ResolvedSkill(
                        "skill-1", "Use concise academic language",
                        java.util.Set.of("project_read")));
        AgentExperimentContext experiment = mock(AgentExperimentContext.class);
        AgentRagExperimentResult rag = mock(AgentRagExperimentResult.class);
        when(rag.ragContext()).thenReturn("relevant RAG evidence");
        when(experiment.ragResult()).thenReturn(rag);
        when(experiments.prepare(eq(7L), eq("question"), any()))
                .thenReturn(experiment);
        UserSettingsService.ModelEndpoint endpoint =
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "model", null, "SECRET-KEY",
                        "builtin", "DeepSeek");
        when(settings.resolveModelEndpoint(7L, null, null))
                .thenReturn(endpoint);
        when(settings.resolveModelEndpoint(7L, "deepseek", "model"))
                .thenReturn(endpoint);
        AgentSessionSummary summary = mock(AgentSessionSummary.class);
        when(summary.getSummaryText()).thenReturn("rolling summary");
        when(summaries.findBySessionAndUser(9L, 7L))
                .thenReturn(Optional.of(summary));
        when(memories.retrieve(7L, "question"))
                .thenReturn(new AgentLongTermMemoryContext(
                        "confirmed memory", 1, 1, 0, "confirmed"));
        when(contextBuilder.build(any())).thenReturn(
                new AgentContextPackage(
                        List.of(
                                ChatMessage.system("context identity"),
                                ChatMessage.user("recent dialogue"),
                                ChatMessage.user("question")),
                        List.of(new AgentContextSection(
                                "recent_messages", 2, 30,
                                "workspace history")),
                        List.of(), 3, 3, 50,
                        com.yanban.api.agent.EvidenceLedger.empty(),
                        ChatMessage.user("question"),
                        null));
    }

    @Test
    void directUsesBoundedProductContextAndPersistsOneAssistant() throws Exception {
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(
                        directAnswer("answer")),
                "stop", null));
        when(transactions.saveAssistant(
                eq(intake), eq("answer"), any())).thenAnswer(invocation -> {
            AgentMessage message = new AgentMessage(
                    9L, 7L, "assistant", "answer", null, null);
            setId(message, 13L);
            intake.completeDirect(
                    13L, invocation.getArgument(2), Instant.now());
            when(transactions.message(13L)).thenReturn(message);
            return message;
        });

        var response = service().execute(
                7L, 9L, request());

        assertEquals("DIRECT", response.route());
        assertEquals("answer", response.answer());
        assertEquals(13L, response.assistantMessageId());
        verify(bootstraps, never()).bootstrap(any(), any(), any());
        verify(transactions).open(
                eq(7L), eq(9L), eq("request-1"), any(), eq("question"),
                eq(false), eq("skill-1"), any(),
                eq("deepseek"), eq("model"));

        ArgumentCaptor<AgentContextBuildRequest> context =
                ArgumentCaptor.forClass(AgentContextBuildRequest.class);
        verify(contextBuilder).build(context.capture());
        assertThat(context.getValue().sessionSummary())
                .isEqualTo("rolling summary");
        assertThat(context.getValue().longTermMemoryContext().content())
                .isEqualTo("confirmed memory");
        assertThat(context.getValue().ragContext())
                .isEqualTo("relevant RAG evidence");
        assertThat(context.getValue().maxRecentMessages()).isEqualTo(12);
        assertThat(context.getValue().maxContextCharacters())
                .isEqualTo(12_000);

        ArgumentCaptor<ChatRequest> provider =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(provider.capture());
        assertThat(provider.getValue().tools()).isEmpty();
        assertThat(provider.getValue().messages())
                .extracting(ChatMessage::content)
                .anyMatch(value -> value.contains(
                        "Use concise academic language"))
                .noneMatch(value -> value.contains("SECRET-KEY"));
        assertThat(provider.getValue().messages().stream()
                .filter(value -> "user".equals(value.role()))
                .filter(value -> "question".equals(value.content()))
                .count()).isEqualTo(1);
    }

    @Test
    void plannerProviderCallRequiresReadyContextFirst() throws Exception {
        V2PlannerContextBoundaryFactory boundaries =
                mock(V2PlannerContextBoundaryFactory.class);
        V2PlannerContextBoundaryFactory.Session boundary =
                mock(V2PlannerContextBoundaryFactory.Session.class);
        V2ContextBoundaryPrepared prepared = mock(V2ContextBoundaryPrepared.class);
        when(boundaries.open(any())).thenReturn(boundary);
        when(boundary.prepare(any(V2PlannerCallMaterial.class)))
                .thenReturn(prepared);
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(directAnswer("ready")), "stop", null));
        when(transactions.saveAssistant(
                eq(intake), eq("ready"), any())).thenAnswer(invocation -> {
            AgentMessage message = new AgentMessage(
                    9L, 7L, "assistant", "ready", null, null);
            setId(message, 13L);
            intake.completeDirect(13L, invocation.getArgument(2), Instant.now());
            when(transactions.message(13L)).thenReturn(message);
            return message;
        });

        service(boundaries).execute(7L, 9L, request());

        InOrder order = org.mockito.Mockito.inOrder(boundary, model);
        order.verify(boundary).prepare(any(V2PlannerCallMaterial.class));
        order.verify(boundary).requireReady(prepared);
        order.verify(model).chat(any());
    }

    @Test
    void plannerContextFailureBlocksProviderAndPlanAuthority() {
        V2PlannerContextBoundaryFactory boundaries =
                mock(V2PlannerContextBoundaryFactory.class);
        V2PlannerContextBoundaryFactory.Session boundary =
                mock(V2PlannerContextBoundaryFactory.Session.class);
        when(boundaries.open(any())).thenReturn(boundary);
        when(boundary.prepare(any(V2PlannerCallMaterial.class))).thenThrow(
                new V2PlannerContextBoundaryException(
                        "PLANNER_CONTEXT_NOT_READY"));

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service(boundaries).execute(7L, 9L, request()));

        verify(model, never()).chat(any());
        verify(bootstraps, never()).bootstrap(any(), any(), any());
        verify(transactions, never()).saveAssistant(any(), any(), any());
        verify(transactions, never()).savePersistent(any(), any(), any(), any());
        verify(transactions).saveFailure(
                intake, "PLANNER_CONTEXT_NOT_READY");
        assertThat(intake.planId()).isNull();
        assertThat(intake.plannerOutputJson()).isNull();
    }

    @Test
    void knownContextProfileOnlyObservesTheAlreadyBuiltPlannerInput()
            throws Exception {
        V2TurnIntakeEntity profiledIntake = new V2TurnIntakeEntity(
                7L, 9L, "request-1", "a".repeat(64), "question",
                false, "skill-1", "{}", 11L, 12L,
                "deepseek", "deepseek-v4-flash", Instant.EPOCH);
        UserSettingsService.ModelEndpoint endpoint =
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null,
                        "SECRET-KEY", "builtin", "DeepSeek");
        when(transactions.open(
                eq(7L), eq(9L), eq("request-1"), any(), eq("question"),
                eq(false), eq("skill-1"), any(), any(), any()))
                .thenReturn(profiledIntake);
        when(transactions.locked(eq(profiledIntake), any()))
                .thenAnswer(invocation -> {
                    Function<V2TurnIntakeEntity, Object> operation =
                            invocation.getArgument(1);
                    return operation.apply(profiledIntake);
                });
        when(settings.resolveModelEndpoint(
                7L, "deepseek", "deepseek-v4-flash"))
                .thenReturn(endpoint);
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(directAnswer("answer")),
                "stop", null));
        when(transactions.saveAssistant(
                eq(profiledIntake), eq("answer"), any()))
                .thenAnswer(invocation -> {
                    AgentMessage message = new AgentMessage(
                            9L, 7L, "assistant", "answer", null, null);
                    setId(message, 13L);
                    profiledIntake.completeDirect(
                            13L, invocation.getArgument(2), Instant.now());
                    when(transactions.message(13L)).thenReturn(message);
                    return message;
                });

        service().execute(7L, 9L, request());

        ArgumentCaptor<ChatRequest> plannerInput =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(plannerInput.capture());
        assertThat(plannerInput.getValue().messages())
                .extracting(ChatMessage::content)
                .contains("context identity", "recent dialogue", "question");
        verify(contextBuilder).build(any());
        verify(transactions).saveAssistant(
                eq(profiledIntake), eq("answer"), any());
    }

    @Test
    void shadowClassificationExcludesUntrustedEnvelopeFromCore() {
        UserSettingsService.ModelEndpoint endpoint =
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null,
                        "unused", "builtin", "DeepSeek");
        AgentContextDebugView debug = new AgentContextDebugView(
                12_000, 12_000, 100,
                new AgentContextDebugView.DebugText(
                        "current request", true, false, "active_request"),
                List.of(new AgentContextDebugView.DebugTurn(
                        1L, 2L, 3L, "recent user", "recent assistant", 27)),
                new AgentContextDebugView.DebugText(
                        "summary", true, false,
                        "agent_session_summaries"),
                null,
                new AgentContextDebugView.DebugMemory(
                        "memory", 1, 0, false,
                        "governed_long_term_memory", "confirmed"),
                List.of(), List.of(), List.of());
        AgentContextPackage assembled = new AgentContextPackage(
                List.of(
                        ChatMessage.system("runtime guard"),
                        ChatMessage.user("untrusted data envelope"),
                        ChatMessage.user("fallback history")),
                List.of(), List.of(), 3, 3, 100,
                com.yanban.api.agent.EvidenceLedger.empty(),
                ChatMessage.user("current request"), debug);

        var measurement = V2NaturalLanguageTurnService
                .shadowContextMeasurement(endpoint, assembled, false)
                .orElseThrow();
        Utf8ByteTokenCounter counter = new Utf8ByteTokenCounter();

        assertThat(measurement.section(ContextSectionType.CORE_AUTHORITY)
                .estimatedTokens()).isEqualTo(
                        counter.count("runtime guard")
                                + counter.count("current request"));
        assertThat(measurement.section(
                ContextSectionType.RECENT_CONVERSATION).estimatedTokens())
                .isEqualTo(counter.count("recent user")
                        + counter.count("recent assistant"));
        assertThat(measurement.section(
                ContextSectionType.CONVERSATION_SUMMARY).estimatedTokens())
                .isEqualTo(counter.count("summary"));
        assertThat(measurement.section(ContextSectionType.LONG_TERM_MEMORY)
                .estimatedTokens()).isEqualTo(counter.count("memory"));
        assertThat(measurement.section(ContextSectionType.RAG_EVIDENCE)
                .estimatedTokens()).isZero();
        assertThat(measurement.section(ContextSectionType.TOOL_RESULTS)
                .estimatedTokens()).isZero();
    }

    @Test
    void nonProjectShadowFallsBackToAssembledRecentMessages() {
        UserSettingsService.ModelEndpoint endpoint =
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null,
                        "unused", "builtin", "DeepSeek");
        AgentContextDebugView debug = new AgentContextDebugView(
                12_000, 12_000, 100,
                new AgentContextDebugView.DebugText(
                        "current", true, false, "active_request"),
                List.of(), null, null, null,
                List.of(), List.of(), List.of());
        AgentContextPackage assembled = new AgentContextPackage(
                List.of(
                        ChatMessage.system("guard"),
                        ChatMessage.user("envelope"),
                        ChatMessage.user("history one"),
                        ChatMessage.assistant("history two")),
                List.of(), List.of(), 4, 4, 100,
                com.yanban.api.agent.EvidenceLedger.empty(),
                ChatMessage.user("current"), debug);

        var measurement = V2NaturalLanguageTurnService
                .shadowContextMeasurement(endpoint, assembled, false)
                .orElseThrow();

        assertThat(measurement.section(
                ContextSectionType.RECENT_CONVERSATION).estimatedTokens())
                .isEqualTo(new Utf8ByteTokenCounter().count("history one")
                        + new Utf8ByteTokenCounter().count("history two"));
    }

    @Test
    void persistentStoresLlmAuthoredFrameAndPlanWithoutExecution() {
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {"route":"PERSISTENT_PLAN_EXECUTE",
                         "requirements":{"projectEvidence":true,
                           "toolUse":true,"retrieval":false,
                           "networkAccess":false,"execution":false,
                           "durableModification":false,
                           "durableProgress":true},
                         "taskFrame":{"objective":"Inspect","targets":["project"],
                           "deliverables":["report"],"constraints":["read only"]},
                         "plan":{"reason":"Need evidence","steps":[{
                           "id":"read-1","intent":"Read source",
                           "expectedOutcome":"Text is available",
                           "dependencies":[],
                           "completionCriteria":["Read receipt exists"],
                           "maxAttempts":1,"maxDurationSeconds":120}]}}
                        """),
                "stop", null));
        PersistedPlanBootstrap persisted = mock(PersistedPlanBootstrap.class);
        Plan plan = mock(Plan.class);
        when(plan.id()).thenReturn(new PlanId("product-plan.test"));
        when(persisted.plan()).thenReturn(plan);
        when(bootstraps.bootstrap(eq(7L), eq(12L), any()))
                .thenReturn(PersistenceResult.applied(persisted));
        org.mockito.Mockito.doAnswer(invocation -> {
            intake.completePersistent(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    Instant.now());
            return null;
        }).when(transactions).savePersistent(
                eq(intake), eq("product-plan.test"), any(), any());

        var response = service().execute(7L, 9L, request());

        assertEquals("PERSISTENT_PLAN_EXECUTE", response.route());
        assertEquals("product-plan.test", response.planId());
        assertThat(response.assistantMessageId()).isNull();
        ArgumentCaptor<ProductPersistentPlanBootstrapCommand> command =
                ArgumentCaptor.forClass(
                        ProductPersistentPlanBootstrapCommand.class);
        verify(bootstraps).bootstrap(eq(7L), eq(12L), command.capture());
        assertThat(command.getValue().taskFrameDraft().objective())
                .isEqualTo("Inspect");
        assertThat(command.getValue().initialPlanDraft().steps())
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.id().value()).isEqualTo("read-1");
                    assertThat(step.intent()).isEqualTo("Read source");
                });
        assertThat(command.getValue().routingDecision().requirements())
                .contains(RoutingRequirement.PROJECT_FILE_ACCESS,
                        RoutingRequirement.TOOL_USE);
        assertThat(command.getValue().executionProfile().capabilities())
                .contains(Capability.READ_PROJECT);
    }

    @Test
    void exactTerminalReplaySkipsPlannerAndBootstrap() {
        intake.completePersistent(
                "product-plan.test", "{}", "[]", Instant.now());

        var response = service().execute(7L, 9L, request());

        assertThat(response.replayed()).isTrue();
        assertThat(response.planId()).isEqualTo("product-plan.test");
        verify(model, never()).chat(any());
        verify(bootstraps, never()).bootstrap(any(), any(), any());
    }

    @Test
    void persistentTurnStartsAdaptiveExecutionAndDeliversFinalMessage() {
        stubPersistentPlanning();
        V2AdaptiveExecutionService adaptive =
                mock(V2AdaptiveExecutionService.class);
        when(adaptive.execute(any())).thenReturn(
                new V2AdaptiveExecutionResult(
                        "SUCCEEDED", List.of(), "最终结论",
                        null, 1, 0, 0));
        when(transactions.savePersistentAssistant(
                7L, 9L, "request-1", "最终结论"))
                .thenReturn(mock(AgentMessage.class));

        var response = service(adaptive).execute(7L, 9L, request());

        assertThat(response.route())
                .isEqualTo("PERSISTENT_PLAN_EXECUTE");
        ArgumentCaptor<V2AdaptiveExecutionService.Command> command =
                ArgumentCaptor.forClass(
                        V2AdaptiveExecutionService.Command.class);
        verify(adaptive).execute(command.capture());
        assertThat(command.getValue().bindings()).isEmpty();
        verify(transactions).savePersistentAssistant(
                7L, 9L, "request-1", "最终结论");
    }

    @Test
    void persistentRuntimeUsesRequestScopedSettingsEndpoint() {
        stubPersistentPlanning();
        V2AdaptiveExecutionService adaptive =
                mock(V2AdaptiveExecutionService.class);
        when(adaptive.execute(any())).thenAnswer(invocation -> {
            V2AdaptiveExecutionService.Command command =
                    invocation.getArgument(0);
            command.modelProvider().complete(new ModelRequest(
                    new ModelRequestId("runtime-request"),
                    new CorrelationId("runtime-trace"),
                    List.of(new ModelMessage(
                            MessageRole.USER, "runtime turn")),
                    List.of(),
                    new GenerationOptions(
                            128, 0, 0.1d,
                            java.util.OptionalLong.empty(), java.util.Map.of()),
                    Optional.of(new io.paperagent.v2.contracts.TaskFrameId(
                            "frame-1")),
                    Optional.of(new PlanId("product-plan.test")),
                    Optional.of(new io.paperagent.v2.contracts.PlanRevisionId(
                            "revision-1")),
                    Optional.of(new io.paperagent.v2.contracts.PlanStepId(
                            "read-1")),
                    false));
            return new V2AdaptiveExecutionResult(
                    "FAILED", List.of(), null, "EXPECTED", 1, 0, 0);
        });

        service(adaptive).execute(7L, 9L, request());

        ArgumentCaptor<ChatRequest> calls =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(calls.capture());
        ChatRequest runtime = calls.getAllValues().get(1);
        assertThat(runtime.provider()).isEqualTo("deepseek");
        assertThat(runtime.model()).isEqualTo("model");
        assertThat(runtime.apiKey()).isEqualTo("SECRET-KEY");
        assertThat(runtime.messages())
                .extracting(ChatMessage::content)
                .noneMatch(value -> value.contains("SECRET-KEY"));
    }

    @Test
    void sameClientRequestResumesRunningAdaptiveTurnWithoutReplanning()
            throws Exception {
        PersistedPlanBootstrap persisted = stubPersistentPlanning();
        V2AdaptiveExecutionService adaptive =
                mock(V2AdaptiveExecutionService.class);
        when(adaptive.canResume(7L, 9L, "request-1"))
                .thenReturn(true);
        when(adaptive.execute(any()))
                .thenReturn(
                        new V2AdaptiveExecutionResult(
                                "RUNNING", List.of(), null,
                                null, 1, 0, 0),
                        new V2AdaptiveExecutionResult(
                                "SUCCEEDED", List.of(), "最终结论",
                                null, 2, 0, 0));
        ProductPlanBootstrapRepositoryAdapter resume =
                mock(ProductPlanBootstrapRepositoryAdapter.class);
        when(resume.find(new PlanId("product-plan.test")))
                .thenReturn(Optional.of(persisted));
        when(transactions.savePersistentAssistant(
                7L, 9L, "request-1", "最终结论"))
                .thenReturn(mock(AgentMessage.class));
        V2NaturalLanguageTurnService service =
                service(adaptive, resume);

        var first = service.execute(7L, 9L, request());
        var second = service.execute(7L, 9L, request());

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        ArgumentCaptor<V2AdaptiveExecutionService.Command> commands =
                ArgumentCaptor.forClass(
                        V2AdaptiveExecutionService.Command.class);
        verify(adaptive, times(2)).execute(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(
                        V2AdaptiveExecutionService.Command::clientRequestId)
                .containsOnly("request-1");
        assertThat(commands.getAllValues())
                .extracting(command ->
                        command.bootstrap().plan().id().value())
                .containsOnly("product-plan.test");
        verify(model, times(1)).chat(any());
        verify(bootstraps, times(1)).bootstrap(
                eq(7L), eq(12L), any());
        verify(transactions).savePersistentAssistant(
                7L, 9L, "request-1", "最终结论");
    }

    @Test
    void changedPayloadConflictStopsBeforePlanning() {
        when(transactions.open(
                eq(7L), eq(9L), eq("request-1"), any(), eq("question"),
                eq(false), eq("skill-1"), any(),
                any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "clientRequestId was already used for another payload"));

        assertThrows(IllegalArgumentException.class,
                () -> service().execute(7L, 9L, request()));

        verify(model, never()).chat(any());
        verify(bootstraps, never()).bootstrap(any(), any(), any());
    }

    @Test
    void malformedPlannerFailureCreatesNoSuccessfulFacts() {
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("{bad json"), "stop", null));
        org.mockito.Mockito.doAnswer(invocation -> {
            intake.fail(invocation.getArgument(1), Instant.now());
            return null;
        }).when(transactions).saveFailure(eq(intake), any());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service().execute(7L, 9L, request()));

        ArgumentCaptor<String> failureCode =
                ArgumentCaptor.forClass(String.class);
        verify(transactions).saveFailure(eq(intake), failureCode.capture());
        assertThat(failureCode.getValue()).matches(
                "PLANNER_JSON_SYNTAX_[0-9a-f]{12}");
        verify(transactions, never()).saveAssistant(any(), any(), any());
        verify(transactions, never()).savePersistent(any(), any(), any(), any());
        verify(bootstraps, never()).bootstrap(any(), any(), any());
        assertThat(intake.status()).isEqualTo(V2TurnIntakeEntity.FAILED);
    }

    @Test
    void projectVersionContextStillAllowsAuditedDirect() {
        when(contexts.resolve(7L, 12L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "12", 7L, 9L, 91L),
                        Optional.of("server-manifest-v8")));
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(
                        directAnswer("2")),
                "stop", null));
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentMessage message = new AgentMessage(
                    9L, 7L, "assistant", "2", null, null);
            setId(message, 13L);
            intake.completeDirect(
                    13L, invocation.getArgument(2), Instant.now());
            when(transactions.message(13L)).thenReturn(message);
            return message;
        }).when(transactions).saveAssistant(
                eq(intake), eq("2"), any());

        var response = service().execute(7L, 9L, request());

        ArgumentCaptor<AgentContextBuildRequest> context =
                ArgumentCaptor.forClass(AgentContextBuildRequest.class);
        verify(contextBuilder).build(context.capture());
        assertThat(context.getValue().projectState().projectId())
                .isEqualTo(91L);
        assertThat(context.getValue().projectState().projectVersion())
                .isEqualTo("server-manifest-v8");
        assertThat(response.route()).isEqualTo("DIRECT");
        assertThat(response.answer()).isEqualTo("2");
        verify(bootstraps, never()).bootstrap(any(), any(), any());
        verify(transactions).saveAssistant(eq(intake), eq("2"), any());
    }

    @Test
    void concurrentSameKeyAttemptsConvergeOnOnePersistentPlan()
            throws Exception {
        stubPersistentPlanning();
        int attempts = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        try {
            List<java.util.concurrent.Future<V2NaturalLanguageTurnResponse>>
                    futures = new java.util.ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                futures.add(pool.submit(
                        () -> service().execute(7L, 9L, request())));
            }
            List<V2NaturalLanguageTurnResponse> responses =
                    new java.util.ArrayList<>();
            for (var future : futures) {
                responses.add(future.get(
                        10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(responses)
                    .extracting(V2NaturalLanguageTurnResponse::planId)
                    .containsOnly("product-plan.test");
            assertThat(responses.stream()
                    .filter(value -> !value.replayed()).count())
                    .isEqualTo(1);
            verify(model, times(1)).chat(any());
            verify(bootstraps, times(1)).bootstrap(
                    eq(7L), eq(12L), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayKeepsTurnModelAfterDefaultSettingChanges() {
        PersistedPlanBootstrap persisted = stubPersistentPlanning();
        UserSettingsService.ModelEndpoint changedDefault =
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "changed-pro", null, "NEW-KEY",
                        "builtin", "DeepSeek");
        when(settings.resolveModelEndpoint(7L, null, null))
                .thenReturn(
                        new UserSettingsService.ModelEndpoint(
                                "deepseek", "model", null, "SECRET-KEY",
                                "builtin", "DeepSeek"),
                        changedDefault);
        V2AdaptiveExecutionService adaptive =
                mock(V2AdaptiveExecutionService.class);
        when(adaptive.canResume(7L, 9L, "request-1"))
                .thenReturn(true);
        when(adaptive.execute(any())).thenReturn(
                new V2AdaptiveExecutionResult(
                        "RUNNING", List.of(), null, null, 1, 0, 0),
                new V2AdaptiveExecutionResult(
                        "RUNNING", List.of(), null, null, 2, 0, 0));
        ProductPlanBootstrapRepositoryAdapter resume =
                mock(ProductPlanBootstrapRepositoryAdapter.class);
        when(resume.find(new PlanId("product-plan.test")))
                .thenReturn(Optional.of(persisted));

        V2NaturalLanguageTurnService service = service(adaptive, resume);
        service.execute(7L, 9L, request());
        service.execute(7L, 9L, request());

        verify(settings, times(2)).resolveModelEndpoint(
                7L, "deepseek", "model");
        verify(settings, never()).resolveModelEndpoint(
                7L, "deepseek", "changed-pro");
    }

    private PersistedPlanBootstrap stubPersistentPlanning() {
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {"route":"PERSISTENT_PLAN_EXECUTE",
                         "requirements":{"projectEvidence":true,
                           "toolUse":true,"retrieval":false,
                           "networkAccess":false,"execution":false,
                           "durableModification":false,
                           "durableProgress":true},
                         "taskFrame":{"objective":"Inspect","targets":["project"],
                           "deliverables":["report"],"constraints":["read only"]},
                         "plan":{"reason":"Need evidence","steps":[{
                           "id":"read-1","intent":"Read source",
                           "expectedOutcome":"Text is available",
                           "dependencies":[],
                           "completionCriteria":["Read receipt exists"],
                           "maxAttempts":1,"maxDurationSeconds":120}]}}
                        """),
                "stop", null));
        PersistedPlanBootstrap persisted = mock(PersistedPlanBootstrap.class);
        Plan plan = mock(Plan.class);
        TaskFrame taskFrame = mock(TaskFrame.class);
        when(plan.id()).thenReturn(new PlanId("product-plan.test"));
        when(persisted.plan()).thenReturn(plan);
        when(taskFrame.createdAt()).thenReturn(Instant.EPOCH);
        when(persisted.taskFrame()).thenReturn(taskFrame);
        when(bootstraps.bootstrap(eq(7L), eq(12L), any()))
                .thenReturn(PersistenceResult.applied(persisted));
        org.mockito.Mockito.doAnswer(invocation -> {
            intake.completePersistent(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    Instant.now());
            return null;
        }).when(transactions).savePersistent(
                eq(intake), eq("product-plan.test"), any(), any());
        return persisted;
    }

    private V2NaturalLanguageTurnService service() {
        return new V2NaturalLanguageTurnService(
                sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                model);
    }

    private V2NaturalLanguageTurnService service(
            V2PlannerContextBoundaryFactory boundaries) {
        return new V2NaturalLanguageTurnService(
                sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                model, null, null, boundaries);
    }

    private static String directAnswer(String answer) {
        return """
                {"route":"DIRECT","requirements":{
                  "projectEvidence":false,"toolUse":false,
                  "retrieval":false,"networkAccess":false,
                  "execution":false,"durableModification":false,
                  "durableProgress":false},"answer":"%s"}
                """.formatted(answer);
    }

    private V2NaturalLanguageTurnService service(
            V2AdaptiveExecutionService adaptive) {
        return service(adaptive, null);
    }

    private V2NaturalLanguageTurnService service(
            V2AdaptiveExecutionService adaptive,
            ProductPlanBootstrapRepositoryAdapter resume) {
        return new V2NaturalLanguageTurnService(
                sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                model, adaptive, resume);
    }

    private V2NaturalLanguageTurnRequest request() {
        return new V2NaturalLanguageTurnRequest(
                "question", false, "skill-1",
                new AgentExperimentRequest(
                        true, null, null, null, null, List.of(), false),
                "request-1");
    }

    private static void setId(AgentMessage message, Long value)
            throws Exception {
        Field id = AgentMessage.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(message, value);
    }

}
