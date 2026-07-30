package com.yanban.api.agent.v2.loop;

import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionState;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionException;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import com.yanban.api.agent.v2.progression.EffectDrivenStepProgressionState;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationCommitted;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnPersistenceRejected;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelProtocolCode;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelProtocolException;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelStage;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopNoEffect;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.replan.composition.BoundedStepReplanApplied;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import static com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopTestSupport.TURN_ID;
import static com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopTestSupport.USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedPersistentPlanAgentLoopComposerTest {
    @Test
    void terminalCutReturnsWithoutProviderToolOrWrites() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        PersistedStepRecoverySucceeded succeeded =
                mock(PersistedStepRecoverySucceeded.class);
        when(succeeded.planId()).thenReturn(fixture.planId());
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(succeeded));

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(3));

        assertEquals(PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                outcome.state());
        assertEquals(0, outcome.cyclesAttempted());
        assertEquals(PersistentPlanAgentLoopCutKind.SUCCEEDED,
                outcome.cut().orElseThrow().kind());
        verifyNoInteractions(
                fixture.recoverer(), fixture.activation(),
                fixture.kernel(), fixture.effects(),
                fixture.progression(), fixture.replans());
    }

    @Test
    void readyCutActivatesBeforeRecoveringAndRunningKernel() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        PlanStepId readyStepId = new PlanStepId("step-ready");
        PersistedStepRecoveryReady ready =
                mock(PersistedStepRecoveryReady.class);
        when(ready.planId()).thenReturn(fixture.planId());
        when(ready.readyStepId()).thenReturn(readyStepId);
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(ready));
        StepActivationCommitted committed =
                mock(StepActivationCommitted.class);
        when(fixture.activation().composeReady(any()))
                .thenReturn(committed);
        var active = PersistentPlanAgentLoopTestSupport.active(
                fixture.planId(), "step-ready");
        when(fixture.recoverer().recover(any()))
                .thenReturn(active.active());
        when(fixture.kernel().run(any())).thenReturn(
                new SingleTurnNoEffect(
                        fixture.planId(), active.stepId()));

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(1));

        assertEquals(PersistentPlanAgentLoopState.REPLAN_REQUIRED,
                outcome.state());
        assertEquals(1, outcome.cyclesAttempted());
        InOrder order = inOrder(
                fixture.activation(), fixture.recoverer(),
                fixture.kernel());
        order.verify(fixture.activation()).composeReady(any());
        order.verify(fixture.recoverer()).recover(any());
        order.verify(fixture.kernel()).run(any());
        verifyNoInteractions(
                fixture.effects(), fixture.progression(),
                fixture.replans());
    }

    @Test
    void unsupportedIntentStopsBeforeEffectOrProgression() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        var intent = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), active.stepId(), "unsupported",
                "paper.write");
        when(fixture.kernel().run(any())).thenReturn(intent.outcome());

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2));

        assertEquals(PersistentPlanAgentLoopState.UNSUPPORTED_INTENT,
                outcome.state());
        assertEquals(1, outcome.cyclesAttempted());
        assertEquals(active.stepId(), outcome.stepId().orElseThrow());
        verifyNoInteractions(
                fixture.effects(), fixture.progression());
    }

    @Test
    void kernelFailureIsSanitizedAndNeverReachesEffect() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        activeFixture(fixture, "step-a");
        when(fixture.kernel().run(any()))
                .thenThrow(new IllegalStateException(
                        "provider-secret-payload"));

        PersistentPlanAgentLoopException failure = assertThrows(
                PersistentPlanAgentLoopException.class,
                () -> fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2)));

        assertEquals("kernel", failure.stage());
        assertFalse(failure.toString().contains(
                "provider-secret-payload"));
        verifyNoInteractions(
                fixture.effects(), fixture.progression());
    }

    @Test
    void kernelProtocolFailureRetainsOnlyStableClassification() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        activeFixture(fixture, "step-a");
        SingleTurnStepKernelProtocolException kernelFailure =
                mock(SingleTurnStepKernelProtocolException.class);
        when(kernelFailure.stage()).thenReturn(
                SingleTurnStepKernelStage.TURN_DECISION);
        when(kernelFailure.code()).thenReturn(
                SingleTurnStepKernelProtocolCode.COLLABORATOR_EXCEPTION);
        when(kernelFailure.path()).thenReturn(
                "singleTurnStepKernel.turnDecision");
        when(fixture.kernel().run(any())).thenThrow(kernelFailure);

        PersistentPlanAgentLoopException failure = assertThrows(
                PersistentPlanAgentLoopException.class,
                () -> fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2)));

        assertEquals("kernel", failure.stage());
        assertEquals(
                "kernel.turn_decision.collaborator_exception",
                failure.diagnosticStage());
        assertEquals(
                SingleTurnStepKernelStage.TURN_DECISION,
                failure.kernelStage().orElseThrow());
        assertEquals(
                SingleTurnStepKernelProtocolCode.COLLABORATOR_EXCEPTION,
                failure.kernelCode().orElseThrow());
        assertEquals(
                "singleTurnStepKernel.turnDecision",
                failure.kernelPath().orElseThrow());
        assertFalse(failure.toString().contains(
                "provider-secret-payload"));
        verifyNoInteractions(
                fixture.effects(), fixture.progression());
    }

    @Test
    void wrongKernelAuthorityFailsClosed() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        when(fixture.kernel().run(any())).thenReturn(
                new SingleTurnNoEffect(
                        new PlanId("other-plan"), active.stepId()));

        PersistentPlanAgentLoopException failure = assertThrows(
                PersistentPlanAgentLoopException.class,
                () -> fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2)));

        assertEquals("kernelAuthority", failure.stage());
        verifyNoInteractions(
                fixture.effects(), fixture.progression());
    }

    @Test
    void invalidRecoveryLeaseRetainsTypedFailureAndStops() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = PersistentPlanAgentLoopTestSupport.active(
                fixture.planId(), "step-a");
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(
                        active.recovery()));
        PersistenceFailure leaseFailure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_EXPIRED, "lease");
        StepRecoveryLeaseRejected rejected =
                mock(StepRecoveryLeaseRejected.class);
        when(rejected.planId()).thenReturn(fixture.planId());
        when(rejected.failure()).thenReturn(leaseFailure);
        when(fixture.recoverer().recover(any()))
                .thenReturn(rejected);

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2));

        assertEquals(PersistentPlanAgentLoopState.RECOVERY_REJECTED,
                outcome.state());
        assertEquals(0, outcome.cyclesAttempted());
        assertSame(leaseFailure, outcome.failure().orElseThrow());
        verifyNoInteractions(
                fixture.kernel(), fixture.effects(),
                fixture.progression());
    }

    @Test
    void kernelPersistenceRejectionIsExplicit() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        PersistenceFailure persistenceFailure = new PersistenceFailure(
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent");
        when(fixture.kernel().run(any())).thenReturn(
                new SingleTurnPersistenceRejected(
                        fixture.planId(), active.stepId(),
                        persistenceFailure));

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2));

        assertEquals(
                PersistentPlanAgentLoopState
                        .KERNEL_PERSISTENCE_REJECTED,
                outcome.state());
        assertSame(persistenceFailure,
                outcome.failure().orElseThrow());
        verifyNoInteractions(
                fixture.effects(), fixture.progression());
    }

    @Test
    void missingEffectIsRejectedBeforeProgression() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        var intent = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), active.stepId(), "a",
                "literature.search");
        when(fixture.kernel().run(any())).thenReturn(intent.outcome());
        when(fixture.effects().execute(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(null);

        PersistentPlanAgentLoopException failure = assertThrows(
                PersistentPlanAgentLoopException.class,
                () -> fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2)));

        assertEquals("effect", failure.stage());
        verifyNoInteractions(fixture.progression());
    }

    @Test
    void invalidProgressionIsExplicitlyRejected() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        var intent = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), active.stepId(), "a",
                "literature.search");
        when(fixture.kernel().run(any())).thenReturn(intent.outcome());
        var effect = PersistentPlanAgentLoopTestSupport
                .successfulEffect(intent.toolCallId());
        when(fixture.effects().execute(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(effect);
        when(fixture.progression().progress(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(null);

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2));

        assertEquals(
                PersistentPlanAgentLoopState.PROGRESSION_REJECTED,
                outcome.state());
        assertEquals(1, outcome.cyclesAttempted());
    }

    @Test
    void failedReceiptIsReturnedForReflectionWithoutProgression() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        var intent = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), active.stepId(), "a",
                "literature.search");
        when(fixture.kernel().run(any())).thenReturn(intent.outcome());
        var receipt = mock(io.paperagent.v2.contracts.ExecutionReceipt.class);
        when(receipt.toolCallId()).thenReturn(intent.toolCallId());
        when(receipt.status()).thenReturn(
                io.paperagent.v2.contracts.ReceiptStatus.FAILURE);
        when(receipt.resultCode()).thenReturn(
                java.util.Optional.of("TOOL_FAILED"));
        var persisted = mock(io.paperagent.v2.persistence
                .PersistedEffectResult.class);
        when(persisted.receipt()).thenReturn(receipt);
        when(fixture.effects().execute(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(new com.yanban.api.agent.v2.effect
                        .AuthenticatedLiteratureSearchEffectExecutionOutcome(
                                persisted, false));

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2));

        assertEquals(PersistentPlanAgentLoopState.EFFECT_REJECTED,
                outcome.state());
        assertEquals("FAILURE",
                outcome.receiptFacts().orElseThrow().status());
        assertEquals(java.util.Optional.of("TOOL_FAILED"),
                outcome.receiptFacts().orElseThrow().resultCode());
        verifyNoInteractions(fixture.progression());
    }

    @Test
    void progressionFailureRetainsOnlyItsStablePath() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        var intent = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), active.stepId(), "a",
                "literature.search");
        when(fixture.kernel().run(any())).thenReturn(intent.outcome());
        var effect = PersistentPlanAgentLoopTestSupport
                .successfulEffect(intent.toolCallId());
        when(fixture.effects().execute(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(effect);
        EffectDrivenStepProgressionException rejection =
                mock(EffectDrivenStepProgressionException.class);
        when(rejection.path()).thenReturn("effect.fence");
        when(fixture.progression().progress(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenThrow(rejection);

        PersistentPlanAgentLoopException failure = assertThrows(
                PersistentPlanAgentLoopException.class,
                () -> fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(2)));

        assertEquals("progression.effect.fence",
                failure.diagnosticStage());
    }

    @Test
    void hardCycleLimitWithProposalExecutesCurrentEffectButNeverReplans() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        var intent = PersistentPlanAgentLoopTestSupport.intent(
                fixture.planId(), active.stepId(), "a",
                "literature.search");
        when(fixture.kernel().run(any())).thenReturn(intent.outcome());
        var effect = PersistentPlanAgentLoopTestSupport
                .successfulEffect(intent.toolCallId());
        when(fixture.effects().execute(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(effect);
        var nextActive = PersistentPlanAgentLoopTestSupport.active(
                fixture.planId(), "step-b");
        var progressed = PersistentPlanAgentLoopTestSupport.progression(
                fixture.planId(), active.stepId(),
                EffectDrivenStepProgressionState.NEXT_STEP_ACTIVE,
                nextActive.recovery());
        when(fixture.progression().progress(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any()))
                .thenReturn(progressed);
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(active.recovery()))
                .thenReturn(PersistenceResult.found(
                        nextActive.recovery()));
        ActiveStepReplanRequest proposal =
                mock(ActiveStepReplanRequest.class);

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(
                                1, proposal));

        assertEquals(PersistentPlanAgentLoopState.REPLAN_REQUIRED,
                outcome.state());
        assertEquals(1, outcome.cyclesAttempted());
        assertTrue(outcome.replan().isEmpty());
        assertEquals(nextActive.stepId(),
                outcome.stepId().orElseThrow());
        verify(fixture.effects()).execute(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any());
        verify(fixture.progression()).progress(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(TURN_ID), any());
        verifyNoInteractions(proposal);
        verifyNoInteractions(fixture.replans());
        verify(fixture.activation(), never()).composeReady(any());
    }

    @Test
    void genuineNoEffectWithProposalAppliesReplanWithoutEffectDispatch() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        var active = activeFixture(fixture, "step-a");
        when(fixture.kernel().run(any())).thenReturn(
                new SingleTurnNoEffect(
                        fixture.planId(), active.stepId()));
        ActiveStepReplanRequest proposal =
                mock(ActiveStepReplanRequest.class);
        PersistedActiveStepReplan persisted =
                mock(PersistedActiveStepReplan.class);
        EventEnvelope supersession = mock(EventEnvelope.class);
        EventEnvelope replan = mock(EventEnvelope.class);
        PlanRevision revision = mock(PlanRevision.class);
        VersionedCheckpoint superseded =
                mock(VersionedCheckpoint.class);
        VersionedCheckpoint replacement =
                mock(VersionedCheckpoint.class);
        when(persisted.supersededStepId()).thenReturn(active.stepId());
        when(persisted.supersessionEvent()).thenReturn(supersession);
        when(persisted.replanEvent()).thenReturn(replan);
        when(persisted.replannedRevision()).thenReturn(revision);
        when(persisted.supersededCheckpoint()).thenReturn(superseded);
        when(persisted.replannedCheckpoint()).thenReturn(replacement);
        when(supersession.id()).thenReturn(
                new EventId("supersession-event"));
        when(replan.id()).thenReturn(new EventId("replan-event"));
        when(revision.id()).thenReturn(
                new io.paperagent.v2.contracts.PlanRevisionId(
                        "replacement-revision"));
        when(superseded.version()).thenReturn(4L);
        when(replacement.version()).thenReturn(5L);
        PersistedStepRecoveryReady ready =
                mock(PersistedStepRecoveryReady.class);
        when(ready.planId()).thenReturn(fixture.planId());
        when(ready.readyStepId()).thenReturn(
                new PlanStepId("replacement-step"));
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(active.recovery()))
                .thenReturn(PersistenceResult.found(ready));
        when(fixture.replans().composeNoEffect(
                org.mockito.ArgumentMatchers.eq(active.active()),
                any(), org.mockito.ArgumentMatchers.eq(proposal)))
                .thenReturn(new BoundedStepReplanApplied(persisted));

        PersistentPlanAgentLoopOutcome outcome =
                fixture.composer().execute(
                        USER_ID, TURN_ID,
                        PersistentPlanAgentLoopTestSupport.command(
                                1, proposal));

        assertEquals(PersistentPlanAgentLoopState.REPLAN_APPLIED,
                outcome.state());
        assertEquals(PersistentPlanAgentLoopCutKind.READY,
                outcome.cut().orElseThrow().kind());
        assertEquals(new EventId("replan-event"),
                outcome.replan().orElseThrow().replanEventId());
        ArgumentCaptor<BoundedStepAgentLoopNoEffect> stall =
                ArgumentCaptor.forClass(
                        BoundedStepAgentLoopNoEffect.class);
        verify(fixture.replans()).composeNoEffect(
                org.mockito.ArgumentMatchers.eq(active.active()),
                stall.capture(),
                org.mockito.ArgumentMatchers.eq(proposal));
        assertEquals(1, stall.getValue().turnsExecuted());
        assertTrue(stall.getValue().persistedIntents().isEmpty());
        verifyNoInteractions(fixture.effects(), fixture.progression());
    }

    @Test
    void replanFactoryExceptionHasStableStage() {
        var fixture = PersistentPlanAgentLoopTestSupport.fixture();
        activeFixture(fixture, "step-a");
        when(fixture.kernel().run(any())).thenReturn(
                new SingleTurnNoEffect(
                        fixture.planId(), new PlanStepId("step-a")));

        PersistentPlanAgentLoopException failure = assertThrows(
                PersistentPlanAgentLoopException.class,
                () -> fixture.composer()
                        .executeWithKernelAndReplanFactory(
                                USER_ID, TURN_ID,
                                PersistentPlanAgentLoopTestSupport.command(1),
                                fixture.kernel(),
                                ignored -> {
                                    throw new IllegalArgumentException(
                                            "untrusted detail");
                                }));

        assertEquals("replanFactory", failure.diagnosticStage());
        assertFalse(failure.toString().contains("untrusted detail"));
    }

    private static PersistentPlanAgentLoopTestSupport.ActiveCut
            activeFixture(
                    PersistentPlanAgentLoopTestSupport.LoopFixture fixture,
                    String step) {
        var active = PersistentPlanAgentLoopTestSupport.active(
                fixture.planId(), step);
        when(fixture.inspections().inspect(fixture.planId()))
                .thenReturn(PersistenceResult.found(
                        active.recovery()));
        when(fixture.recoverer().recover(any()))
                .thenReturn(active.active());
        return active;
    }
}
