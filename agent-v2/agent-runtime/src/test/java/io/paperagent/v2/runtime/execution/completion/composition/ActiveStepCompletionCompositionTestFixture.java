package io.paperagent.v2.runtime.execution.completion.composition;

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
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionEventDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionFactDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionRevisionDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.DeterministicActiveStepCompletionMaterializer;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ActiveStepCompletionCompositionTestFixture {
    static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");
    static final PlanStepId TARGET = new PlanStepId("step-target");
    static final PlanStepId PEER = new PlanStepId("step-peer");
    static final String LEASE_TOKEN = "opaque-completion-token";

    private ActiveStepCompletionCompositionTestFixture() {
    }

    static ActiveStepCompletionMaterializationRequest request() {
        return request(recovered());
    }

    static ActiveStepCompletionMaterializationRequest request(
            RecoveredActiveStep recovered) {
        return new ActiveStepCompletionMaterializationRequest(
                recovered,
                new ActiveStepCompletionFactDraft(
                        "outcome-hash",
                        T0.plusSeconds(3),
                        List.of(new ReceiptId("receipt-new"))),
                new ActiveStepCompletionEventDraft(
                        new EventId("completion-event"),
                        T0.plusSeconds(4),
                        new EventType("step-completed"),
                        Optional.of(new EventId("activation-event")),
                        "correlation",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                new ActiveStepCompletionRevisionDraft(
                        new PlanRevisionId("revision-complete"),
                        "complete active step",
                        T0.plusSeconds(5)),
                T0.plusSeconds(6));
    }

    static StepCompletionRequest materialized(
            ActiveStepCompletionMaterializationRequest request) {
        return new DeterministicActiveStepCompletionMaterializer()
                .materialize(request);
    }

    static PersistedStepCompletion persisted(StepCompletionRequest request) {
        LeaseRecord lease = ActiveStepCompletionCompositionTestFixture
                .request().recoveredActiveStep().lease();
        return persisted(
                request,
                request.planId(),
                request.stepId(),
                lease.ownerId(),
                lease.fencingToken());
    }

    static PersistedStepCompletion persisted(
            StepCompletionRequest request,
            PlanId planId,
            PlanStepId stepId,
            String ownerId,
            long fence) {
        return new PersistedStepCompletion(
                planId,
                stepId,
                ownerId,
                fence,
                request.completionEvent(),
                request.completedRevision(),
                new VersionedCheckpoint(
                        4, request.completedCheckpoint()));
    }

    static RecoveredActiveStep recovered() {
        return recovered(new PlanId("plan"), TARGET, PEER);
    }

    static RecoveredActiveStep recovered(
            PlanId planId,
            PlanStepId target,
            PlanStepId peer) {
        TaskFrameId taskFrameId = new TaskFrameId("task");
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
                new PlanRevisionId("revision-current"),
                taskFrameId,
                1,
                Optional.empty(),
                "current",
                T0,
                List.of(step(target), step(peer)),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        states.put(target, StepExecutionState.ACTIVE);
        states.put(peer, StepExecutionState.NOT_STARTED);
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                List.of(new ReceiptId("receipt-existing")),
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
        return new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        taskFrame,
                        plan,
                        versioned,
                        new PersistedStepActivation(
                                planId,
                                target,
                                "activation-owner",
                                1,
                                activationEvent,
                                versioned),
                        Optional.empty()),
                new LeaseRecord(
                        planId,
                        "recovery-owner",
                        LEASE_TOKEN,
                        7,
                        T0.plusSeconds(2),
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
