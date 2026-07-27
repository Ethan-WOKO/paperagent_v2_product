package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
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
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ActiveStepCompletionMaterializationFixture {
    static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");
    static final PlanStepId TARGET = new PlanStepId("step-target");
    static final PlanStepId PEER = new PlanStepId("step-peer");
    static final ReceiptId EXISTING_RECEIPT =
            new ReceiptId("receipt-existing");
    static final String LEASE_TOKEN = "opaque-completion-token";
    static final String OUTCOME_HASH = "secret-outcome-hash";

    private ActiveStepCompletionMaterializationFixture() {
    }

    static ActiveStepCompletionMaterializationRequest request(
            RecoveredActiveStep recovered,
            List<ReceiptId> receipts) {
        return new ActiveStepCompletionMaterializationRequest(
                recovered,
                factDraft(receipts),
                eventDraft(Optional.of(new EventId("activation-event"))),
                revisionDraft(),
                T0.plusSeconds(6));
    }

    static ActiveStepCompletionFactDraft factDraft(List<ReceiptId> receipts) {
        return new ActiveStepCompletionFactDraft(
                OUTCOME_HASH,
                T0.plusSeconds(3),
                receipts);
    }

    static ActiveStepCompletionEventDraft eventDraft(
            Optional<EventId> causationId) {
        return new ActiveStepCompletionEventDraft(
                new EventId("completion-event"),
                T0.plusSeconds(4),
                new EventType("step-completed"),
                causationId,
                "secret-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    static ActiveStepCompletionRevisionDraft revisionDraft() {
        return new ActiveStepCompletionRevisionDraft(
                new PlanRevisionId("revision-complete"),
                "complete active step",
                T0.plusSeconds(5));
    }

    static RecoveredActiveStep recovered() {
        return recovered(StepExecutionState.NOT_STARTED, 1);
    }

    static RecoveredActiveStep finalRecovered() {
        return recovered(StepExecutionState.SUCCEEDED, 1);
    }

    static RecoveredActiveStep recovered(
            StepExecutionState peerState,
            long revisionNumber) {
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
        PlanStep target = step(TARGET);
        PlanStep peer = step(PEER);
        PlanRevisionId revisionId = new PlanRevisionId("revision-current");
        Map<PlanStepId, CompletionFact> priorFacts =
                new LinkedHashMap<>();
        if (peerState == StepExecutionState.SUCCEEDED) {
            priorFacts.put(PEER, new CompletionFact(
                    PEER,
                    "peer-outcome",
                    T0.plusSeconds(1),
                    List.of(EXISTING_RECEIPT)));
        }
        PlanRevision revision = new PlanRevision(
                revisionId,
                taskFrameId,
                revisionNumber,
                revisionNumber == 1
                        ? Optional.empty()
                        : Optional.of(new PlanRevisionId("revision-parent")),
                "current",
                T0,
                List.of(target, peer),
                priorFacts);
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        states.put(TARGET, StepExecutionState.ACTIVE);
        states.put(PEER, peerState);
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                List.of(EXISTING_RECEIPT),
                T0.plusSeconds(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-event"),
                taskFrameId,
                planId,
                2,
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "activation-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        VersionedCheckpoint versioned = new VersionedCheckpoint(3, checkpoint);
        PersistedStepActivation activation = new PersistedStepActivation(
                planId,
                TARGET,
                "activation-owner",
                1,
                activationEvent,
                versioned);
        PersistedStepRecoveryActive active = new PersistedStepRecoveryActive(
                taskFrame,
                plan,
                versioned,
                activation,
                Optional.empty());
        LeaseRecord lease = new LeaseRecord(
                planId,
                "recovery-owner",
                LEASE_TOKEN,
                7,
                T0.plusSeconds(2),
                T0.plusSeconds(60));
        return new RecoveredActiveStep(
                active,
                lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static RecoveredActiveStep withCheckpoint(
            RecoveredActiveStep source,
            long version,
            Checkpoint checkpoint) {
        PersistedStepRecoveryActive old = source.recovery();
        VersionedCheckpoint versioned =
                new VersionedCheckpoint(version, checkpoint);
        return new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        old.taskFrame(),
                        old.plan(),
                        versioned,
                        old.activation(),
                        old.executionContext()),
                source.lease(),
                source.leaseDisposition());
    }

    static RecoveredActiveStep withPlan(
            RecoveredActiveStep source,
            Plan plan) {
        PersistedStepRecoveryActive old = source.recovery();
        return new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        old.taskFrame(),
                        plan,
                        old.checkpoint(),
                        old.activation(),
                        old.executionContext()),
                source.lease(),
                source.leaseDisposition());
    }

    static RecoveredActiveStep withPlanAndLease(
            RecoveredActiveStep source,
            Plan plan,
            LeaseRecord lease) {
        PersistedStepRecoveryActive old = source.recovery();
        return new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        old.taskFrame(),
                        plan,
                        old.checkpoint(),
                        old.activation(),
                        old.executionContext()),
                lease,
                source.leaseDisposition());
    }

    static RecoveredActiveStep withLease(
            RecoveredActiveStep source,
            LeaseRecord lease,
            StepRecoveryLeaseDisposition disposition) {
        return new RecoveredActiveStep(
                source.recovery(), lease, disposition);
    }

    static Plan withCompletedTarget(RecoveredActiveStep source) {
        Plan old = source.recovery().plan();
        PlanRevision revision = old.latestRevision();
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>();
        facts.put(TARGET, new CompletionFact(
                TARGET,
                "prior-outcome",
                T0.plusSeconds(1),
                List.of()));
        PlanRevision completed = new PlanRevision(
                revision.id(),
                revision.taskFrameId(),
                revision.number(),
                revision.parentRevisionId(),
                revision.reason(),
                revision.createdAt(),
                revision.steps(),
                facts);
        return new Plan(old.id(), old.taskFrameId(), List.of(completed));
    }

    static Checkpoint checkpointWith(
            RecoveredActiveStep source,
            long sequence,
            PlanExecutionState planState,
            Map<PlanStepId, StepExecutionState> states) {
        Checkpoint old = source.recovery().checkpoint().checkpoint();
        return new Checkpoint(
                old.taskFrameId(),
                old.planId(),
                old.revisionId(),
                old.revisionNumber(),
                sequence,
                planState,
                states,
                old.receiptReferences(),
                old.createdAt());
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
