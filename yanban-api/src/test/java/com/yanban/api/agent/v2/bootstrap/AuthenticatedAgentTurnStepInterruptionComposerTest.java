package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionComposer;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCompositionOutcome;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCompositionProtocolException;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionLeaseDisposition;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionPersistenceRejected;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionEventDraft;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryPersistenceRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryProtocolException;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryStage;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnStepInterruptionComposerTest {
    private static final Instant EVENT_TIME =
            Instant.parse("2099-07-28T10:11:00Z");
    private static final Instant CHECKPOINT_TIME =
            Instant.parse("2099-07-28T10:12:00Z");

    private AgentTurnProductContextResolver contexts;
    private ProductPlanIdDerivation planIds;
    private StepRecoverer recoverer;
    private ActiveStepInterruptionComposer interruptions;
    private AuthenticatedAgentTurnStepInterruptionComposer composer;
    private PlanId planId;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        planIds = spy(new ProductPlanIdDerivation());
        recoverer = mock(StepRecoverer.class);
        interruptions = mock(ActiveStepInterruptionComposer.class);
        composer = new AuthenticatedAgentTurnStepInterruptionComposer(
                contexts, planIds, recoverer, interruptions);
        var context =
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext();
        planId = new ProductPlanIdDerivation().derive(context.identity());
        when(contexts.resolve(7L, 42L)).thenReturn(context);
    }

    @Test
    void resolvesDerivesRecoversThenInterruptsExactlyOnceForEveryKind() {
        for (StepInterruptionKind kind : StepInterruptionKind.values()) {
            reset(recoverer, interruptions);
            RecoveredActiveStep recovered = recovered(planId);
            ActiveStepInterruptionCompositionOutcome stable =
                    stableInterruption(planId);
            when(recoverer.recover(any())).thenReturn(recovered);
            when(interruptions.compose(any())).thenReturn(stable);
            var command = command(kind);

            AuthenticatedAgentTurnStepInterrupted result =
                    (AuthenticatedAgentTurnStepInterrupted)
                            composer.interrupt(7L, 42L, command);

            assertSame(stable, result.interruption());
            assertSame(stable.leaseDisposition(), result.leaseDisposition());
            ArgumentCaptor<StepRecoveryRequest> recoveryRequest =
                    ArgumentCaptor.forClass(StepRecoveryRequest.class);
            ArgumentCaptor<ActiveStepInterruptionMaterializationRequest>
                    interruptionRequest = ArgumentCaptor.forClass(
                            ActiveStepInterruptionMaterializationRequest.class);
            var order = inOrder(contexts, planIds, recoverer, interruptions);
            order.verify(contexts).resolve(7L, 42L);
            order.verify(planIds).derive(any());
            order.verify(recoverer).recover(recoveryRequest.capture());
            order.verify(interruptions).compose(interruptionRequest.capture());
            assertEquals(planId, recoveryRequest.getValue().planId());
            assertSame(
                    command.recoveryAttempt(),
                    recoveryRequest.getValue().leaseAttempt());
            assertSame(
                    recovered,
                    interruptionRequest.getValue().recoveredActiveStep());
            assertSame(kind, interruptionRequest.getValue().kind());
            assertSame(
                    command.eventDraft(),
                    interruptionRequest.getValue().eventDraft());
            assertSame(
                    command.checkpointCreatedAt(),
                    interruptionRequest.getValue().checkpointCreatedAt());
            verify(recoverer, times(1)).recover(any());
            verify(interruptions, times(1)).compose(any());
            reset(contexts, planIds);
            var context =
                    AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                            .workspaceContext();
            when(contexts.resolve(7L, 42L)).thenReturn(context);
        }
    }

    @Test
    void bothNonRecoveredOutcomesArePreservedAndShortCircuitInterruption() {
        StepRecoveryCompositionOutcome[] outcomes = {
                new StepRecoveryLeaseRejected(
                        planId,
                        new PersistenceFailure(
                                PersistenceErrorCode.LEASE_HELD, "lease"),
                        StepRecoveryLeaseDisposition.NOT_ACQUIRED),
                new StepRecoveryPersistenceRejected(
                        planId,
                        StepRecoveryStage.POST_LEASE_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode
                                        .STEP_RECOVERY_NOT_ELIGIBLE,
                                "stepRecovery"),
                        StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY)
        };
        for (StepRecoveryCompositionOutcome outcome : outcomes) {
            reset(recoverer, interruptions);
            when(recoverer.recover(any())).thenReturn(outcome);
            var result = (AuthenticatedAgentTurnStepInterruptionRecoveryRejected)
                    composer.interrupt(
                            7L, 42L, command(StepInterruptionKind.PAUSE));
            assertSame(outcome, result.recovery());
            assertSame(outcome.leaseDisposition(), result.leaseDisposition());
            verify(recoverer, times(1)).recover(any());
            verifyNoInteractions(interruptions);
        }
    }

    @Test
    void ownershipAndDerivationFailuresPropagateBeforeCommandOrRecovery() {
        AgentTurnProductContextResolutionException ownership =
                mock(AgentTurnProductContextResolutionException.class);
        when(contexts.resolve(7L, 404L)).thenThrow(ownership);
        assertSame(ownership, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.interrupt(7L, 404L, null)));
        verifyNoInteractions(recoverer, interruptions);

        reset(contexts, planIds);
        var context =
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        IllegalArgumentException derivation =
                new IllegalArgumentException("derivation");
        org.mockito.Mockito.doThrow(derivation)
                .when(planIds).derive(context.identity());
        assertSame(derivation, assertThrows(
                IllegalArgumentException.class,
                () -> composer.interrupt(7L, 42L, null)));
        verifyNoInteractions(recoverer, interruptions);
    }

    @Test
    void validatesCommandOnlyAfterResolverAndDerivation() {
        var commands = new AuthenticatedAgentTurnStepInterruptionCommand[]{
                null,
                new AuthenticatedAgentTurnStepInterruptionCommand(
                        null, StepInterruptionKind.PAUSE, eventDraft(),
                        CHECKPOINT_TIME),
                new AuthenticatedAgentTurnStepInterruptionCommand(
                        attempt(), null, eventDraft(), CHECKPOINT_TIME),
                new AuthenticatedAgentTurnStepInterruptionCommand(
                        attempt(), StepInterruptionKind.PAUSE, null,
                        CHECKPOINT_TIME),
                new AuthenticatedAgentTurnStepInterruptionCommand(
                        attempt(), StepInterruptionKind.PAUSE, eventDraft(),
                        null)
        };
        String[] paths = {
                "authenticatedStepInterruption.command",
                "authenticatedStepInterruption.command.recoveryAttempt",
                "authenticatedStepInterruption.command.kind",
                "authenticatedStepInterruption.command.eventDraft",
                "authenticatedStepInterruption.command.checkpointCreatedAt"
        };
        for (int index = 0; index < commands.length; index++) {
            AuthenticatedAgentTurnStepInterruptionCommand invalid =
                    commands[index];
            var failure = assertFailure(
                    () -> composer.interrupt(7L, 42L, invalid),
                    AuthenticatedAgentTurnStepInterruptionCompositionCode
                            .REQUIRED_VALUE_MISSING);
            assertEquals(paths[index], failure.path());
        }
        verify(contexts, times(commands.length)).resolve(7L, 42L);
        verify(planIds, times(commands.length)).derive(any());
        verifyNoInteractions(recoverer, interruptions);
    }

    @Test
    void nullMalformedMismatchedAndExceptionalRecoveryFailClosedWithoutRetry() {
        when(recoverer.recover(any())).thenReturn(null);
        assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INVALID_RECOVERY_RESULT);

        reset(recoverer);
        StepRecoveryLeaseRejected malformed =
                mock(StepRecoveryLeaseRejected.class);
        when(malformed.planId()).thenThrow(
                new IllegalStateException("secret-token"));
        when(recoverer.recover(any())).thenReturn(malformed);
        var malformedFailure = assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INVALID_RECOVERY_RESULT);
        assertFalse(malformedFailure.toString().contains("secret-token"));

        reset(recoverer);
        when(recoverer.recover(any())).thenReturn(
                new StepRecoveryLeaseRejected(
                        new PlanId("other-plan"),
                        new PersistenceFailure(
                                PersistenceErrorCode.LEASE_HELD, "lease"),
                        StepRecoveryLeaseDisposition.NOT_ACQUIRED));
        assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .RECOVERY_PLAN_MISMATCH);

        reset(recoverer);
        when(recoverer.recover(any())).thenThrow(
                new IllegalStateException("secret-owner"));
        var collaboratorFailure = assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .RECOVERY_COLLABORATOR_FAILURE);
        assertFalse(collaboratorFailure.toString().contains("secret-owner"));
        assertFalse(String.valueOf(collaboratorFailure.getCause())
                .contains("secret-owner"));
        verify(recoverer, times(1)).recover(any());
        verifyNoInteractions(interruptions);
    }

    @Test
    void stableRecoveryFailuresPropagateUnchanged() {
        RuntimeException[] failures = {
                mock(StepRecoveryValidationException.class),
                mock(StepRecoveryProtocolException.class)
        };
        for (RuntimeException failure : failures) {
            reset(recoverer);
            when(recoverer.recover(any())).thenThrow(failure);
            assertSame(failure, assertThrows(
                    failure.getClass(),
                    () -> composer.interrupt(
                            7L, 42L, command(StepInterruptionKind.FAIL))));
            verify(recoverer, times(1)).recover(any());
            verifyNoInteractions(interruptions);
        }
    }

    @Test
    void nullMismatchedAndExceptionalInterruptionFailClosedWithoutRetry() {
        RecoveredActiveStep recovered = recovered(planId);
        when(recoverer.recover(any())).thenReturn(recovered);
        when(interruptions.compose(any())).thenReturn(null);
        assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INVALID_INTERRUPTION_RESULT);

        reset(interruptions);
        ActiveStepInterruptionPersistenceRejected malformed =
                mock(ActiveStepInterruptionPersistenceRejected.class);
        when(malformed.planId()).thenReturn(planId);
        when(malformed.leaseDisposition()).thenReturn(null);
        when(interruptions.compose(any())).thenReturn(malformed);
        assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INVALID_INTERRUPTION_RESULT);

        reset(interruptions);
        when(interruptions.compose(any())).thenReturn(
                stableInterruption(new PlanId("other-plan")));
        assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INTERRUPTION_PLAN_MISMATCH);

        reset(interruptions);
        when(interruptions.compose(any())).thenThrow(
                new IllegalStateException("event-secret-payload"));
        var failure = assertCode(
                AuthenticatedAgentTurnStepInterruptionCompositionCode
                        .INTERRUPTION_COLLABORATOR_FAILURE);
        assertFalse(failure.toString().contains("event-secret-payload"));
        assertFalse(String.valueOf(failure.getCause())
                .contains("event-secret-payload"));
        verify(interruptions, times(1)).compose(any());
    }

    @Test
    void stableCoreFailurePropagatesUnchanged() {
        RecoveredActiveStep recovered = recovered(planId);
        when(recoverer.recover(any())).thenReturn(recovered);
        var stable = mock(
                ActiveStepInterruptionCompositionProtocolException.class);
        when(interruptions.compose(any())).thenThrow(stable);
        assertSame(stable, assertThrows(
                ActiveStepInterruptionCompositionProtocolException.class,
                () -> composer.interrupt(
                        7L, 42L, command(StepInterruptionKind.CANCEL))));
        verify(interruptions, times(1)).compose(any());
    }

    @Test
    void commandOutcomeAndDiagnosticsExposeNoAuthorityOrPayload() {
        var command = command(StepInterruptionKind.FAIL);
        String diagnostics = command.toString();
        assertFalse(diagnostics.contains("secret-owner"));
        assertFalse(diagnostics.contains("secret-token"));
        assertFalse(diagnostics.contains("secret-payload"));
        assertArrayEquals(
                new String[]{
                        "recoveryAttempt",
                        "kind",
                        "eventDraft",
                        "checkpointCreatedAt"
                },
                Arrays.stream(
                                AuthenticatedAgentTurnStepInterruptionCommand
                                        .class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        assertEquals(
                Set.of(
                        AuthenticatedAgentTurnStepInterruptionRecoveryRejected
                                .class,
                        AuthenticatedAgentTurnStepInterrupted.class),
                Arrays.stream(
                                AuthenticatedAgentTurnStepInterruptionOutcome
                                        .class.getPermittedSubclasses())
                        .collect(Collectors.toSet()));
        assertTrue(Arrays.stream(
                        AuthenticatedAgentTurnStepInterruptionComposer.class
                                .getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(
                        field.getModifiers()))
                .map(java.lang.reflect.Field::getType)
                .collect(Collectors.toSet())
                .equals(Set.of(
                        AgentTurnProductContextResolver.class,
                        ProductPlanIdDerivation.class,
                        StepRecoverer.class,
                        ActiveStepInterruptionComposer.class)));
    }

    private AuthenticatedAgentTurnStepInterruptionCompositionException
            assertCode(
                    AuthenticatedAgentTurnStepInterruptionCompositionCode code) {
        return assertFailure(
                () -> composer.interrupt(
                        7L, 42L, command(StepInterruptionKind.PAUSE)),
                code);
    }

    private static AuthenticatedAgentTurnStepInterruptionCompositionException
            assertFailure(
                    org.junit.jupiter.api.function.Executable executable,
                    AuthenticatedAgentTurnStepInterruptionCompositionCode code) {
        var failure = assertThrows(
                AuthenticatedAgentTurnStepInterruptionCompositionException.class,
                executable);
        assertEquals(code, failure.code());
        return failure;
    }

    static AuthenticatedAgentTurnStepInterruptionCommand command(
            StepInterruptionKind kind) {
        return new AuthenticatedAgentTurnStepInterruptionCommand(
                attempt(), kind, eventDraft(), CHECKPOINT_TIME);
    }

    private static io.paperagent.v2.runtime.execution.recovery.composition
            .StepRecoveryLeaseAttempt attempt() {
        return new io.paperagent.v2.runtime.execution.recovery.composition
                .StepRecoveryLeaseAttempt(
                        "secret-owner",
                        "secret-token",
                        Instant.parse("2099-07-27T10:10:00Z"));
    }

    private static ActiveStepInterruptionEventDraft eventDraft() {
        return new ActiveStepInterruptionEventDraft(
                new EventId("event-id"),
                EVENT_TIME,
                new EventType("step-interruption"),
                Optional.empty(),
                "correlation",
                new InlineEventPayload(new ObjectValue(
                        Map.of("message",
                                new io.paperagent.v2.contracts.TextValue(
                                        "secret-payload")))));
    }

    private static RecoveredActiveStep recovered(PlanId planId) {
        RecoveredActiveStep recovered = mock(RecoveredActiveStep.class);
        PersistedStepRecoveryActive snapshot =
                mock(PersistedStepRecoveryActive.class);
        LeaseRecord lease = mock(LeaseRecord.class);
        when(recovered.planId()).thenReturn(planId);
        when(recovered.recovery()).thenReturn(snapshot);
        when(recovered.lease()).thenReturn(lease);
        when(recovered.leaseDisposition()).thenReturn(
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        when(snapshot.planId()).thenReturn(planId);
        when(lease.planId()).thenReturn(planId);
        return recovered;
    }

    private static ActiveStepInterruptionCompositionOutcome
            stableInterruption(PlanId planId) {
        return new ActiveStepInterruptionPersistenceRejected(
                planId,
                new PersistenceFailure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "interruption"),
                ActiveStepInterruptionLeaseDisposition.RETAINED_FOR_RECOVERY);
    }
}
