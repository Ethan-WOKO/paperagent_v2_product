package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationAttempt;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionOutcome;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCompositionRequest;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseDisposition;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationLeaseRejected;
import io.paperagent.v2.runtime.execution.activation.materialization.StepActivationEventDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnStepActivationComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPlanIdDerivation planIds;
    private ExecutionStartRecoveryRepository executionStarts;
    private StepActivationComposer stableComposer;
    private AuthenticatedAgentTurnStepActivationComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        planIds = spy(new ProductPlanIdDerivation());
        executionStarts = mock(ExecutionStartRecoveryRepository.class);
        stableComposer = mock(StepActivationComposer.class);
        composer = new AuthenticatedAgentTurnStepActivationComposer(
                contexts, planIds, executionStarts, stableComposer);
    }

    @Test
    void resolvesDerivesInspectsAndDelegatesExactAuthorityOnceInOrder() {
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
        PlanId planId = planIds.derive(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext().identity());
        ValidCommitted fixture = validCommitted(planId);
        when(executionStarts.inspect(planId))
                .thenReturn(PersistenceResult.found(fixture.committed()));
        StepActivationCompositionOutcome outcome = outcome(planId);
        when(stableComposer.compose(any())).thenReturn(outcome);
        var command = command();

        assertSame(outcome, composer.activate(7L, 42L, command));

        InOrder order = inOrder(contexts, planIds, executionStarts, stableComposer);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(planIds).derive(any());
        order.verify(executionStarts).inspect(planId);
        ArgumentCaptor<StepActivationCompositionRequest> request =
                ArgumentCaptor.forClass(StepActivationCompositionRequest.class);
        order.verify(stableComposer).compose(request.capture());
        assertSame(fixture.committed(), request.getValue().committedStart());
        assertSame(command.stepId(), request.getValue().stepId());
        assertSame(command.attempt(), request.getValue().attempt());
    }

    @Test
    void resolverRemainsFirstForNullAndInvalidCommands() {
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());

        assertFailure(
                () -> composer.activate(7L, 42L, null),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .REQUIRED_VALUE_MISSING,
                "authenticatedStepActivation.command");
        verify(contexts).resolve(7L, 42L);
        verify(planIds).derive(any());
        verifyNoInteractions(executionStarts, stableComposer);

        setUp();
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
        StepActivationAttempt attempt = attempt();
        assertFailure(
                () -> composer.activate(
                        7L, 42L,
                        new AuthenticatedAgentTurnStepActivationCommand(
                                null, attempt)),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .REQUIRED_VALUE_MISSING,
                "authenticatedStepActivation.command.stepId");
        assertFailure(
                () -> composer.activate(
                        7L, 42L,
                        new AuthenticatedAgentTurnStepActivationCommand(
                                new PlanStepId("step-1"), null)),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .REQUIRED_VALUE_MISSING,
                "authenticatedStepActivation.command.attempt");
        verify(executionStarts, never()).inspect(any());
        verifyNoInteractions(stableComposer);
    }

    @Test
    void ownershipFailurePropagatesUnchangedBeforeAllOtherAuthority() {
        var failure = new AgentTurnProductContextResolutionException(
                AgentTurnProductContextResolutionCode.TURN_NOT_FOUND, "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.activate(7L, 404L, command())));
        verifyNoInteractions(planIds, executionStarts, stableComposer);
    }

    @Test
    void everyInvalidInspectionShapeFailsClosedWithoutComposition() {
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
        RuntimeException collaboratorFailure =
                new IllegalStateException("synthetic inspection");
        doThrow(collaboratorFailure).when(executionStarts).inspect(any());
        var wrapped = assertFailure(
                () -> composer.activate(7L, 42L, command()),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INSPECTION_COLLABORATOR_FAILURE,
                "authenticatedStepActivation.executionStartInspection");
        assertSame(collaboratorFailure, wrapped.getCause());

        assertInspectionFailure(null,
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INVALID_INSPECTION_RESULT);
        assertInspectionFailure(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND, "planId"),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .EXECUTION_START_NOT_FOUND);
        assertInspectionFailure(
                PersistenceResult.rejected(
                        PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                        "executionRecovery"),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .EXECUTION_START_REJECTED);
        assertInspectionFailure(
                PersistenceResult.found(mock(PersistedExecutionStartReady.class)),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .EXECUTION_START_NOT_COMMITTED);

        @SuppressWarnings("unchecked")
        PersistenceResult<ExecutionStartRecoverySnapshot> missingValue =
                mock(PersistenceResult.class);
        when(missingValue.outcome()).thenReturn(PersistenceOutcome.FOUND);
        when(missingValue.value()).thenReturn(Optional.empty());
        when(missingValue.failure()).thenReturn(Optional.empty());
        assertInspectionFailure(
                missingValue,
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INVALID_INSPECTION_RESULT);

        @SuppressWarnings("unchecked")
        PersistenceResult<ExecutionStartRecoverySnapshot> contradictory =
                mock(PersistenceResult.class);
        when(contradictory.outcome()).thenReturn(PersistenceOutcome.FOUND);
        when(contradictory.value()).thenReturn(Optional.of(
                mock(PersistedExecutionStartReady.class)));
        when(contradictory.failure()).thenReturn(Optional.of(
                new io.paperagent.v2.persistence.PersistenceFailure(
                        PersistenceErrorCode.NOT_FOUND, "planId")));
        assertInspectionFailure(
                contradictory,
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INVALID_INSPECTION_RESULT);

        @SuppressWarnings("unchecked")
        PersistenceResult<ExecutionStartRecoverySnapshot> rejectedWithoutFailure =
                mock(PersistenceResult.class);
        when(rejectedWithoutFailure.outcome())
                .thenReturn(PersistenceOutcome.REJECTED);
        when(rejectedWithoutFailure.value()).thenReturn(Optional.empty());
        when(rejectedWithoutFailure.failure()).thenReturn(Optional.empty());
        assertInspectionFailure(
                rejectedWithoutFailure,
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INVALID_INSPECTION_RESULT);

        @SuppressWarnings("unchecked")
        PersistenceResult<ExecutionStartRecoverySnapshot> unexpectedOutcome =
                mock(PersistenceResult.class);
        when(unexpectedOutcome.outcome()).thenReturn(PersistenceOutcome.APPLIED);
        when(unexpectedOutcome.value()).thenReturn(Optional.of(
                mock(PersistedExecutionStartReady.class)));
        when(unexpectedOutcome.failure()).thenReturn(Optional.empty());
        assertInspectionFailure(
                unexpectedOutcome,
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INVALID_INSPECTION_RESULT);
        verifyNoInteractions(stableComposer);
    }

    @Test
    void wrongPlanAndMalformedCrossLinksFailClosed() {
        PlanId expected = derivedPlanId();
        ValidCommitted wrong = validCommitted(new PlanId("wrong-plan"));
        assertInspectionFailure(
                PersistenceResult.found(wrong.committed()),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .EXECUTION_START_PLAN_MISMATCH);

        ValidCommitted malformed = validCommitted(expected);
        when(malformed.startEvent().sequence()).thenReturn(2L);
        assertInspectionFailure(
                PersistenceResult.found(malformed.committed()),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .MALFORMED_COMMITTED_START);

        malformed = validCommitted(expected);
        when(malformed.startedCheckpoint().planState())
                .thenReturn(PlanExecutionState.NOT_STARTED);
        assertInspectionFailure(
                PersistenceResult.found(malformed.committed()),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .MALFORMED_COMMITTED_START);

        malformed = validCommitted(expected);
        when(malformed.bootstrapPlan().taskFrameId())
                .thenReturn(new TaskFrameId("wrong-task"));
        assertInspectionFailure(
                PersistenceResult.found(malformed.committed()),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .EXECUTION_START_PLAN_MISMATCH);
        verifyNoInteractions(stableComposer);
    }

    @Test
    void stableOutcomeAndExceptionAreNeverTranslated() {
        PlanId planId = derivedPlanId();
        ValidCommitted fixture = validCommitted(planId);
        when(executionStarts.inspect(planId))
                .thenReturn(PersistenceResult.found(fixture.committed()));
        StepActivationCompositionOutcome outcome = outcome(planId);
        when(stableComposer.compose(any())).thenReturn(outcome);
        assertSame(outcome, composer.activate(7L, 42L, command()));

        RuntimeException stableFailure =
                new IllegalArgumentException("synthetic stable failure");
        when(stableComposer.compose(any())).thenThrow(stableFailure);
        assertSame(stableFailure, assertThrows(
                IllegalArgumentException.class,
                () -> composer.activate(7L, 42L, command())));
    }

    @Test
    void diagnosticsAndCommandDoNotRevealAttemptPayloadOrToken() {
        String token = "must-not-appear-token";
        StepActivationAttempt attempt = attempt(token);
        var command = new AuthenticatedAgentTurnStepActivationCommand(
                new PlanStepId("step-1"), attempt);
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
        doThrow(new IllegalStateException("synthetic inspection"))
                .when(executionStarts).inspect(any());
        assertFalse(command.toString().contains(token));
        var failure = assertFailure(
                () -> composer.activate(7L, 42L, command),
                AuthenticatedAgentTurnStepActivationCompositionCode
                        .INSPECTION_COLLABORATOR_FAILURE,
                "authenticatedStepActivation.executionStartInspection");
        assertFalse(failure.toString().contains(token));
        assertFalse(failure.toString().contains("payload-secret"));
        assertArrayEquals(
                new String[]{"stepId", "attempt"},
                Arrays.stream(
                                AuthenticatedAgentTurnStepActivationCommand.class
                                        .getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    private void assertInspectionFailure(
            PersistenceResult<ExecutionStartRecoverySnapshot> result,
            AuthenticatedAgentTurnStepActivationCompositionCode expected) {
        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
        doReturn(result).when(executionStarts).inspect(any());
        assertFailure(
                () -> composer.activate(7L, 42L, command()),
                expected,
                expected == AuthenticatedAgentTurnStepActivationCompositionCode
                                .EXECUTION_START_NOT_COMMITTED
                        || expected == AuthenticatedAgentTurnStepActivationCompositionCode
                                .EXECUTION_START_PLAN_MISMATCH
                        || expected == AuthenticatedAgentTurnStepActivationCompositionCode
                                .MALFORMED_COMMITTED_START
                        ? "authenticatedStepActivation.executionStartInspection.value"
                        : "authenticatedStepActivation.executionStartInspection");
    }

    private PlanId derivedPlanId() {
        var context =
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        return new ProductPlanIdDerivation().derive(context.identity());
    }

    private static AuthenticatedAgentTurnStepActivationCompositionException
            assertFailure(
                    org.junit.jupiter.api.function.Executable executable,
                    AuthenticatedAgentTurnStepActivationCompositionCode code,
                    String path) {
        var failure = assertThrows(
                AuthenticatedAgentTurnStepActivationCompositionException.class,
                executable);
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        return failure;
    }

    static AuthenticatedAgentTurnStepActivationCommand command() {
        return new AuthenticatedAgentTurnStepActivationCommand(
                new PlanStepId("step-1"), attempt());
    }

    static StepActivationAttempt attempt() {
        return attempt("synthetic-token");
    }

    static StepActivationAttempt attempt(String token) {
        return new StepActivationAttempt(
                "synthetic-owner",
                token,
                Instant.parse("2099-07-27T10:10:00Z"),
                new StepActivationEventDraft(
                        new EventId("synthetic-activation-event"),
                        Instant.parse("2026-07-27T10:00:05Z"),
                        new EventType("STEP_ACTIVATED"),
                        Optional.empty(),
                        "synthetic-correlation",
                        new InlineEventPayload(new ObjectValue(
                                Map.of("detail",
                                        new io.paperagent.v2.contracts.TextValue(
                                                "payload-secret"))))),
                Instant.parse("2026-07-27T10:00:06Z"));
    }

    private static StepActivationCompositionOutcome outcome(PlanId planId) {
        return new StepActivationLeaseRejected(
                planId,
                new io.paperagent.v2.persistence.PersistenceFailure(
                        PersistenceErrorCode.LEASE_HELD, "lease"),
                StepActivationLeaseDisposition.NOT_ACQUIRED);
    }

    private static ValidCommitted validCommitted(PlanId planId) {
        TaskFrameId taskFrameId = new TaskFrameId("task-frame");
        PlanStepId stepId = new PlanStepId("step-1");
        TaskFrame taskFrame = mock(TaskFrame.class);
        when(taskFrame.id()).thenReturn(taskFrameId);
        PlanStep step = mock(PlanStep.class);
        when(step.id()).thenReturn(stepId);
        PlanRevision revision = mock(PlanRevision.class);
        when(revision.steps()).thenReturn(List.of(step));
        when(revision.completedFacts()).thenReturn(Map.of());
        var revisionId =
                new io.paperagent.v2.contracts.PlanRevisionId("revision-1");
        when(revision.id()).thenReturn(revisionId);
        when(revision.number()).thenReturn(1L);
        Plan bootstrapPlan = mock(Plan.class);
        when(bootstrapPlan.id()).thenReturn(planId);
        when(bootstrapPlan.taskFrameId()).thenReturn(taskFrameId);
        when(bootstrapPlan.revisions()).thenReturn(List.of(revision));
        when(bootstrapPlan.latestRevision()).thenReturn(revision);
        Plan currentPlan = mock(Plan.class);
        when(currentPlan.id()).thenReturn(planId);
        when(currentPlan.taskFrameId()).thenReturn(taskFrameId);
        when(currentPlan.revisions()).thenReturn(List.of(revision));
        when(currentPlan.latestRevision()).thenReturn(revision);
        Checkpoint initial = checkpoint(
                taskFrameId, planId, revisionId, 0,
                PlanExecutionState.NOT_STARTED, stepId,
                Instant.parse("2026-07-27T10:00:02Z"));
        Checkpoint started = checkpoint(
                taskFrameId, planId, revisionId, 1,
                PlanExecutionState.ACTIVE, stepId,
                Instant.parse("2026-07-27T10:00:04Z"));
        VersionedCheckpoint initialVersion = mock(VersionedCheckpoint.class);
        when(initialVersion.version()).thenReturn(1L);
        when(initialVersion.checkpoint()).thenReturn(initial);
        VersionedCheckpoint startedVersion = mock(VersionedCheckpoint.class);
        when(startedVersion.version()).thenReturn(2L);
        when(startedVersion.checkpoint()).thenReturn(started);
        PersistedPlanBootstrap bootstrap = mock(PersistedPlanBootstrap.class);
        when(bootstrap.taskFrame()).thenReturn(taskFrame);
        when(bootstrap.plan()).thenReturn(bootstrapPlan);
        when(bootstrap.initialCheckpoint()).thenReturn(initialVersion);
        EventEnvelope startEvent = mock(EventEnvelope.class);
        when(startEvent.planId()).thenReturn(planId);
        when(startEvent.taskFrameId()).thenReturn(taskFrameId);
        when(startEvent.sequence()).thenReturn(1L);
        PersistedExecutionStart executionStart =
                mock(PersistedExecutionStart.class);
        when(executionStart.planId()).thenReturn(planId);
        when(executionStart.startEvent()).thenReturn(startEvent);
        when(executionStart.startedCheckpoint()).thenReturn(startedVersion);
        PersistedExecutionStartCommitted committed =
                mock(PersistedExecutionStartCommitted.class);
        when(committed.bootstrap()).thenReturn(bootstrap);
        when(committed.currentPlan()).thenReturn(currentPlan);
        when(committed.executionStart()).thenReturn(executionStart);
        when(committed.planId()).thenReturn(planId);
        return new ValidCommitted(
                committed, bootstrapPlan, startEvent, started);
    }

    private static Checkpoint checkpoint(
            TaskFrameId taskFrameId,
            PlanId planId,
            io.paperagent.v2.contracts.PlanRevisionId revisionId,
            long eventSequence,
            PlanExecutionState state,
            PlanStepId stepId,
            Instant createdAt) {
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.taskFrameId()).thenReturn(taskFrameId);
        when(checkpoint.planId()).thenReturn(planId);
        when(checkpoint.revisionId()).thenReturn(revisionId);
        when(checkpoint.revisionNumber()).thenReturn(1L);
        when(checkpoint.lastEventSequence()).thenReturn(eventSequence);
        when(checkpoint.planState()).thenReturn(state);
        var states = new LinkedHashMap<PlanStepId, StepExecutionState>();
        states.put(stepId, StepExecutionState.NOT_STARTED);
        when(checkpoint.stepStates()).thenReturn(states);
        when(checkpoint.receiptReferences()).thenReturn(List.of());
        when(checkpoint.createdAt()).thenReturn(createdAt);
        return checkpoint;
    }

    private record ValidCommitted(
            PersistedExecutionStartCommitted committed,
            Plan bootstrapPlan,
            EventEnvelope startEvent,
            Checkpoint startedCheckpoint) {
    }
}
