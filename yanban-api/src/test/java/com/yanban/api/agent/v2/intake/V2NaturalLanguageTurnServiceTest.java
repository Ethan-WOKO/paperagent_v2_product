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
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.providers.*;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
            7L, "session", "deepseek", "model", 20, false,
            AgentSessionScope.WORKSPACE, null);
    private final V2TurnIntakeEntity intake = new V2TurnIntakeEntity(
            7L, 9L, "request-1", "a".repeat(64), "question",
            false, "skill-1", "{}", 11L, 12L, Instant.EPOCH);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(session));
        when(transactions.open(
                eq(7L), eq(9L), eq("request-1"), any(), eq("question"),
                eq(false), eq("skill-1"), any()))
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
        when(settings.resolveModelEndpoint(7L, "deepseek", "model"))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "deepseek", "model", null, "SECRET-KEY",
                        "builtin", "DeepSeek"));
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
                        "{\"route\":\"DIRECT\",\"answer\":\"answer\"}"),
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

        ArgumentCaptor<AgentContextBuildRequest> context =
                ArgumentCaptor.forClass(AgentContextBuildRequest.class);
        verify(contextBuilder).build(context.capture());
        assertThat(context.getValue().sessionSummary())
                .isEqualTo("rolling summary");
        assertThat(context.getValue().longTermMemoryContext().content())
                .isEqualTo("confirmed memory");
        assertThat(context.getValue().ragContext())
                .isEqualTo("relevant RAG evidence");

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
    void persistentStoresLlmAuthoredFrameAndPlanWithoutExecution() {
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {"route":"PERSISTENT_PLAN_EXECUTE",
                         "taskFrame":{"objective":"Inspect","targets":["project"],
                           "deliverables":["report"],"constraints":["read only"]},
                         "plan":{"reason":"Need evidence","steps":[{
                           "id":"read-1","intent":"Read source",
                           "expectedOutcome":"Text is available",
                           "dependencies":[],
                           "completionCriteria":["Read receipt exists"],
                           "maxAttempts":1,"maxDurationSeconds":120,
                           "capability":"project_read"}]}}
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
        assertThat(command.getValue().bindings())
                .containsEntry("read-1", "project.read");
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
    void changedPayloadConflictStopsBeforePlanning() {
        when(transactions.open(
                eq(7L), eq(9L), eq("request-1"), any(), eq("question"),
                eq(false), eq("skill-1"), any()))
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

        verify(transactions, never()).saveAssistant(any(), any(), any());
        verify(transactions, never()).savePersistent(any(), any(), any(), any());
        verify(bootstraps, never()).bootstrap(any(), any(), any());
        assertThat(intake.status()).isEqualTo(V2TurnIntakeEntity.FAILED);
    }

    @Test
    void projectVersionComesFromAuthenticatedContextAndDirectIsForbidden() {
        when(contexts.resolve(7L, 12L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "12", 7L, 9L, 91L),
                        Optional.of("server-manifest-v8")));
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(
                        "{\"route\":\"DIRECT\",\"answer\":\"answer\"}"),
                "stop", null));
        org.mockito.Mockito.doAnswer(invocation -> {
            intake.fail(invocation.getArgument(1), Instant.now());
            return null;
        }).when(transactions).saveFailure(eq(intake), any());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service().execute(7L, 9L, request()));

        ArgumentCaptor<AgentContextBuildRequest> context =
                ArgumentCaptor.forClass(AgentContextBuildRequest.class);
        verify(contextBuilder).build(context.capture());
        assertThat(context.getValue().projectState().projectId())
                .isEqualTo(91L);
        assertThat(context.getValue().projectState().projectVersion())
                .isEqualTo("server-manifest-v8");
        verify(bootstraps, never()).bootstrap(any(), any(), any());
        verify(transactions, never()).saveAssistant(any(), any(), any());
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

    private void stubPersistentPlanning() {
        when(model.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("""
                        {"route":"PERSISTENT_PLAN_EXECUTE",
                         "taskFrame":{"objective":"Inspect","targets":["project"],
                           "deliverables":["report"],"constraints":["read only"]},
                         "plan":{"reason":"Need evidence","steps":[{
                           "id":"read-1","intent":"Read source",
                           "expectedOutcome":"Text is available",
                           "dependencies":[],
                           "completionCriteria":["Read receipt exists"],
                           "maxAttempts":1,"maxDurationSeconds":120,
                           "capability":"project_read"}]}}
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
    }

    private V2NaturalLanguageTurnService service() {
        return new V2NaturalLanguageTurnService(
                sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                model);
    }

    private V2NaturalLanguageTurnService service(
            V2AdaptiveExecutionService adaptive) {
        return new V2NaturalLanguageTurnService(
                sessions, transactions, contexts, contextBuilder, summaries,
                memories, experiments, skills, settings, bootstraps, json,
                model, adaptive);
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
