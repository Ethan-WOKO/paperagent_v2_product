package com.yanban.api.agent.v2.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import com.yanban.api.agent.v2.result.V2StepResultSource;
import com.yanban.api.agent.v2.result.V2StepResultStatus;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.activation.composition.StepActivationComposer;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionCommitted;
import io.paperagent.v2.runtime.execution.completion.composition.ActiveStepCompletionComposer;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.progression.StepProgressionInspector;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthenticatedResultDrivenStepProgressionComposerTest {
    @Test
    void acceptedReasoningResultCompletesWithoutForgingReceipt() {
        PlanId planId = new PlanId("plan-result");
        PlanStepId stepId = new PlanStepId("step-result");
        EventId activationId = new EventId("activation-result");
        String acceptedHash = "b".repeat(64);
        V2StepResultSnapshot result = new V2StepResultSnapshot(
                "step-result.accepted", planId,
                new PlanRevisionId("revision-result"), stepId,
                activationId, V2StepResultSource.MODEL,
                "analysis result", "a".repeat(64), List.of(),
                V2StepResultStatus.ACCEPTED,
                Optional.of("analysis result"),
                Optional.of(acceptedHash),
                Instant.parse("2026-08-01T00:00:02Z"),
                Instant.parse("2026-08-01T00:00:03Z"));

        AgentTurnProductContextResolver contexts =
                mock(AgentTurnProductContextResolver.class);
        VerifiedAgentTurnProductContext context =
                mock(VerifiedAgentTurnProductContext.class);
        when(contexts.resolve(7L, 11L)).thenReturn(context);
        ProductPlanIdDerivation planIds =
                mock(ProductPlanIdDerivation.class);
        when(planIds.derive(context.identity())).thenReturn(planId);
        StepProgressionInspector inspector =
                mock(StepProgressionInspector.class);
        StepRecoverer recoverer = mock(StepRecoverer.class);
        ActiveStepCompletionComposer completion =
                mock(ActiveStepCompletionComposer.class);
        StepActivationComposer activation =
                mock(StepActivationComposer.class);

        PersistedStepRecoveryActive current =
                active(planId, stepId, activationId);
        RecoveredActiveStep recovered = mock(RecoveredActiveStep.class);
        when(recovered.planId()).thenReturn(planId);
        when(recovered.recovery()).thenReturn(current);
        when(recovered.leaseDisposition()).thenReturn(
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        when(recoverer.recover(any())).thenReturn(recovered);
        when(completion.compose(any())).thenReturn(
                mock(ActiveStepCompletionCommitted.class));

        PersistedStepRecoverySucceeded succeeded =
                mock(PersistedStepRecoverySucceeded.class);
        Plan completedPlan = mock(Plan.class);
        PlanRevision completedRevision = mock(PlanRevision.class);
        when(succeeded.planId()).thenReturn(planId);
        when(succeeded.plan()).thenReturn(completedPlan);
        when(completedPlan.latestRevision())
                .thenReturn(completedRevision);
        when(completedRevision.completedFacts()).thenReturn(Map.of(
                stepId, new CompletionFact(
                        stepId, "sha256." + acceptedHash,
                        Instant.parse("2026-08-01T00:00:03Z"),
                        List.of())));
        when(inspector.inspect(planId)).thenReturn(
                PersistenceResult.found(current),
                PersistenceResult.found(succeeded));

        var composer = new AuthenticatedResultDrivenStepProgressionComposer(
                contexts, planIds, inspector, recoverer,
                completion, activation);
        var outcome = composer.complete(
                7L, 11L, planId, stepId, result,
                new StepRecoveryLeaseAttempt(
                        "owner", "token",
                        Instant.parse("2026-08-01T00:10:00Z")),
                new EffectDrivenStepProgressionActivationLeaseAttempt(
                        "owner", "token",
                        Instant.parse("2026-08-01T00:10:00Z")));

        assertEquals(
                EffectDrivenStepProgressionState.PLAN_SUCCEEDED,
                outcome.state());
        ArgumentCaptor<ActiveStepCompletionMaterializationRequest> request =
                ArgumentCaptor.forClass(
                        ActiveStepCompletionMaterializationRequest.class);
        verify(completion).compose(request.capture());
        assertTrue(request.getValue().completionFactDraft()
                .receiptReferences().isEmpty());
        assertEquals("sha256." + acceptedHash,
                request.getValue().completionFactDraft().outcomeHash());
        verify(activation, never()).composeReady(any());
    }

    private static PersistedStepRecoveryActive active(
            PlanId planId, PlanStepId stepId, EventId activationId) {
        PersistedStepRecoveryActive active =
                mock(PersistedStepRecoveryActive.class);
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        EventEnvelope event = mock(EventEnvelope.class);
        VersionedCheckpoint versioned = mock(VersionedCheckpoint.class);
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(active.planId()).thenReturn(planId);
        when(active.activation()).thenReturn(activation);
        when(active.checkpoint()).thenReturn(versioned);
        when(versioned.checkpoint()).thenReturn(checkpoint);
        when(checkpoint.createdAt()).thenReturn(
                Instant.parse("2026-08-01T00:00:01Z"));
        when(activation.stepId()).thenReturn(stepId);
        when(activation.activationEvent()).thenReturn(event);
        when(event.id()).thenReturn(activationId);
        return active;
    }
}
