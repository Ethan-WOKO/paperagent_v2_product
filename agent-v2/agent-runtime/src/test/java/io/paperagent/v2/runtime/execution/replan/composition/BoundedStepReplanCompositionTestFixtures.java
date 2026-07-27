package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
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
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.ActiveStepReplanRepository;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.loop.BoundedStepAgentLoopTurnLimitReached;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class BoundedStepReplanCompositionTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    private BoundedStepReplanCompositionTestFixtures() {
    }

    static Scenario scenario(String suffix) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "goal " + suffix,
                List.of("object"),
                List.of("deliverable"),
                List.of("constraint"),
                Optional.empty(),
                profile(),
                T0);
        PlanStep step = step(stepId, suffix);
        PlanRevision initialRevision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(initialRevision));
        Checkpoint activeCheckpoint = new Checkpoint(
                taskFrameId,
                planId,
                initialRevision.id(),
                initialRevision.number(),
                2,
                PlanExecutionState.ACTIVE,
                Map.of(stepId, StepExecutionState.ACTIVE),
                List.of(),
                T0.plusSeconds(2));
        EventEnvelope activationEvent = event(
                "activation-" + suffix,
                taskFrameId,
                planId,
                2,
                "step-activation",
                T0.plusSeconds(2));
        LeaseRecord lease = new LeaseRecord(
                planId,
                "owner-" + suffix,
                "token-" + suffix,
                7,
                T0,
                T0.plus(Duration.ofMinutes(5)));
        PersistedStepActivation activation = new PersistedStepActivation(
                planId,
                stepId,
                lease.ownerId(),
                lease.fencingToken(),
                activationEvent,
                new VersionedCheckpoint(3, activeCheckpoint));
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                taskFrame,
                plan,
                new VersionedCheckpoint(3, activeCheckpoint),
                activation,
                Optional.empty());
        RecoveredActiveStep recovered = new RecoveredActiveStep(
                recovery,
                lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
        PersistedEffectIntent intent = persistedIntent(recovered, suffix);
        BoundedStepAgentLoopTurnLimitReached turnLimit =
                new BoundedStepAgentLoopTurnLimitReached(
                        planId, stepId, 1, List.of(intent));
        ActiveStepReplanRequest request = request(recovered, suffix);
        return new Scenario(recovered, turnLimit, request);
    }

    static ActiveStepReplanRequest request(RecoveredActiveStep recovered, String suffix) {
        PersistedStepRecoveryActive recovery = recovered.recovery();
        Checkpoint active = recovery.checkpoint().checkpoint();
        PlanStepId activeStepId = recovery.activation().stepId();
        EventEnvelope supersession = event(
                "supersession-" + suffix,
                active.taskFrameId(),
                active.planId(),
                active.lastEventSequence() + 1,
                "step-superseded-by-replan",
                T0.plusSeconds(3));
        Checkpoint superseded = new Checkpoint(
                active.taskFrameId(),
                active.planId(),
                active.revisionId(),
                active.revisionNumber(),
                supersession.sequence(),
                PlanExecutionState.ACTIVE,
                Map.of(activeStepId, StepExecutionState.SUPERSEDED_BY_REPLAN),
                active.receiptReferences(),
                T0.plusSeconds(3));
        PlanStep replannedStep = step(new PlanStepId("replanned-step-" + suffix), suffix);
        PlanRevision replannedRevision = new PlanRevision(
                new PlanRevisionId("replanned-revision-" + suffix),
                active.taskFrameId(),
                active.revisionNumber() + 1,
                Optional.of(active.revisionId()),
                "turn limit " + suffix,
                T0.plusSeconds(4),
                List.of(replannedStep),
                Map.of());
        EventEnvelope replan = event(
                "replan-" + suffix,
                active.taskFrameId(),
                active.planId(),
                supersession.sequence() + 1,
                "plan-replanned",
                T0.plusSeconds(4));
        Checkpoint replanned = new Checkpoint(
                active.taskFrameId(),
                active.planId(),
                replannedRevision.id(),
                replannedRevision.number(),
                replan.sequence(),
                PlanExecutionState.ACTIVE,
                Map.of(replannedStep.id(), StepExecutionState.NOT_STARTED),
                active.receiptReferences(),
                T0.plusSeconds(4));
        return new ActiveStepReplanRequest(
                recovered.planId(),
                recovered.lease().leaseToken(),
                recovered.lease().fencingToken(),
                active.revisionId(),
                active.revisionNumber(),
                recovery.checkpoint().version(),
                active.lastEventSequence(),
                activeStepId,
                supersession,
                superseded,
                replan,
                replannedRevision,
                replanned);
    }

    static ActiveStepReplanRequest copyRequest(
            ActiveStepReplanRequest request,
            PlanId planId,
            String leaseToken,
            long fencingToken,
            PlanRevisionId revisionId,
            long revisionNumber,
            long checkpointVersion,
            long eventHeadSequence,
            PlanStepId activeStepId) {
        return new ActiveStepReplanRequest(
                planId,
                leaseToken,
                fencingToken,
                revisionId,
                revisionNumber,
                checkpointVersion,
                eventHeadSequence,
                activeStepId,
                request.supersessionEvent(),
                request.supersededCheckpoint(),
                request.replanEvent(),
                request.replannedRevision(),
                request.replannedCheckpoint());
    }

    static PersistedActiveStepReplan persisted(
            RecoveredActiveStep recovered,
            ActiveStepReplanRequest request) {
        return new PersistedActiveStepReplan(
                request.planId(),
                request.activeStepId(),
                recovered.lease().ownerId(),
                request.fencingToken(),
                request.supersessionEvent(),
                new VersionedCheckpoint(
                        request.expectedCheckpointVersion() + 1,
                        request.supersededCheckpoint()),
                request.replanEvent(),
                request.replannedRevision(),
                new VersionedCheckpoint(
                        request.expectedCheckpointVersion() + 2,
                        request.replannedCheckpoint()));
    }

    static BoundedStepAgentLoopTurnLimitReached turnLimit(
            Scenario scenario,
            PersistedEffectIntent intent) {
        return new BoundedStepAgentLoopTurnLimitReached(
                scenario.turnLimitReached().planId(),
                scenario.turnLimitReached().stepId(),
                1,
                List.of(intent));
    }

    static PersistedEffectIntent persistedIntent(
            RecoveredActiveStep recovered,
            String suffix) {
        return new PersistedEffectIntent(
                new EffectIntent(
                        new ToolCallId("call-" + suffix),
                        recovered.planId(),
                        recovered.recovery().activation().stepId(),
                        "test.effect",
                        new ObjectValue(Map.of("suffix", new TextValue(suffix)))),
                recovered.lease().ownerId(),
                recovered.lease().fencingToken(),
                recovered.recovery().activation().activationEvent().id());
    }

    static PersistedEffectIntent copyIntent(
            PersistedEffectIntent intent,
            PlanId planId,
            PlanStepId stepId,
            String ownerId,
            long fencingToken,
            EventId activationEventId) {
        return new PersistedEffectIntent(
                new EffectIntent(
                        intent.intent().toolCallId(),
                        planId,
                        stepId,
                        intent.intent().kind(),
                        intent.intent().arguments()),
                ownerId,
                fencingToken,
                activationEventId);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static PersistenceResult<PersistedActiveStepReplan> malformedAppliedValue() {
        return (PersistenceResult) new PersistenceResult(
                PersistenceOutcome.APPLIED,
                Optional.of("not-a-replan"),
                Optional.empty());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static PersistenceResult<PersistedActiveStepReplan> malformedRejectedFailure() {
        return (PersistenceResult) new PersistenceResult(
                PersistenceOutcome.REJECTED,
                Optional.empty(),
                Optional.of("not-a-failure"));
    }

    static PersistenceResult<PersistedActiveStepReplan> unsettledEffectRejection() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                "activeStepReplan.settledEffects");
    }

    static final class RecordingReplanRepository implements ActiveStepReplanRepository {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<ActiveStepReplanRequest> requests = new ArrayList<>();
        private PersistenceResult<PersistedActiveStepReplan> result;
        private RuntimeException exception;
        private boolean returnNull;

        RecordingReplanRepository(PersistenceResult<PersistedActiveStepReplan> result) {
            this.result = result;
        }

        @Override
        public synchronized PersistenceResult<PersistedActiveStepReplan> supersedeAndReplan(
                ActiveStepReplanRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            if (exception != null) {
                throw exception;
            }
            return returnNull ? null : result;
        }

        int calls() {
            return calls.get();
        }

        synchronized List<ActiveStepReplanRequest> requests() {
            return List.copyOf(requests);
        }

        void throwWith(RuntimeException exception) {
            this.exception = exception;
        }

        void returnNull() {
            this.returnNull = true;
        }
    }

    record Scenario(
            RecoveredActiveStep recovered,
            BoundedStepAgentLoopTurnLimitReached turnLimitReached,
            ActiveStepReplanRequest request) {
    }

    private static EventEnvelope event(
            String id,
            TaskFrameId taskFrameId,
            PlanId planId,
            long sequence,
            String type,
            Instant occurredAt) {
        return new EventEnvelope(
                new EventId(id),
                taskFrameId,
                planId,
                sequence,
                occurredAt,
                new EventType(type),
                Optional.empty(),
                "correlation-" + id,
                new InlineEventPayload(new ObjectValue(Map.of())));
    }

    private static PlanStep step(PlanStepId stepId, String suffix) {
        return new PlanStep(
                stepId,
                "do " + suffix,
                "verify " + suffix,
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
    }

    private static ExecutionProfile profile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        1024,
                        1024,
                        1),
                Set.of());
    }
}
