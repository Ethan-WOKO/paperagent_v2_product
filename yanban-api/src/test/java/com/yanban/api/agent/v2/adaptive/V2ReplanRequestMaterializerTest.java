package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.yanban.api.agent.v2.adaptive.reflection.*;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.*;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class V2ReplanRequestMaterializerTest {
    @Test
    void appendsReplacementAndPreservesSourceFacts() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        PlanStepId oldId = new PlanStepId("old-step");
        PlanStep old = step(oldId, "old");
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-1"),
                new TaskFrameId("frame-1"), 1, Optional.empty(),
                "initial", time, List.of(old), Map.of());
        Plan plan = new Plan(
                new PlanId("plan-1"), new TaskFrameId("frame-1"),
                List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                new TaskFrameId("frame-1"), plan.id(), revision.id(), 1, 2,
                PlanExecutionState.ACTIVE,
                Map.of(oldId, StepExecutionState.ACTIVE),
                List.of(new ReceiptId("receipt-1")), time.plusSeconds(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-1"), checkpoint.taskFrameId(),
                plan.id(), 2, time.plusSeconds(2),
                new EventType("STEP_ACTIVATED"), Optional.empty(),
                "activation-1",
                new InlineEventPayload(new ObjectValue(Map.of())));
        PersistedStepActivation activation =
                mock(PersistedStepActivation.class);
        when(activation.stepId()).thenReturn(oldId);
        when(activation.activationEvent()).thenReturn(activationEvent);
        PersistedStepRecoveryActive source =
                mock(PersistedStepRecoveryActive.class);
        when(source.planId()).thenReturn(plan.id());
        when(source.plan()).thenReturn(plan);
        when(source.checkpoint())
                .thenReturn(new VersionedCheckpoint(3, checkpoint));
        when(source.activation()).thenReturn(activation);
        LeaseRecord lease = mock(LeaseRecord.class);
        when(lease.leaseToken()).thenReturn("lease-token");
        when(lease.fencingToken()).thenReturn(4L);
        RecoveredActiveStep recovered = mock(RecoveredActiveStep.class);
        when(recovered.recovery()).thenReturn(source);
        when(recovered.lease()).thenReturn(lease);
        ReflectionOutcome reflection = new ReflectionOutcome(
                ReflectionAction.REPLAN, "repair", null,
                List.of(new ReflectionReplacementStep(
                        step(new PlanStepId("repair-step"), "repair"),
                        "project_read", new ToolId("project.read"))));

        ActiveStepReplanRequest result =
                new V2ReplanRequestMaterializer()
                        .materialize(recovered, reflection);

        assertEquals(StepExecutionState.SUPERSEDED_BY_REPLAN,
                result.supersededCheckpoint().stepStates().get(oldId));
        assertEquals(List.of(new ReceiptId("receipt-1")),
                result.replannedCheckpoint().receiptReferences());
        assertEquals(List.of(new PlanStepId("repair-step")),
                result.replannedRevision().steps().stream()
                        .map(PlanStep::id).toList());
        assertEquals(StepExecutionState.NOT_STARTED,
                result.replannedCheckpoint().stepStates()
                        .get(new PlanStepId("repair-step")));
    }

    private static PlanStep step(PlanStepId id, String intent) {
        return new PlanStep(
                id, intent, "done", Set.of(), List.of("receipt"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
    }
}
