package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionEventDraft;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;
import io.paperagent.v2.runtime.execution.interruption.materialization.DeterministicActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedActiveStepInterruption;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepCancellation;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepFailure;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepPause;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ActiveStepInterruptionCompositionTestFixture {
    static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");
    static final PlanStepId TARGET = new PlanStepId("step-target");
    static final PlanStepId PEER = new PlanStepId("step-peer");
    static final String LEASE_TOKEN = "opaque-secret-token";

    private ActiveStepInterruptionCompositionTestFixture() {
    }

    static ActiveStepInterruptionMaterializationRequest request(
            StepInterruptionKind kind) {
        return new ActiveStepInterruptionMaterializationRequest(
                recovered(),
                kind,
                new ActiveStepInterruptionEventDraft(
                        new EventId("interruption-" + kind.name()),
                        T0.plusSeconds(3),
                        new EventType("step-interruption"),
                        Optional.of(new EventId("activation-event")),
                        "correlation",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                T0.plusSeconds(4));
    }

    static MaterializedActiveStepInterruption materialized(
            ActiveStepInterruptionMaterializationRequest request) {
        return new DeterministicActiveStepInterruptionMaterializer()
                .materialize(request);
    }

    static PersistedStepInterruption persisted(
            ActiveStepInterruptionMaterializationRequest input,
            MaterializedActiveStepInterruption materialized) {
        EventEnvelope event;
        Checkpoint checkpoint;
        if (materialized instanceof MaterializedStepPause value) {
            event = value.request().pauseEvent();
            checkpoint = value.request().pausedCheckpoint();
        } else if (materialized instanceof MaterializedStepFailure value) {
            event = value.request().failureEvent();
            checkpoint = value.request().failedCheckpoint();
        } else {
            MaterializedStepCancellation value =
                    (MaterializedStepCancellation) materialized;
            event = value.request().cancellationEvent();
            checkpoint = value.request().cancelledCheckpoint();
        }
        LeaseRecord lease = input.recoveredActiveStep().lease();
        return new PersistedStepInterruption(
                input.recoveredActiveStep().recovery().planId(),
                input.recoveredActiveStep().recovery().activation().stepId(),
                input.kind(),
                lease.ownerId(),
                lease.fencingToken(),
                event,
                new VersionedCheckpoint(4, checkpoint));
    }

    static RecoveredActiveStep recovered() {
        TaskFrameId taskFrameId = new TaskFrameId("task");
        PlanId planId = new PlanId("plan");
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "perform active step",
                List.of("target"),
                List.of("deliverable"),
                List.of("constraint"),
                Optional.empty(),
                profile(),
                T0);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-1"),
                taskFrameId,
                1,
                Optional.empty(),
                "initial",
                T0,
                List.of(step(TARGET), step(PEER)),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        states.put(TARGET, StepExecutionState.ACTIVE);
        states.put(PEER, StepExecutionState.NOT_STARTED);
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                List.of(new ReceiptId("receipt-before")),
                T0.plusSeconds(2));
        VersionedCheckpoint versioned = new VersionedCheckpoint(3, checkpoint);
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-event"),
                taskFrameId,
                planId,
                2,
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        PersistedStepActivation activation = new PersistedStepActivation(
                planId,
                TARGET,
                "original-owner",
                1,
                activationEvent,
                versioned);
        return new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        taskFrame,
                        plan,
                        versioned,
                        activation,
                        Optional.empty()),
                new LeaseRecord(
                        planId,
                        "recovery-owner",
                        LEASE_TOKEN,
                        7,
                        T0.plusSeconds(3),
                        T0.plusSeconds(60)),
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    private static PlanStep step(PlanStepId id) {
        return new PlanStep(
                id,
                "intent " + id.value(),
                "outcome " + id.value(),
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
    }

    private static ExecutionProfile profile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1),
                        1024,
                        1024,
                        1),
                Set.of());
    }
}
