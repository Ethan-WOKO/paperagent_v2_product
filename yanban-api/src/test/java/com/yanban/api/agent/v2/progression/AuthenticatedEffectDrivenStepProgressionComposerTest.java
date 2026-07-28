package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.execution.activation.composition
        .StepActivationLeaseDisposition;
import io.paperagent.v2.runtime.execution.activation.composition
        .StepActivationLeaseRejected;
import io.paperagent.v2.runtime.execution.activation.composition
        .StepActivationPersistenceRejected;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition
        .StepRecoveryLeaseRejected;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedEffectDrivenStepProgressionComposerTest {
    @Test
    void wrongPlanFailsBeforeInspectOrWrite() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        var command = new EffectDrivenStepProgressionCommand(
                new PlanId("wrong-plan"), fixture.command().toolCallId(),
                fixture.command().currentStepRecoveryAttempt(),
                fixture.command().nextStepActivationAttempt());

        var failure = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(7L, 42L, command));

        assertEquals("command.planId", failure.path());
        verify(fixture.inspector, never()).inspect(any());
        verify(fixture.completion, never()).compose(any());
    }

    @Test
    void missingOutcomeFailsBeforeRecoveryOrWrite() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.outcomes.findResult(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.rejected(
                        io.paperagent.v2.persistence.PersistenceErrorCode
                                .NOT_FOUND,
                        "missing"));

        var failure = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        assertEquals("effectOutcome", failure.path());
        verify(fixture.recoverer, never()).recover(any());
        verify(fixture.completion, never()).compose(any());
    }

    @Test
    void failedReceiptNeverCompletesStep() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        ExecutionReceipt failed = new ExecutionReceipt(
                fixture.receipt.id(), fixture.receipt.toolCallId(),
                ReceiptStatus.FAILURE,
                fixture.receipt.startedAt(), fixture.receipt.endedAt(),
                Optional.of(1), Optional.of("FAILED"),
                fixture.receipt.standardOutput(),
                fixture.receipt.standardError(),
                fixture.receipt.artifactReferences(),
                fixture.receipt.resultingDiff(),
                fixture.receipt.eventReferences());
        when(fixture.outcomes.findResult(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(
                        new PersistedEffectResult(
                                failed, fixture.result.leaseOwnerId(),
                                fixture.result.fencingToken())));

        var failure = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        assertEquals("effectEvidence.authority", failure.path());
        verify(fixture.inspector, never()).inspect(any());
        verify(fixture.completion, never()).compose(any());
    }

    @Test
    void mismatchedEffectFenceFailsBeforeInspection() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.outcomes.findResult(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(
                        new PersistedEffectResult(
                                fixture.receipt,
                                fixture.intent.leaseOwnerId(),
                                fixture.intent.fencingToken() + 1)));

        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        verify(fixture.inspector, never()).inspect(any());
    }

    @Test
    void interruptedOrCorruptInspectionFailsWithoutWrite() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.inspector.inspect(fixture.planId)).thenReturn(
                PersistenceResult.rejected(
                        io.paperagent.v2.persistence.PersistenceErrorCode
                                .STEP_RECOVERY_NOT_ELIGIBLE,
                        "interrupted"));

        var failure = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        assertEquals("progression.inspection", failure.path());
        verify(fixture.completion, never()).compose(any());
        verify(fixture.activation, never()).composeReady(any());
    }

    @Test
    void wrongUserOrTurnFailsBeforeReadingEffectOrWriting() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.contexts.resolve(8L, 99L))
                .thenThrow(new IllegalArgumentException("not owner"));

        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.composer.progress(
                        8L, 99L, fixture.command()));

        verify(fixture.intents, never()).find(any());
        verify(fixture.outcomes, never()).findResult(any());
        verify(fixture.completion, never()).compose(any());
        verify(fixture.activation, never()).composeReady(any());
    }

    @Test
    void wrongToolCallStepOrActivationBindingFailsBeforeInspection() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        ToolCallId wrongCall = new ToolCallId("wrong-call");
        var wrongCallCommand = new EffectDrivenStepProgressionCommand(
                fixture.planId, wrongCall,
                fixture.command().currentStepRecoveryAttempt(),
                fixture.command().nextStepActivationAttempt());
        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, wrongCallCommand));

        when(fixture.intents.find(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(
                        changedIntent(
                                fixture, new PlanStepId("wrong-step"),
                                fixture.intent.activationEventId())));
        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        when(fixture.intents.find(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(
                        changedIntent(
                                fixture,
                                fixture.intent.intent().stepId(),
                                new EventId("wrong-activation"))));
        fixture.inspections(fixture.activeA);
        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        verify(fixture.completion, never()).compose(any());
    }

    @Test
    void corruptReceiptToolCallFailsBeforeInspection() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        ExecutionReceipt corrupt = new ExecutionReceipt(
                fixture.receipt.id(), new ToolCallId("wrong-call"),
                fixture.receipt.status(), fixture.receipt.startedAt(),
                fixture.receipt.endedAt(), fixture.receipt.exitCode(),
                fixture.receipt.resultCode(),
                fixture.receipt.standardOutput(),
                fixture.receipt.standardError(),
                fixture.receipt.artifactReferences(),
                fixture.receipt.resultingDiff(),
                fixture.receipt.eventReferences());
        when(fixture.outcomes.findResult(fixture.command().toolCallId()))
                .thenReturn(PersistenceResult.found(
                        new PersistedEffectResult(
                                corrupt, fixture.result.leaseOwnerId(),
                                fixture.result.fencingToken())));

        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        verify(fixture.inspector, never()).inspect(any());
    }

    @Test
    void invalidCurrentLeaseFailsBeforeCompletion() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        fixture.inspections(fixture.activeA, fixture.activeA);
        when(fixture.recoverer.recover(any())).thenReturn(
                new StepRecoveryLeaseRejected(
                        fixture.planId,
                        new io.paperagent.v2.persistence.PersistenceFailure(
                                io.paperagent.v2.persistence
                                        .PersistenceErrorCode.LEASE_HELD,
                                "lease"),
                        StepRecoveryLeaseDisposition.NOT_ACQUIRED));

        var failure = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        assertEquals("recovery.activeStep", failure.path());
        verify(fixture.completion, never()).compose(any());
        verify(fixture.activation, never()).composeReady(any());
    }

    @Test
    void invalidNextLeaseCannotCreateActivation() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        fixture.inspections(fixture.readyB, fixture.readyB);
        when(fixture.activation.composeReady(any())).thenReturn(
                new StepActivationLeaseRejected(
                        fixture.planId,
                        new io.paperagent.v2.persistence.PersistenceFailure(
                                io.paperagent.v2.persistence
                                        .PersistenceErrorCode.LEASE_HELD,
                                "lease"),
                        StepActivationLeaseDisposition.NOT_ACQUIRED));

        var failure = assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));

        assertEquals("activation.persistence", failure.path());
        verify(fixture.completion, never()).compose(any());
    }

    @Test
    void replannedCutFailsClosedBeforeEitherPhase() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.inspector.inspect(fixture.planId)).thenReturn(
                PersistenceResult.rejected(
                        io.paperagent.v2.persistence.PersistenceErrorCode
                                .STEP_RECOVERY_NOT_ELIGIBLE,
                        "replanned"));

        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        verify(fixture.recoverer, never()).recover(any());
        verify(fixture.completion, never()).compose(any());
        verify(fixture.activation, never()).composeReady(any());
    }

    @Test
    void interruptionAfterStaleActiveInspectionPreventsCompletion() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.inspector.inspect(fixture.planId))
                .thenReturn(PersistenceResult.found(fixture.activeA))
                .thenReturn(PersistenceResult.rejected(
                        io.paperagent.v2.persistence.PersistenceErrorCode
                                .STEP_RECOVERY_NOT_ELIGIBLE,
                        "interrupted"));
        when(fixture.recoverer.recover(any())).thenReturn(
                new StepRecoveryLeaseRejected(
                        fixture.planId,
                        new io.paperagent.v2.persistence.PersistenceFailure(
                                io.paperagent.v2.persistence
                                        .PersistenceErrorCode
                                        .STEP_RECOVERY_NOT_ELIGIBLE,
                                "interrupted"),
                        StepRecoveryLeaseDisposition.NOT_ACQUIRED));

        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        verify(fixture.completion, never()).compose(any());
        verify(fixture.activation, never()).composeReady(any());
    }

    @Test
    void replanAfterStaleReadyInspectionCannotAuthorizeActivation() {
        var fixture = new EffectDrivenStepProgressionTestFixtures();
        when(fixture.inspector.inspect(fixture.planId))
                .thenReturn(PersistenceResult.found(fixture.readyB))
                .thenReturn(PersistenceResult.rejected(
                        io.paperagent.v2.persistence.PersistenceErrorCode
                                .STEP_RECOVERY_NOT_ELIGIBLE,
                        "replanned"));
        when(fixture.activation.composeReady(any())).thenReturn(
                new StepActivationPersistenceRejected(
                        fixture.planId,
                        new io.paperagent.v2.persistence.PersistenceFailure(
                                io.paperagent.v2.persistence
                                        .PersistenceErrorCode
                                        .STEP_ACTIVATION_NOT_ELIGIBLE,
                                "replanned"),
                        StepActivationLeaseDisposition
                                .RETAINED_FOR_RECOVERY));

        assertThrows(
                EffectDrivenStepProgressionException.class,
                () -> fixture.composer.progress(
                        7L, 42L, fixture.command()));
        verify(fixture.completion, never()).compose(any());
        verify(fixture.activation).composeReady(any());
    }

    private static PersistedEffectIntent changedIntent(
            EffectDrivenStepProgressionTestFixtures fixture,
            PlanStepId stepId,
            EventId activationId) {
        return new PersistedEffectIntent(
                new EffectIntent(
                        fixture.intent.intent().toolCallId(),
                        fixture.intent.intent().planId(),
                        stepId,
                        fixture.intent.intent().kind(),
                        new ObjectValue(Map.of())),
                fixture.intent.leaseOwnerId(),
                fixture.intent.fencingToken(),
                activationId);
    }
}
