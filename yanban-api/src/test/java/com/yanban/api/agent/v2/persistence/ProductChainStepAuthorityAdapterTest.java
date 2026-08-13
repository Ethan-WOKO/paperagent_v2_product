package com.yanban.api.agent.v2.persistence;

import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnStepActivationCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnStepActivationComposer;
import com.yanban.api.agent.v2.progression.AuthenticatedAgentTurnStepProgressionComposer;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.WorkspaceCandidateRecord;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEventCommand;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEventKind;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseDisposition;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProductChainStepAuthorityAdapterTest {
    private static final Instant NOW = Instant.parse("2099-08-07T01:00:00Z");

    private ChainWorkflowRepository workflows;
    private ChainFoundationRepository foundations;
    private ProductPlanBootstrapRepositoryAdapter bootstraps;
    private ProductStepActivationJpaRepository activations;
    private ProductStepActivationCodec activationCodec;
    private ProductStepCompletionJpaRepository completions;
    private ProductActiveStepReplanJpaRepository replans;
    private LeaseRepository leases;
    private AuthenticatedAgentTurnStepActivationComposer composer;
    private AuthenticatedAgentTurnStepProgressionComposer progression;
    private StepRecoverer recoverer;
    private ActiveStepCompletionComposer completion;
    private ProductActiveStepReplanMarkerReader replanMarkers;
    private ProductPlanRevisionAuthoritySource revisionAuthorities;
    private JdbcTemplate jdbc;
    private ProductChainStepAuthorityAdapter adapter;

    @BeforeEach
    void setUp() {
        workflows = mock(ChainWorkflowRepository.class);
        foundations = mock(ChainFoundationRepository.class);
        bootstraps = mock(ProductPlanBootstrapRepositoryAdapter.class);
        activations = mock(ProductStepActivationJpaRepository.class);
        activationCodec = mock(ProductStepActivationCodec.class);
        completions = mock(ProductStepCompletionJpaRepository.class);
        replans = mock(ProductActiveStepReplanJpaRepository.class);
        leases = mock(LeaseRepository.class);
        composer = mock(AuthenticatedAgentTurnStepActivationComposer.class);
        progression = mock(AuthenticatedAgentTurnStepProgressionComposer.class);
        recoverer = mock(StepRecoverer.class);
        completion = mock(ActiveStepCompletionComposer.class);
        replanMarkers = mock(ProductActiveStepReplanMarkerReader.class);
        revisionAuthorities = mock(
                ProductPlanRevisionAuthoritySource.class);
        jdbc = mock(JdbcTemplate.class);
        adapter = new ProductChainStepAuthorityAdapter(
                workflows, foundations, bootstraps, activations,
                activationCodec, completions, replans, leases, composer, jdbc,
                recoverer, completion, null, null, null, progression,
                replanMarkers, revisionAuthorities);
        when(activations.findAllByPlanId(any())).thenReturn(List.of());
        when(completions.findAllByPlanId(any())).thenReturn(List.of());
        when(replans.findAllByPlanIdOrderBySourceEventSequenceAsc(any()))
                .thenReturn(List.of());
    }

    @Test
    void mapsOnlyTheFormallyBoundStablePlanRevisionAndDependencies() {
        bindPlan();

        var snapshot = adapter.findPlan("task-1", "revision-1")
                .orElseThrow();

        assertEquals("frame-1", snapshot.taskFrameId());
        assertEquals("plan-1", snapshot.planId());
        assertEquals(ChainIdentity.NONE, snapshot.targetCandidateKey());
        assertEquals("instruction-1",
                snapshot.targetInstructionVersionId());
        assertEquals(List.of("step-1", "step-2"), snapshot.steps().stream()
                .map(value -> value.stepId()).toList());
        assertEquals(Set.of("step-1"),
                snapshot.steps().get(1).prerequisiteStepIds());
    }

    @Test
    void readsTheExactFullPlanRevisionWithoutLatestSelection() {
        bindPlan();

        PlanRevision revision = adapter.findPlanRevision(
                "task-1", "revision-1").orElseThrow();

        assertEquals("revision-1", revision.id().value());
        assertEquals(List.of("step-1", "step-2"), revision.steps().stream()
                .map(value -> value.id().value()).toList());
    }

    @Test
    void readsTheExactRevisionFromTheCommittedActiveStepReplan() {
        ReplannedFixture fixture = bindReplannedPlan();

        var snapshot = adapter.findPlan("task-1", "revision-2")
                .orElseThrow();
        PlanRevision revision = adapter.findPlanRevision(
                "task-1", "revision-2").orElseThrow();

        assertEquals(List.of("replacement-step", "following-step"),
                snapshot.steps().stream().map(value -> value.stepId()).toList());
        assertEquals("revision-2", revision.id().value());
        assertEquals(fixture.revision(), revision);
    }

    @Test
    void rejectsAReplannedRevisionWhoseCommittedMarkerIsInvalid() {
        bindReplannedPlan();
        when(revisionAuthorities.find("plan-1", "revision-2"))
                .thenReturn(Optional.empty());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.findPlan("task-1", "revision-2"));

        assertEquals("CHAIN_STEP_PLAN_REVISION_NOT_STABLE",
                failure.getMessage());
    }

    @Test
    void bindsPlanCandidateIdentityToWorkspaceCandidateId() {
        bindPlan();
        when(workflows.findWorkspaceCandidates("task-1")).thenReturn(List.of(
                new WorkspaceCandidateRecord(
                        "workspace-candidate-1", "task-1", "candidate-event-1",
                        "action-1", "workspace-1", "project-version-1", 1L,
                        "a".repeat(64), "b".repeat(64), "c".repeat(64), NOW)));

        var snapshot = adapter.findPlan("task-1", "revision-1")
                .orElseThrow();

        assertEquals("workspace-candidate-1", snapshot.targetCandidateKey());
    }

    @Test
    void supersessionOnlyReplaysTheExactCommittedActiveReplan() {
        StepEventCommand command = supersessionCommand();
        bindReplanTransition();
        ProductActiveStepReplanEntity row =
                mock(ProductActiveStepReplanEntity.class);
        PersistedActiveStepReplan result = supersessionResult(
                command, "transition-1");
        when(replans.findBySupersessionEventId(command.eventId()))
                .thenReturn(Optional.of(row));
        when(replanMarkers.read(row)).thenReturn(
                new ProductActiveStepReplanMarkerReader.Marker(
                        mock(io.paperagent.v2.persistence
                                .ActiveStepReplanRequest.class), result));
        PlanBindingRecord binding = mock(PlanBindingRecord.class);
        when(binding.planRevisionId()).thenReturn("revision-1");
        when(binding.planId()).thenReturn("plan-1");
        when(workflows.findPlanBindings("task-1"))
                .thenReturn(List.of(binding));

        var replay = adapter.appendStepEvent(command);

        assertTrue(replay.replayed());
        assertEquals(command, replay.value().command());
        assertEquals(5L, replay.value().authoritySequence());
        verify(replans).findBySupersessionEventId(command.eventId());
        verifyNoMoreInteractions(replans);
    }

    @Test
    void supersessionRejectsCommittedAuthorityWithAnotherTransition() {
        StepEventCommand command = supersessionCommand();
        bindReplanTransition();
        ProductActiveStepReplanEntity row =
                mock(ProductActiveStepReplanEntity.class);
        when(replans.findBySupersessionEventId(command.eventId()))
                .thenReturn(Optional.of(row));
        var request = mock(io.paperagent.v2.persistence
                .ActiveStepReplanRequest.class);
        var result = supersessionResult(command, "other-transition");
        when(replanMarkers.read(row)).thenReturn(
                new ProductActiveStepReplanMarkerReader.Marker(
                        request, result));
        PlanBindingRecord binding = mock(PlanBindingRecord.class);
        when(binding.planRevisionId()).thenReturn("revision-1");
        when(binding.planId()).thenReturn("plan-1");
        when(workflows.findPlanBindings("task-1"))
                .thenReturn(List.of(binding));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> adapter.appendStepEvent(command));

        assertEquals("CHAIN_STEP_REPLAN_IDENTITY_MISMATCH",
                failure.getMessage());
        verify(replans).findBySupersessionEventId(command.eventId());
        verifyNoMoreInteractions(replans);
    }

    @Test
    void projectsFormalLaterActivationAcrossCompletedRevisionBoundary()
            throws Exception {
        bindPlan();
        StepEventCommand command = new StepEventCommand(
                "activation-2", "task-1", "revision-1", "step-2",
                "activation-2", StepEventKind.ACTIVATED, "decision-1",
                "transition-1", NOW);
        ProductStepActivationEntity later = mock(
                ProductStepActivationEntity.class);
        when(later.planId()).thenReturn("plan-1");
        when(later.sourceRevisionId()).thenReturn("completed-revision-1");
        when(later.activationEventId()).thenReturn("activation-2");
        when(later.stepId()).thenReturn("step-2");
        when(later.resultEventSequence()).thenReturn(4L);
        when(later.committedAt()).thenReturn(NOW);
        when(later.resultFormatVersion()).thenReturn(1);
        when(later.resultSha256()).thenReturn("result-sha");
        when(later.resultJson()).thenReturn("result-json");
        when(activations.findAllByPlanId("plan-1"))
                .thenReturn(List.of(later));
        PersistedStepActivation persisted = persistedActivation(command);
        when(activationCodec.decodeResult(
                1, "result-sha", "result-json"))
                .thenReturn(persisted);
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.transitionId()).thenReturn("transition-1");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));

        var events = adapter.findStepEvents("task-1", "revision-1");

        assertEquals(1, events.size());
        assertEquals("activation-2", events.get(0).command().eventId());
        assertEquals("step-2", events.get(0).command().stepId());
        assertEquals(StepEventKind.ACTIVATED,
                events.get(0).command().eventKind());
    }

    @Test
    void replannedRevisionExcludesEventsOwnedByThePreviousRevision() {
        bindReplannedPlan();
        ProductStepActivationEntity previousActivation = mock(
                ProductStepActivationEntity.class);
        when(previousActivation.resultEventSequence()).thenReturn(2L);
        when(activations.findAllByPlanId("plan-1"))
                .thenReturn(List.of(previousActivation));
        ProductActiveStepReplanEntity replan = mock(
                ProductActiveStepReplanEntity.class);
        when(replan.resultRevisionId()).thenReturn("revision-2");
        when(replan.resultEventSequence()).thenReturn(4L);
        when(replan.supersessionEventSequence()).thenReturn(3L);
        when(replans.findAllByPlanIdOrderBySourceEventSequenceAsc("plan-1"))
                .thenReturn(List.of(replan));

        var events = adapter.findStepEvents("task-1", "revision-2");

        assertTrue(events.isEmpty());
        org.mockito.Mockito.verifyNoInteractions(activationCodec);
    }

    @Test
    void replaysExactActivationAndBindsLeaseAndPayloadIdentity() {
        bindPlan();
        StepEventCommand command = activationCommand();
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));
        TaskRecord task = mock(TaskRecord.class);
        when(task.userId()).thenReturn(7L);
        when(task.turnId()).thenReturn(11L);
        when(foundations.findTask("task-1")).thenReturn(Optional.of(task));

        PersistedStepActivation persisted = persistedActivation(command);
        when(composer.activate(anyLong(), anyLong(), any(
                AuthenticatedAgentTurnStepActivationCommand.class)))
                .thenReturn(new StepActivationCommitted(
                        PersistenceOutcome.REPLAYED, persisted,
                        StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY));
        LeaseRecord lease = new LeaseRecord(
                new PlanId("plan-1"), "chain-step-authority",
                "chain-step-" + command.eventId(), 3L, NOW,
                NOW.plusSeconds(600));
        when(leases.find(new PlanId("plan-1")))
                .thenReturn(PersistenceResult.found(lease));

        var appended = adapter.appendStepEvent(command);

        assertTrue(appended.replayed());
        assertEquals(command, appended.value().command());
        assertEquals(2L, appended.value().authoritySequence());
        var captured = org.mockito.ArgumentCaptor.forClass(
                AuthenticatedAgentTurnStepActivationCommand.class);
        verify(composer).activate(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(11L), captured.capture());
        var draft = captured.getValue().attempt().eventDraft();
        assertEquals(command.eventId(), draft.id().value());
        assertEquals(command.transitionId(), draft.correlationId());
        assertEquals(new EventId(command.eventId()),
                captured.getValue().attempt().eventDraft().id());
        assertTrue(draft.payload() instanceof InlineEventPayload);
    }

    @Test
    void activatesLaterStepFromLatestReadyCheckpointAuthority() {
        bindPlan();
        StepEventCommand command = new StepEventCommand(
                "activation-2", "task-1", "revision-1", "step-2",
                "activation-2", StepEventKind.ACTIVATED, "decision-1",
                "transition-1", NOW);
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));
        TaskRecord task = mock(TaskRecord.class);
        when(task.userId()).thenReturn(7L);
        when(task.turnId()).thenReturn(11L);
        when(foundations.findTask("task-1")).thenReturn(Optional.of(task));
        when(activations.findAllByPlanId("plan-1")).thenReturn(List.of(
                mock(ProductStepActivationEntity.class)));

        PersistedStepActivation persisted = persistedActivation(command);
        when(progression.activateReady(anyLong(), anyLong(), any(
                io.paperagent.v2.runtime.execution.activation.composition
                        .StepActivationAttempt.class)))
                .thenReturn(new StepActivationCommitted(
                        PersistenceOutcome.APPLIED, persisted,
                        StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY));
        LeaseRecord lease = new LeaseRecord(
                new PlanId("plan-1"), "chain-step-authority",
                "chain-step-activation-2", 3L, NOW,
                NOW.plusSeconds(600));
        when(leases.find(new PlanId("plan-1")))
                .thenReturn(PersistenceResult.found(lease));

        var appended = adapter.appendStepEvent(command);

        assertEquals("step-2", appended.value().command().stepId());
        verify(progression).activateReady(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(11L), any());
        org.mockito.Mockito.verifyNoInteractions(composer);
    }

    @Test
    void rejectsTransitionIdentityMisbindingBeforeStableMutation() {
        bindPlan();
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("another-task");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.appendStepEvent(activationCommand()));

        assertEquals("CHAIN_STEP_TRANSITION_IDENTITY_MISMATCH",
                failure.getMessage());
    }

    @Test
    void replaysExpiredLeaseFromExactStableActivationWithoutComposer() {
        bindPlan();
        StepEventCommand command = activationCommand();
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));
        ProductStepActivationEntity row = exactStoredRow(command);
        when(activations.findById(command.eventId()))
                .thenReturn(Optional.of(row));

        var appended = adapter.appendStepEvent(command);

        assertTrue(appended.replayed());
        assertEquals(2L, appended.value().authoritySequence());
        org.mockito.Mockito.verifyNoInteractions(composer, leases);
    }

    @Test
    void rejectsStableActivationWhoseFormalPayloadIsMisbinding() {
        bindPlan();
        StepEventCommand command = activationCommand();
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));
        ProductStepActivationEntity row = exactStoredRow(command);
        var request = activationRequest(command);
        when(request.activationEvent().correlationId())
                .thenReturn("another-transition");
        when(activationCodec.decodeRequest(1, "request-sha", "request-json"))
                .thenReturn(request);
        when(activations.findById(command.eventId()))
                .thenReturn(Optional.of(row));

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.appendStepEvent(command));

        assertEquals("CHAIN_STEP_ACTIVATION_REPLAY_MISMATCH",
                failure.getMessage());
        org.mockito.Mockito.verifyNoInteractions(composer, leases);
    }

    @Test
    void rejectsTerminalEventsWhenFormalPlanAuthorityIsMissing() {
        StepEventCommand completion = new StepEventCommand(
                "completion-1", "task-1", "revision-1", "step-1",
                "activation-1", StepEventKind.COMPLETED, "review-1",
                "transition-1", NOW);

        var failure = assertThrows(IllegalStateException.class,
                () -> adapter.appendStepEvent(completion));

        assertEquals("CHAIN_STEP_PLAN_NOT_FOUND",
                failure.getMessage());
    }

    @Test
    void commitsCompletedStepThroughStableCompletionAuthority() {
        bindPlan();
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.sourceDecisionId()).thenReturn("decision-1");
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));
        LeaseRecord lease = new LeaseRecord(
                new PlanId("plan-1"), "chain-step-authority", "token", 3L,
                NOW, NOW.plusSeconds(600));
        when(leases.find(new PlanId("plan-1")))
                .thenReturn(PersistenceResult.found(lease));

        PersistedStepRecoveryActive recovery = mock(
                PersistedStepRecoveryActive.class);
        PersistedStepActivation recoveredActivation = persistedActivation(
                activationCommand());
        when(recovery.activation()).thenReturn(recoveredActivation);
        Checkpoint recoveryCheckpoint = mock(Checkpoint.class);
        when(recoveryCheckpoint.createdAt()).thenReturn(NOW);
        VersionedCheckpoint recoveryVersioned = mock(VersionedCheckpoint.class);
        when(recoveryVersioned.checkpoint()).thenReturn(recoveryCheckpoint);
        when(recovery.checkpoint()).thenReturn(recoveryVersioned);
        RecoveredActiveStep recovered = mock(RecoveredActiveStep.class);
        when(recovered.recovery()).thenReturn(recovery);
        when(recoverer.recover(any())).thenReturn(recovered);

        String completionId = "step.completed." + sha256(
                "task-1\0revision-1\0step-1\0activation-1\0transition-1");
        PersistedStepCompletion persisted = mock(PersistedStepCompletion.class);
        EventEnvelope completionEvent = mock(EventEnvelope.class);
        when(completionEvent.id()).thenReturn(new EventId(completionId));
        when(completionEvent.sequence()).thenReturn(7L);
        when(persisted.completionEvent()).thenReturn(completionEvent);
        when(completion.compose(any())).thenReturn(
                new ActiveStepCompletionCommitted(
                        PersistenceOutcome.APPLIED, persisted,
                        ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY));

        ProductChainStepAuthorityAdapter spy = org.mockito.Mockito.spy(adapter);
        doReturn(List.of(new ChainStepAuthorityPort.StepEvent(
                activationCommand(), 2L)))
                .when(spy).findStepEvents("task-1", "revision-1");
        StepEventCommand command = new StepEventCommand(
                completionId, "task-1", "revision-1", "step-1",
                "activation-1", StepEventKind.COMPLETED, "decision-1",
                "transition-1", NOW);

        var appended = spy.appendStepEvent(command);

        assertEquals(7L, appended.value().authoritySequence());
        assertTrue(!appended.replayed());
        verify(recoverer).recover(any());
        verify(completion).compose(any());
    }

    private void bindPlan() {
        PlanBindingRecord binding = mock(PlanBindingRecord.class);
        when(binding.taskId()).thenReturn("task-1");
        when(binding.taskFrameId()).thenReturn("frame-1");
        when(binding.planId()).thenReturn("plan-1");
        when(binding.planRevisionId()).thenReturn("revision-1");
        when(binding.planRevisionNumber()).thenReturn(1L);
        when(binding.instructionId()).thenReturn("instruction-1");
        when(workflows.findPlanBindings("task-1"))
                .thenReturn(List.of(binding));

        TaskFrame frame = mock(TaskFrame.class);
        when(frame.id()).thenReturn(new TaskFrameId("frame-1"));
        PlanStep first = mock(PlanStep.class);
        when(first.id()).thenReturn(new PlanStepId("step-1"));
        when(first.dependencies()).thenReturn(Set.of());
        PlanStep second = mock(PlanStep.class);
        when(second.id()).thenReturn(new PlanStepId("step-2"));
        when(second.dependencies()).thenReturn(Set.of(
                new PlanStepId("step-1")));
        PlanRevision revision = mock(PlanRevision.class);
        when(revision.id()).thenReturn(new PlanRevisionId("revision-1"));
        when(revision.number()).thenReturn(1L);
        when(revision.taskFrameId()).thenReturn(new TaskFrameId("frame-1"));
        when(revision.steps()).thenReturn(List.of(first, second));
        Plan plan = mock(Plan.class);
        when(plan.taskFrameId()).thenReturn(new TaskFrameId("frame-1"));
        when(plan.revisions()).thenReturn(List.of(revision));
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        when(bootstrap.taskFrame()).thenReturn(frame);
        when(bootstrap.plan()).thenReturn(plan);
        when(bootstraps.find(new PlanId("plan-1")))
                .thenReturn(Optional.of(bootstrap));
    }

    private ReplannedFixture bindReplannedPlan() {
        PlanBindingRecord binding = mock(PlanBindingRecord.class);
        when(binding.taskId()).thenReturn("task-1");
        when(binding.taskFrameId()).thenReturn("frame-1");
        when(binding.planId()).thenReturn("plan-1");
        when(binding.planRevisionId()).thenReturn("revision-2");
        when(binding.planRevisionNumber()).thenReturn(2L);
        when(binding.instructionId()).thenReturn("instruction-1");
        when(workflows.findPlanBindings("task-1"))
                .thenReturn(List.of(binding));

        TaskFrame frame = mock(TaskFrame.class);
        when(frame.id()).thenReturn(new TaskFrameId("frame-1"));
        PlanRevision initial = mock(PlanRevision.class);
        when(initial.id()).thenReturn(new PlanRevisionId("revision-1"));
        Plan plan = mock(Plan.class);
        when(plan.taskFrameId()).thenReturn(new TaskFrameId("frame-1"));
        when(plan.revisions()).thenReturn(List.of(initial));
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        when(bootstrap.taskFrame()).thenReturn(frame);
        when(bootstrap.plan()).thenReturn(plan);
        when(bootstraps.find(new PlanId("plan-1")))
                .thenReturn(Optional.of(bootstrap));

        PlanStep replacement = mock(PlanStep.class);
        when(replacement.id()).thenReturn(new PlanStepId("replacement-step"));
        when(replacement.dependencies()).thenReturn(Set.of());
        PlanStep following = mock(PlanStep.class);
        when(following.id()).thenReturn(new PlanStepId("following-step"));
        when(following.dependencies()).thenReturn(Set.of(
                new PlanStepId("replacement-step")));
        PlanRevision replanned = mock(PlanRevision.class);
        when(replanned.id()).thenReturn(new PlanRevisionId("revision-2"));
        when(replanned.number()).thenReturn(2L);
        when(replanned.taskFrameId()).thenReturn(new TaskFrameId("frame-1"));
        when(replanned.steps()).thenReturn(List.of(replacement, following));

        when(revisionAuthorities.find("plan-1", "revision-2"))
                .thenReturn(Optional.of(
                        new ProductPlanRevisionAuthoritySource.RevisionAuthority(
                                replanned, "a".repeat(64))));
        return new ReplannedFixture(replanned);
    }

    private record ReplannedFixture(PlanRevision revision) {
    }

    private void bindReplanTransition() {
        TransitionRecord transition = mock(TransitionRecord.class);
        when(transition.taskId()).thenReturn("task-1");
        when(transition.sourceDecisionId()).thenReturn("review-1");
        when(transition.transitionType()).thenReturn(
                io.paperagent.v2.chain.ChainTransitionType.PLAN_CHANGE);
        when(workflows.findTransition("transition-1"))
                .thenReturn(Optional.of(transition));
    }

    private static StepEventCommand activationCommand() {
        return new StepEventCommand(
                "activation-1", "task-1", "revision-1", "step-1",
                "activation-1", StepEventKind.ACTIVATED, "decision-1",
                "transition-1", NOW);
    }

    private static StepEventCommand supersessionCommand() {
        return new StepEventCommand(
                "step.superseded_by_replan." + "a".repeat(64),
                "task-1", "revision-1", "old-step",
                "activation-1", StepEventKind.SUPERSEDED_BY_REPLAN,
                "review-1", "transition-1", NOW);
    }

    private static PersistedActiveStepReplan supersessionResult(
            StepEventCommand command, String correlationId) {
        PlanId planId = new PlanId("plan-1");
        PlanStepId stepId = new PlanStepId(command.stepId());
        EventEnvelope event = new EventEnvelope(
                new EventId(command.eventId()), new TaskFrameId("frame-1"),
                planId, 5L, command.committedAt(),
                new EventType("STEP_SUPERSEDED_BY_REPLAN"),
                Optional.of(new EventId(command.activationEventId())),
                correlationId,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                new TaskFrameId("frame-1"), planId,
                new PlanRevisionId(command.planRevisionId()), 1L, 5L,
                io.paperagent.v2.contracts.PlanExecutionState.ACTIVE,
                Map.of(stepId, io.paperagent.v2.contracts.StepExecutionState
                        .SUPERSEDED_BY_REPLAN),
                List.of(), command.committedAt());
        PersistedActiveStepReplan result =
                mock(PersistedActiveStepReplan.class);
        when(result.planId()).thenReturn(planId);
        when(result.supersededStepId()).thenReturn(stepId);
        when(result.supersessionEvent()).thenReturn(event);
        when(result.supersededCheckpoint()).thenReturn(
                new VersionedCheckpoint(4L, checkpoint));
        return result;
    }

    private static PersistedStepActivation persistedActivation(
            StepEventCommand command) {
        EventEnvelope event = mock(EventEnvelope.class);
        when(event.id()).thenReturn(new EventId(command.eventId()));
        when(event.planId()).thenReturn(new PlanId("plan-1"));
        when(event.sequence()).thenReturn(2L);
        when(event.occurredAt()).thenReturn(command.committedAt());
        when(event.correlationId()).thenReturn(command.transitionId());
        when(event.payload()).thenReturn(new InlineEventPayload(
                new io.paperagent.v2.contracts.ObjectValue(
                        java.util.Map.of(
                                "sourceDecisionId", new io.paperagent.v2.contracts.TextValue("decision-1"),
                                "transitionId", new io.paperagent.v2.contracts.TextValue("transition-1"),
                                "taskId", new io.paperagent.v2.contracts.TextValue("task-1"),
                                "planRevisionId", new io.paperagent.v2.contracts.TextValue("revision-1"),
                                "stepId", new io.paperagent.v2.contracts.TextValue(command.stepId())))));
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.revisionId()).thenReturn(
                new PlanRevisionId("revision-1"));
        when(checkpoint.createdAt()).thenReturn(command.committedAt());
        VersionedCheckpoint versioned = mock(VersionedCheckpoint.class);
        when(versioned.checkpoint()).thenReturn(checkpoint);
        PersistedStepActivation persisted = mock(
                PersistedStepActivation.class);
        when(persisted.planId()).thenReturn(new PlanId("plan-1"));
        when(persisted.stepId()).thenReturn(new PlanStepId(command.stepId()));
        when(persisted.fencingToken()).thenReturn(3L);
        when(persisted.activationEvent()).thenReturn(event);
        when(persisted.activatedCheckpoint()).thenReturn(versioned);
        return persisted;
    }

    private ProductStepActivationEntity exactStoredRow(
            StepEventCommand command) {
        ProductStepActivationEntity row = mock(
                ProductStepActivationEntity.class);
        when(row.planId()).thenReturn("plan-1");
        when(row.stepId()).thenReturn("step-1");
        when(row.activationEventId()).thenReturn(command.eventId());
        when(row.sourceRevisionId()).thenReturn("revision-1");
        when(row.requestFormatVersion()).thenReturn(1);
        when(row.requestSha256()).thenReturn("request-sha");
        when(row.requestJson()).thenReturn("request-json");
        when(row.resultFormatVersion()).thenReturn(1);
        when(row.resultSha256()).thenReturn("result-sha");
        when(row.resultJson()).thenReturn("result-json");
        var request = activationRequest(command);
        var result = persistedActivation(command);
        var resultEvent = result.activationEvent();
        when(request.activationEvent()).thenReturn(resultEvent);
        when(activationCodec.decodeRequest(1, "request-sha", "request-json"))
                .thenReturn(request);
        when(activationCodec.decodeResult(1, "result-sha", "result-json"))
                .thenReturn(result);
        return row;
    }

    private static io.paperagent.v2.persistence.StepActivationRequest
            activationRequest(StepEventCommand command) {
        var request = mock(
                io.paperagent.v2.persistence.StepActivationRequest.class);
        when(request.planId()).thenReturn(new PlanId("plan-1"));
        when(request.stepId()).thenReturn(new PlanStepId("step-1"));
        when(request.expectedRevisionId()).thenReturn(
                new PlanRevisionId("revision-1"));
        when(request.expectedCheckpointVersion()).thenReturn(2L);
        var persisted = persistedActivation(command);
        var activationEvent = persisted.activationEvent();
        when(request.activationEvent()).thenReturn(activationEvent);
        return request;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
