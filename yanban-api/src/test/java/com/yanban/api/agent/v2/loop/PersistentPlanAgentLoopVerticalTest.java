package com.yanban.api.agent.v2.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.LiteratureSearchStartToolExecutor;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.bootstrap
        .AgentV2PlanBootstrapConfiguration;
import com.yanban.api.agent.v2.effect
        .AuthenticatedLiteratureSearchEffectExecutionComposer;
import com.yanban.api.agent.v2.effect
        .AuthenticatedLiteratureSearchEffectExecutionCommand;
import com.yanban.api.agent.v2.persistence
        .ProductEffectExecutionClaimRepository;
import com.yanban.api.agent.v2.persistence
        .ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.agent.v2.progression
        .AuthenticatedEffectDrivenStepProgressionComposer;
import com.yanban.api.agent.v2.progression
        .EffectDrivenStepProgressionCommand;
import com.yanban.api.agent.v2.progression
        .EffectDrivenStepProgressionState;
import com.yanban.api.agent.v2.progression
        .ProductStepProgressionConfiguration;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.execution.activation.composition
        .StepActivationComposer;
import io.paperagent.v2.runtime.execution.completion.composition
        .ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.progression
        .StepProgressionInspector;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopTestSupport.TURN_ID;
import static com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopTestSupport.USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2_persistent_loop_vertical;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        ProductStepProgressionConfiguration.class,
        PersistentPlanAgentLoopVerticalTest.PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersistentPlanAgentLoopVerticalTest {
    @TestConfiguration
    @ComponentScan(
            basePackageClasses =
                    ProductPlanBootstrapRepositoryAdapter.class)
    static class PersistenceSlice {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean
    private AgentTurnProductContextResolver productContexts;

    @Autowired
    private ProductPlanIdDerivation planIds;

    @Autowired
    private PlanBootstrapRepository bootstrapRepository;

    @Autowired
    private LeaseRepository leaseRepository;

    @Autowired
    private ExecutionStartRepository executionStartRepository;

    @Autowired
    private StepActivationRepository stepActivationRepository;

    @Autowired
    private StepRecoveryRepository stepRecoveryRepository;
    @Autowired
    private StepInterruptionRepository stepInterruptionRepository;

    @Autowired
    private EffectIntentRepository effectIntentRepository;

    @Autowired
    private EffectOutcomeRepository effectOutcomeRepository;

    @Autowired
    private ProductEffectExecutionClaimRepository claims;

    @Autowired
    private StepRecoverer persistedRecoverer;

    @Autowired
    private StepActivationComposer persistedActivation;

    @Autowired
    private StepProgressionInspector progressionInspector;

    @Autowired
    private ActiveStepCompletionComposer completion;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private LiteratureSearchTaskRepository literatureTasks;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearDurableRows() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA='PUBLIC' "
                                + "AND (TABLE_NAME LIKE 'AGENT_V2_%' "
                                + "OR TABLE_NAME='LITERATURE_SEARCH_TASKS')",
                        String.class)
                .forEach(table ->
                        jdbc.execute("TRUNCATE TABLE " + table));
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @Test
    void twoStepCallCarriesExactPersistedToolCallsToTerminalSuccess() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var activeA = PersistentPlanAgentLoopTestSupport.active(
                fixture.planId(), "step-a");
        var activeB = PersistentPlanAgentLoopTestSupport.active(
                fixture.planId(), "step-b");
        var intentA = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), activeA.stepId(), "a",
                "literature.search");
        var intentB = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), activeB.stepId(), "b",
                "literature.search");
        PersistedStepRecoverySucceeded succeeded =
                mock(PersistedStepRecoverySucceeded.class);
        when(succeeded.planId()).thenReturn(fixture.planId());
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(
                        activeA.recovery()))
                .thenReturn(PersistenceResult.found(
                        activeB.recovery()));
        when(fixture.recoverer().recover(any()))
                .thenReturn(activeA.active())
                .thenReturn(activeB.active());
        when(fixture.kernel().run(any()))
                .thenReturn(intentA.outcome())
                .thenReturn(intentB.outcome());
        var effectA = PersistentPlanAgentLoopTestSupport
                .successfulEffect(intentA.toolCallId());
        var effectB = PersistentPlanAgentLoopTestSupport
                .successfulEffect(intentB.toolCallId());
        when(fixture.effects().execute(
                eq(USER_ID), eq(TURN_ID), any()))
                .thenReturn(effectA)
                .thenReturn(effectB);
        var progressedA = PersistentPlanAgentLoopTestSupport.progression(
                fixture.planId(), activeA.stepId(),
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                activeB.recovery());
        var progressedB = PersistentPlanAgentLoopTestSupport.progression(
                fixture.planId(), activeB.stepId(),
                EffectDrivenStepProgressionState.PLAN_SUCCEEDED,
                succeeded);
        when(fixture.progression().progress(
                eq(USER_ID), eq(TURN_ID), any()))
                .thenReturn(progressedA)
                .thenReturn(progressedB);

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2));

        assertEquals(PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                outcome.state());
        assertEquals(2, outcome.cyclesAttempted());
        assertEquals(activeB.stepId(),
                outcome.stepId().orElseThrow());
        assertEquals(PersistentPlanAgentLoopCutKind.SUCCEEDED,
                outcome.cut().orElseThrow().kind());

        ArgumentCaptor<AuthenticatedLiteratureSearchEffectExecutionCommand>
                effects = ArgumentCaptor.forClass(
                        AuthenticatedLiteratureSearchEffectExecutionCommand
                                .class);
        verify(fixture.effects(), times(2)).execute(
                eq(USER_ID), eq(TURN_ID), effects.capture());
        assertEquals(
                List.of(intentA.toolCallId(), intentB.toolCallId()),
                effects.getAllValues().stream()
                        .map(AuthenticatedLiteratureSearchEffectExecutionCommand
                                ::toolCallId)
                        .toList());

        ArgumentCaptor<EffectDrivenStepProgressionCommand> progressions =
                ArgumentCaptor.forClass(
                        EffectDrivenStepProgressionCommand.class);
        verify(fixture.progression(), times(2)).progress(
                eq(USER_ID), eq(TURN_ID), progressions.capture());
        assertEquals(
                List.of(intentA.toolCallId(), intentB.toolCallId()),
                progressions.getAllValues().stream()
                        .map(EffectDrivenStepProgressionCommand::toolCallId)
                        .toList());
    }

    @Test
    void realH2TwoStepLoopAndTerminalRestartKeepOneDurableChain() {
        VerifiedAgentTurnProductContext context =
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "turn-42",
                                USER_ID, 11L, null),
                        Optional.empty());
        when(productContexts.resolve(USER_ID, TURN_ID))
                .thenReturn(context);
        var planId = planIds.derive(context.identity());
        var scenario =
                PersistentPlanAgentLoopTestSupport.seedDurableTwoStep(
                        planId, bootstrapRepository, leaseRepository,
                        executionStartRepository,
                        stepActivationRepository);

        SingleTurnStepKernel kernel =
                mock(SingleTurnStepKernel.class);
        when(kernel.run(any())).thenAnswer(invocation ->
                PersistentPlanAgentLoopTestSupport.persistDurableIntent(
                        effectIntentRepository,
                        invocation.<io.paperagent.v2.runtime.execution.kernel
                                .SingleTurnStepKernelRequest>getArgument(0)
                                .recoveredStep()));

        LiteratureSearchStartToolExecutor executor =
                mock(LiteratureSearchStartToolExecutor.class);
        when(executor.execute(any(ToolCall.class)))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    String query = call.arguments()
                            .path("query").asText();
                    String requestId = call.arguments()
                            .path("clientRequestId").asText();
                    LiteratureSearchTask task =
                            literatureTasks.saveAndFlush(
                                    new LiteratureSearchTask(
                                            USER_ID, null, query,
                                            query.toLowerCase(
                                                    java.util.Locale.ROOT),
                                            8, null, true,
                                            "PENDING", "QUEUED",
                                            requestId, requestId));
                    var output = json.createObjectNode();
                    output.put("taskId", task.getId());
                    output.put("status", task.getStatus());
                    output.put("currentStage",
                            task.getCurrentStage());
                    return ToolResult.success(
                            call.id(),
                            "literature_search_start", output);
                });
        AtomicInteger observed = new AtomicInteger();
        java.time.Instant effectStart = java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        AuthenticatedLiteratureSearchEffectExecutionComposer effects =
                new AuthenticatedLiteratureSearchEffectExecutionComposer(
                        productContexts, planIds, persistedRecoverer,
                        effectIntentRepository, claims, executor,
                        () -> effectStart.plusMillis(
                                observed.getAndIncrement()),
                        json);
        AuthenticatedEffectDrivenStepProgressionComposer progression =
                new AuthenticatedEffectDrivenStepProgressionComposer(
                        productContexts, planIds, progressionInspector,
                        persistedRecoverer, effectIntentRepository,
                        effectOutcomeRepository, completion,
                        persistedActivation);
        var composer =
                new AuthenticatedPersistentPlanAgentLoopComposer(
                        productContexts, planIds, stepRecoveryRepository,
                        persistedRecoverer, persistedActivation, kernel,
                        effects, progression,
                        mock(io.paperagent.v2.runtime.execution.replan
                                .composition.BoundedStepReplanComposer.class));
        var command = PersistentPlanAgentLoopTestSupport.command(
                2, scenario.lease());

        PersistentPlanAgentLoopOutcome first =
                composer.execute(USER_ID, TURN_ID, command);
        PersistentPlanAgentLoopOutcome restart =
                composer.execute(USER_ID, TURN_ID, command);

        assertEquals(PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                first.state());
        assertEquals(2, first.cyclesAttempted());
        assertEquals(PersistentPlanAgentLoopCutKind.SUCCEEDED,
                first.cut().orElseThrow().kind());
        assertEquals(PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                restart.state());
        assertEquals(0, restart.cyclesAttempted());
        assertEquals(PersistentPlanAgentLoopCutKind.SUCCEEDED,
                restart.cut().orElseThrow().kind());
        assertInstanceOf(PersistedStepRecoverySucceeded.class,
                stepRecoveryRepository.inspect(planId)
                        .value().orElseThrow());
        assertEquals(2, count("agent_v2_effect_intents"));
        assertEquals(2, count("agent_v2_effect_execution_claims"));
        assertEquals(2, count("agent_v2_effect_results"));
        assertEquals(2, count("agent_v2_receipts"));
        assertEquals(2, count("agent_v2_step_completions"));
        assertEquals(2, count("agent_v2_step_activations"));
        assertEquals(2, literatureTasks.count());
        verify(kernel, times(2)).run(any());
        verify(executor, times(2)).execute(any());
        assertNull(ToolExecutionContext.getCurrentUserId());
        assertNull(ToolExecutionContext.getResolvedAllowedTools());
    }

    @Test
    void realH2CompletedStepAAllowsPausingCurrentActiveStepB() {
        VerifiedAgentTurnProductContext context =
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "turn-42",
                                USER_ID, 11L, null),
                        Optional.empty());
        when(productContexts.resolve(USER_ID, TURN_ID))
                .thenReturn(context);
        var planId = planIds.derive(context.identity());
        var scenario =
                PersistentPlanAgentLoopTestSupport.seedDurableTwoStep(
                        planId, bootstrapRepository, leaseRepository,
                        executionStartRepository,
                        stepActivationRepository);
        SingleTurnStepKernel kernel =
                mock(SingleTurnStepKernel.class);
        when(kernel.run(any())).thenAnswer(invocation ->
                PersistentPlanAgentLoopTestSupport.persistDurableIntent(
                        effectIntentRepository,
                        invocation.<io.paperagent.v2.runtime.execution.kernel
                                .SingleTurnStepKernelRequest>getArgument(0)
                                .recoveredStep()));
        LiteratureSearchStartToolExecutor executor =
                mock(LiteratureSearchStartToolExecutor.class);
        when(executor.execute(any(ToolCall.class)))
                .thenAnswer(invocation -> {
                    ToolCall call = invocation.getArgument(0);
                    LiteratureSearchTask task =
                            literatureTasks.saveAndFlush(
                                    new LiteratureSearchTask(
                                            USER_ID, null,
                                            "step-b-boundary",
                                            "step-b-boundary",
                                            8, null, true,
                                            "PENDING", "QUEUED",
                                            call.id(), call.id()));
                    var output = json.createObjectNode();
                    output.put("taskId", task.getId());
                    output.put("status", task.getStatus());
                    output.put("currentStage",
                            task.getCurrentStage());
                    return ToolResult.success(
                            call.id(),
                            "literature_search_start", output);
                });
        AuthenticatedLiteratureSearchEffectExecutionComposer effects =
                new AuthenticatedLiteratureSearchEffectExecutionComposer(
                        productContexts, planIds, persistedRecoverer,
                        effectIntentRepository, claims, executor,
                        java.time.Instant::now, json);
        AuthenticatedEffectDrivenStepProgressionComposer progression =
                new AuthenticatedEffectDrivenStepProgressionComposer(
                        productContexts, planIds, progressionInspector,
                        persistedRecoverer, effectIntentRepository,
                        effectOutcomeRepository, completion,
                        persistedActivation);
        var composer =
                new AuthenticatedPersistentPlanAgentLoopComposer(
                        productContexts, planIds, stepRecoveryRepository,
                        persistedRecoverer, persistedActivation, kernel,
                        effects, progression,
                        mock(io.paperagent.v2.runtime.execution.replan
                                .composition.BoundedStepReplanComposer.class));

        var bounded = composer.execute(
                USER_ID, TURN_ID,
                PersistentPlanAgentLoopTestSupport.command(
                        1, scenario.lease()));
        assertEquals(PersistentPlanAgentLoopState.REPLAN_REQUIRED,
                bounded.state());
        PersistedStepRecoveryActive active =
                (PersistedStepRecoveryActive) stepRecoveryRepository
                        .inspect(planId).value().orElseThrow();
        assertEquals(scenario.secondStepId(),
                active.activation().stepId());
        var source = active.checkpoint().checkpoint();
        var states = new java.util.LinkedHashMap<>(
                source.stepStates());
        states.put(scenario.secondStepId(),
                io.paperagent.v2.contracts.StepExecutionState.PAUSED);
        String completionEventId = jdbc.queryForObject(
                "SELECT completion_event_id FROM agent_v2_step_completions "
                        + "WHERE plan_id = ? AND step_id = ?",
                String.class, planId.value(),
                scenario.firstStepId().value());
        var collisionEvent = new io.paperagent.v2.contracts.EventEnvelope(
                new io.paperagent.v2.contracts.EventId(
                        completionEventId),
                source.taskFrameId(), source.planId(),
                source.lastEventSequence() + 1,
                source.createdAt().plusSeconds(1),
                new io.paperagent.v2.contracts.EventType(
                        "STEP_PAUSED"),
                Optional.of(
                        active.activation().activationEvent().id()),
                "pause-current-step-b",
                new io.paperagent.v2.contracts.InlineEventPayload(
                        new io.paperagent.v2.contracts.ObjectValue(
                                java.util.Map.of())));
        var paused = new io.paperagent.v2.contracts.Checkpoint(
                source.taskFrameId(), source.planId(),
                source.revisionId(), source.revisionNumber(),
                collisionEvent.sequence(),
                io.paperagent.v2.contracts.PlanExecutionState.PAUSED,
                states, source.receiptReferences(),
                source.createdAt().plusSeconds(1));

        var collision = stepInterruptionRepository.pause(
                new StepPauseRequest(
                        planId, scenario.lease().leaseToken(),
                        scenario.lease().fencingToken(),
                        source.revisionId(), source.revisionNumber(),
                        active.checkpoint().version(),
                        source.lastEventSequence(),
                        scenario.secondStepId(), collisionEvent, paused));
        assertEquals(
                io.paperagent.v2.persistence.PersistenceOutcome.REJECTED,
                collision.outcome());
        assertEquals(
                io.paperagent.v2.persistence.PersistenceErrorCode
                        .CONFLICTING_REPLAY,
                collision.failure().orElseThrow().code());
        assertEquals(0, count("agent_v2_step_interruptions"));

        var event = new io.paperagent.v2.contracts.EventEnvelope(
                new io.paperagent.v2.contracts.EventId(
                        "pause-current-step-b"),
                collisionEvent.taskFrameId(), collisionEvent.planId(),
                collisionEvent.sequence(), collisionEvent.occurredAt(),
                collisionEvent.type(), collisionEvent.causationId(),
                collisionEvent.correlationId(), collisionEvent.payload());
        var result = stepInterruptionRepository.pause(
                new StepPauseRequest(
                        planId, scenario.lease().leaseToken(),
                        scenario.lease().fencingToken(),
                        source.revisionId(), source.revisionNumber(),
                        active.checkpoint().version(),
                        source.lastEventSequence(),
                        scenario.secondStepId(), event, paused));

        assertEquals(
                io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                result.outcome());
        assertEquals(1, count("agent_v2_step_interruptions"));
        assertEquals(
                io.paperagent.v2.persistence.PersistenceOutcome.REJECTED,
                stepRecoveryRepository.inspect(planId).outcome());
    }

    @Test
    void commandAndFailureNeverRenderLeaseTokenOrCollaboratorPayload() {
        var command = PersistentPlanAgentLoopTestSupport.command(2);
        assertFalse(command.toString().contains("lease-token"));
        String secret = "provider-secret-payload";
        var failure = new PersistentPlanAgentLoopException("kernel");
        assertFalse(failure.toString().contains(secret));
    }

    @Test
    void publicOutcomeEvidenceExposesOnlyOpaqueAuthorityMetadata() {
        Set<String> cutAccessors = Arrays.stream(
                        PersistentPlanAgentLoopCut.class
                                .getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                        "kind", "stepId", "revisionId",
                        "revisionNumber", "checkpointVersion",
                        "eventSequence"),
                cutAccessors);

        Set<String> replanAccessors = Arrays.stream(
                        PersistentPlanAgentLoopReplanEvidence.class
                                .getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                        "supersededStepId", "supersessionEventId",
                        "replanEventId", "replannedRevisionId",
                        "supersededCheckpointVersion",
                        "replannedCheckpointVersion"),
                replanAccessors);
        assertFalse(cutAccessors.stream().anyMatch(
                PersistentPlanAgentLoopVerticalTest::rawPayloadName));
        assertFalse(replanAccessors.stream().anyMatch(
                PersistentPlanAgentLoopVerticalTest::rawPayloadName));
    }

    @Test
    void nonPositiveBoundIsRejectedBeforeAnyInspection() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();

        assertThrows(IllegalArgumentException.class,
                () -> fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(0)));

        verify(fixture.inspections(),
                org.mockito.Mockito.never()).inspect(any());
    }

    private static boolean rawPayloadName(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("taskframe")
                || normalized.contains("text")
                || normalized.contains("payload")
                || normalized.contains("lease")
                || normalized.contains("token")
                || normalized.contains("output")
                || normalized.contains("prompt");
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
