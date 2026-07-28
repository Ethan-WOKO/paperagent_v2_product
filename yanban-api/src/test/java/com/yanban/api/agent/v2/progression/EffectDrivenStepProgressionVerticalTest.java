package com.yanban.api.agent.v2.progression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.bootstrap.AgentV2PlanBootstrapConfiguration;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PlanBootstrapRepository;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.runtime.execution.activation.composition.ReadyStepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.composition
        .ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.recovery.composition
        .RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryRequest;
import com.yanban.core.tool.ToolExecutionContext;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2_effect_progression_vertical;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        AgentV2PlanBootstrapConfiguration.class,
        ProductStepProgressionConfiguration.class,
        AuthenticatedEffectDrivenStepProgressionComposer.class,
        EffectDrivenStepProgressionVerticalTest.PersistenceSlice.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EffectDrivenStepProgressionVerticalTest {
    @TestConfiguration
    @ComponentScan(
            basePackageClasses = ProductPlanBootstrapRepositoryAdapter.class)
    static class PersistenceSlice {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockBean
    private AgentTurnProductContextResolver productContexts;

    @Autowired
    private PlanBootstrapRepository bootstrapRepository;

    @Autowired
    private LeaseRepository leaseRepository;

    @Autowired
    private ExecutionStartRepository executionStartRepository;

    @Autowired
    private StepActivationRepository stepActivationRepository;

    @Autowired
    private EffectIntentRepository effectIntentRepository;

    @Autowired
    private EffectOutcomeRepository effectOutcomeRepository;

    @Autowired
    private AuthenticatedEffectDrivenStepProgressionComposer
            persistedComposer;

    @Autowired
    private StepRecoverer persistedRecoverer;

    @Autowired
    private ActiveStepCompletionComposer persistedCompletion;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void successfulEffectCompletesAAndActivatesB() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        fixture.inspections(
                fixture.activeA, fixture.readyB, fixture.activeB);

        var outcome = fixture.composer.progress(
                7L, 42L, fixture.command());

        assertEquals(
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                outcome.state());
        assertEquals(Optional.of(PersistenceOutcome.APPLIED),
                outcome.completionOutcome());
        assertEquals(Optional.of(PersistenceOutcome.APPLIED),
                outcome.activationOutcome());
        assertEquals(EffectDrivenStepProgressionTestFixtures.B,
                fixture.activeB.activation().stepId());
        verify(fixture.completion).compose(anyCompletion());
        verify(fixture.activation).composeReady(anyActivation());
    }

    @Test
    void productDatabaseCommitsCompletionAndNextActivationOnce() {
        clearV2Rows();
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(productContexts.resolve(7L, 42L))
                .thenReturn(fixture.context);
        var plan = fixture.activeA.plan();
        var revision = plan.latestRevision();
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> initial =
                new LinkedHashMap<>();
        revision.steps().forEach(step ->
                initial.put(step.id(), StepExecutionState.NOT_STARTED));
        Checkpoint h0 = new Checkpoint(
                fixture.taskFrame.id(), fixture.planId,
                revision.id(), revision.number(), 0,
                PlanExecutionState.NOT_STARTED, initial, List.of(),
                EffectDrivenStepProgressionTestFixtures.T0);
        assertEquals(PersistenceOutcome.APPLIED,
                bootstrapRepository.bootstrap(
                        fixture.taskFrame, plan, h0).outcome());

        String owner = "db-owner";
        String token = "db-token";
        var expires = EffectDrivenStepProgressionTestFixtures.T0
                .plusSeconds(300);
        var lease = leaseRepository.acquire(
                fixture.planId, owner, token, expires)
                .value().orElseThrow();
        EventEnvelope startEvent = new EventEnvelope(
                new EventId("db-start"), fixture.taskFrame.id(),
                fixture.planId, 1,
                EffectDrivenStepProgressionTestFixtures.T0.plusSeconds(1),
                new EventType("EXECUTION_STARTED"), Optional.empty(),
                "db-start-correlation",
                new InlineEventPayload(
                        new io.paperagent.v2.contracts.ObjectValue(Map.of())));
        Checkpoint started = new Checkpoint(
                h0.taskFrameId(), h0.planId(), h0.revisionId(),
                h0.revisionNumber(), 1, PlanExecutionState.ACTIVE,
                h0.stepStates(), h0.receiptReferences(),
                EffectDrivenStepProgressionTestFixtures.T0.plusSeconds(1));
        assertEquals(PersistenceOutcome.APPLIED,
                executionStartRepository.start(new ExecutionStartRequest(
                        fixture.planId, token, lease.fencingToken(),
                        startEvent, started)).outcome());

        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> active =
                new LinkedHashMap<>(started.stepStates());
        active.put(EffectDrivenStepProgressionTestFixtures.A,
                StepExecutionState.ACTIVE);
        EventEnvelope activationEvent = new EventEnvelope(
                EffectDrivenStepProgressionTestFixtures.ACTIVATION_A,
                fixture.taskFrame.id(), fixture.planId, 2,
                EffectDrivenStepProgressionTestFixtures.T0.plusSeconds(2),
                new EventType("STEP_ACTIVATED"),
                Optional.of(startEvent.id()), "db-activation-a",
                new InlineEventPayload(
                        new io.paperagent.v2.contracts.ObjectValue(Map.of())));
        Checkpoint activated = new Checkpoint(
                started.taskFrameId(), started.planId(),
                started.revisionId(), started.revisionNumber(), 2,
                PlanExecutionState.ACTIVE, active,
                started.receiptReferences(),
                EffectDrivenStepProgressionTestFixtures.T0.plusSeconds(2));
        assertEquals(PersistenceOutcome.APPLIED,
                stepActivationRepository.activate(new StepActivationRequest(
                        fixture.planId, token, lease.fencingToken(),
                        revision.id(), revision.number(), 2, 1,
                        EffectDrivenStepProgressionTestFixtures.A,
                        activationEvent, activated)).outcome());

        EffectIntent persistedIntent = new EffectIntent(
                EffectDrivenStepProgressionTestFixtures.TOOL,
                fixture.planId,
                EffectDrivenStepProgressionTestFixtures.A,
                "literature.search",
                fixture.intent.intent().arguments());
        assertEquals(PersistenceOutcome.APPLIED,
                effectIntentRepository.persist(new EffectIntentRequest(
                        persistedIntent, token, lease.fencingToken(),
                        activationEvent.id())).outcome());
        var dbReceipt = new io.paperagent.v2.contracts.ExecutionReceipt(
                fixture.receipt.id(), fixture.receipt.toolCallId(),
                fixture.receipt.status(), fixture.receipt.startedAt(),
                fixture.receipt.endedAt(), fixture.receipt.exitCode(),
                fixture.receipt.resultCode(),
                fixture.receipt.standardOutput(),
                fixture.receipt.standardError(),
                fixture.receipt.artifactReferences(),
                fixture.receipt.resultingDiff(),
                fixture.receipt.eventReferences());
        assertEquals(PersistenceOutcome.APPLIED,
                effectOutcomeRepository.recordResult(
                        new EffectResultRequest(
                                dbReceipt, token,
                                lease.fencingToken())).outcome());
        var command = new EffectDrivenStepProgressionCommand(
                fixture.planId,
                EffectDrivenStepProgressionTestFixtures.TOOL,
                new io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryLeaseAttempt(owner, token, expires),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        owner, token, expires));

        var applied = persistedComposer.progress(7L, 42L, command);
        var replayed = persistedComposer.progress(7L, 42L, command);

        assertEquals(EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                applied.state());
        assertEquals(EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                replayed.state());
        assertEquals(1, count("agent_v2_step_completions"));
        assertEquals(2, count("agent_v2_step_activations"));
        assertEquals(1, count("agent_v2_effect_results"));
        assertEquals(1, count("agent_v2_receipts"));
    }

    @Test
    void productDatabaseResumesDurableReadyGapAfterCompletionCrash() {
        clearV2Rows();
        var scenario = EffectDrivenStepProgressionTestFixtures.seedDatabase(
                bootstrapRepository, leaseRepository,
                executionStartRepository, stepActivationRepository,
                effectIntentRepository, effectOutcomeRepository);
        when(productContexts.resolve(7L, 42L))
                .thenReturn(scenario.fixture().context);
        var recovered = (RecoveredActiveStep) persistedRecoverer.recover(
                new StepRecoveryRequest(
                        scenario.fixture().planId,
                        scenario.command()
                                .currentStepRecoveryAttempt()));
        var intent = effectIntentRepository.find(
                scenario.command().toolCallId())
                .value().orElseThrow();
        var receipt = effectOutcomeRepository.findResult(
                scenario.command().toolCallId())
                .value().orElseThrow().receipt();
        persistedCompletion.compose(
                EffectDrivenStepProgressionDrafts.completion(
                        recovered, intent, receipt));
        assertEquals(1, count("agent_v2_step_completions"));
        assertEquals(1, count("agent_v2_step_activations"));

        var resumed = persistedComposer.progress(
                7L, 42L, scenario.command());

        assertEquals(EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                resumed.state());
        assertEquals(1, count("agent_v2_step_completions"));
        assertEquals(2, count("agent_v2_step_activations"));
    }

    @Test
    void productDatabaseSingleStepBecomesSucceededWithoutActivation() {
        clearV2Rows();
        var scenario = EffectDrivenStepProgressionTestFixtures.seedDatabase(
                bootstrapRepository, leaseRepository,
                executionStartRepository, stepActivationRepository,
                effectIntentRepository, effectOutcomeRepository, false);
        when(productContexts.resolve(7L, 42L))
                .thenReturn(scenario.fixture().context);

        var applied = persistedComposer.progress(
                7L, 42L, scenario.command());
        var replayed = persistedComposer.progress(
                7L, 42L, scenario.command());

        assertEquals(EffectDrivenStepProgressionState.PLAN_SUCCEEDED,
                applied.state());
        assertEquals(EffectDrivenStepProgressionState.PLAN_SUCCEEDED,
                replayed.state());
        assertEquals(1, count("agent_v2_step_completions"));
        assertEquals(1, count("agent_v2_step_activations"));
    }

    @Test
    void singleStepCompletesToSucceededWithoutActivation() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        var succeeded = fixture.succeeded();
        fixture.inspections(fixture.activeA, succeeded);

        var outcome = fixture.composer.progress(
                7L, 42L, fixture.command());

        assertEquals(
                EffectDrivenStepProgressionState.PLAN_SUCCEEDED,
                outcome.state());
        verify(fixture.activation, never()).composeReady(anyActivation());
    }

    @Test
    void crashAfterCompletionResumesFromReadyWithoutCompletingAgain() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        fixture.inspections(fixture.readyB, fixture.activeB);

        var outcome = fixture.composer.progress(
                7L, 42L, fixture.command());

        assertEquals(
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                outcome.state());
        assertTrue(outcome.completionOutcome().isEmpty());
        verify(fixture.completion, never()).compose(anyCompletion());
        verify(fixture.activation).composeReady(anyActivation());
    }

    @Test
    void replayAfterNextActivationWritesNeitherPhase() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        fixture.inspections(fixture.activeB);

        var outcome = fixture.composer.progress(
                7L, 42L, fixture.command());

        assertEquals(
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                outcome.state());
        assertTrue(outcome.completionOutcome().isEmpty());
        assertTrue(outcome.activationOutcome().isEmpty());
        verify(fixture.recoverer, never()).recover(anyRecovery());
        verify(fixture.completion, never()).compose(anyCompletion());
        verify(fixture.activation, never()).composeReady(anyActivation());
    }

    @Test
    void staleNextActiveFailsWhenExactReceiptSemanticsDoNotProveCompletion() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        var changedReceipt = new io.paperagent.v2.contracts.ExecutionReceipt(
                fixture.receipt.id(), fixture.receipt.toolCallId(),
                fixture.receipt.status(), fixture.receipt.startedAt(),
                fixture.receipt.endedAt(), fixture.receipt.exitCode(),
                fixture.receipt.resultCode(),
                OutputCapture.inline("changed persisted semantics", false),
                fixture.receipt.standardError(),
                fixture.receipt.artifactReferences(),
                fixture.receipt.resultingDiff(),
                fixture.receipt.eventReferences());
        org.mockito.Mockito.when(
                fixture.outcomes.findResult(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(
                        new PersistedEffectResult(
                                changedReceipt,
                                fixture.result.leaseOwnerId(),
                                fixture.result.fencingToken())));
        fixture.inspections(fixture.activeB);

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        assertEquals("completion.evidence", failure.path());
        verify(fixture.completion, never()).compose(anyCompletion());
        verify(fixture.activation, never()).composeReady(anyActivation());
    }

    @Test
    void staleNextActiveRejectsNonDeterministicActivationIdentity() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        var old = fixture.activeB.activation();
        var oldEvent = old.activationEvent();
        var changedEvent = new io.paperagent.v2.contracts.EventEnvelope(
                new io.paperagent.v2.contracts.EventId(
                        "noncanonical-next-activation"),
                oldEvent.taskFrameId(), oldEvent.planId(),
                oldEvent.sequence(), oldEvent.occurredAt(), oldEvent.type(),
                oldEvent.causationId(), oldEvent.correlationId(),
                oldEvent.payload());
        var changedActivation =
                new io.paperagent.v2.persistence.PersistedStepActivation(
                        old.planId(), old.stepId(), old.leaseOwnerId(),
                        old.fencingToken(), changedEvent,
                        old.activatedCheckpoint());
        var changedActive =
                new io.paperagent.v2.persistence.PersistedStepRecoveryActive(
                        fixture.activeB.taskFrame(),
                        fixture.activeB.plan(),
                        fixture.activeB.checkpoint(),
                        changedActivation,
                        fixture.activeB.executionContext());
        fixture.inspections(changedActive);

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        assertEquals("progression.nextActive", failure.path());
        verify(fixture.completion, never()).compose(anyCompletion());
        verify(fixture.activation, never()).composeReady(anyActivation());
    }

    @Test
    void compositionDoesNotTouchProductToolExecutionContext() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        ToolExecutionContext.clear();
        fixture.inspections(fixture.activeB);

        fixture.composer.progress(7L, 42L, fixture.command());

        assertEquals(null, ToolExecutionContext.getCurrentUserId());
        assertEquals(null, ToolExecutionContext.getCurrentProjectId());
        assertEquals(null, ToolExecutionContext.getResolvedAllowedTools());
    }

    @Test
    void deterministicDraftsUseReceiptEndAndSanitizedIdentifiersOnly() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        fixture.inspections(
                fixture.activeA, fixture.readyB, fixture.activeB);

        fixture.composer.progress(7L, 42L, fixture.command());

        ArgumentCaptor<ActiveStepCompletionMaterializationRequest> completion =
                ArgumentCaptor.forClass(
                        ActiveStepCompletionMaterializationRequest.class);
        verify(fixture.completion).compose(completion.capture());
        var draft = completion.getValue();
        assertEquals(fixture.receipt.endedAt(),
                draft.completionFactDraft().completedAt());
        assertEquals(fixture.receipt.endedAt(),
                draft.eventDraft().occurredAt());
        assertEquals("STEP_COMPLETED",
                draft.eventDraft().type().value());
        assertEquals(
                EffectDrivenStepProgressionDrafts.receiptHash(
                        fixture.intent, fixture.receipt),
                draft.completionFactDraft().outcomeHash());
        String completionPayload = ((InlineEventPayload)
                draft.eventDraft().payload()).value().toString();
        assertFalse(completionPayload.contains("bounded result"));

        ArgumentCaptor<ReadyStepActivationCompositionRequest> activation =
                ArgumentCaptor.forClass(
                        ReadyStepActivationCompositionRequest.class);
        verify(fixture.activation).composeReady(activation.capture());
        assertEquals("STEP_ACTIVATED",
                activation.getValue().attempt().eventDraft()
                        .type().value());
        assertEquals(
                Optional.of(draft.eventDraft().id()),
                activation.getValue().attempt().eventDraft()
                        .causationId());
        assertEquals(
                EffectDrivenStepProgressionDrafts.nextActivationEventId(
                        EffectDrivenStepProgressionTestFixtures.B,
                        fixture.intent, fixture.receipt),
                activation.getValue().attempt().eventDraft().id());
    }

    @Test
    void changedReceiptSemanticsChangeHashButNeverPayloadOutput() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        var changed = new io.paperagent.v2.contracts.ExecutionReceipt(
                fixture.receipt.id(), fixture.receipt.toolCallId(),
                fixture.receipt.status(), fixture.receipt.startedAt(),
                fixture.receipt.endedAt(), fixture.receipt.exitCode(),
                fixture.receipt.resultCode(),
                io.paperagent.v2.contracts.OutputCapture.inline(
                        "different secret-like output", false),
                fixture.receipt.standardError(),
                fixture.receipt.artifactReferences(),
                fixture.receipt.resultingDiff(),
                fixture.receipt.eventReferences());

        assertFalse(EffectDrivenStepProgressionDrafts.receiptHash(
                fixture.intent, fixture.receipt).equals(
                EffectDrivenStepProgressionDrafts.receiptHash(
                        fixture.intent, changed)));
        var draft = EffectDrivenStepProgressionDrafts.completion(
                fixture.recoveredA, fixture.intent, changed);
        assertFalse(((InlineEventPayload) draft.eventDraft().payload())
                .value().toString().contains("different secret-like output"));
    }

    private static ActiveStepCompletionMaterializationRequest anyCompletion() {
        return org.mockito.ArgumentMatchers.any(
                ActiveStepCompletionMaterializationRequest.class);
    }

    private static ReadyStepActivationCompositionRequest anyActivation() {
        return org.mockito.ArgumentMatchers.any(
                ReadyStepActivationCompositionRequest.class);
    }

    private static io.paperagent.v2.runtime.execution.recovery.composition
            .StepRecoveryRequest anyRecovery() {
        return org.mockito.ArgumentMatchers.any(
                io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryRequest.class);
    }

    private void clearV2Rows() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA='PUBLIC' "
                                + "AND TABLE_NAME LIKE 'AGENT_V2_%'",
                        String.class)
                .forEach(table -> jdbc.execute("TRUNCATE TABLE " + table));
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
