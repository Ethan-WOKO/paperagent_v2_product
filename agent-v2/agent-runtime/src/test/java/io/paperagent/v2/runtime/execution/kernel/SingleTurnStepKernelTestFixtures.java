package io.paperagent.v2.runtime.execution.kernel;

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
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceResult;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

final class SingleTurnStepKernelTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-26T00:00:00Z");

    private SingleTurnStepKernelTestFixtures() {
    }

    static RecoveredActiveStep recovered(String suffix) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "goal " + suffix,
                List.of("target"),
                List.of("deliverable"),
                List.of("constraint"),
                Optional.empty(),
                profile(),
                T0);
        PlanStep step = new PlanStep(
                stepId,
                "do " + suffix,
                "verify " + suffix,
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Map<PlanStepId, StepExecutionState> stepStates = new LinkedHashMap<>();
        stepStates.put(stepId, StepExecutionState.ACTIVE);
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                stepStates,
                List.of(),
                T0.plusSeconds(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-" + suffix),
                taskFrameId,
                planId,
                2,
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
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
                new VersionedCheckpoint(3, checkpoint));
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                taskFrame,
                plan,
                new VersionedCheckpoint(3, checkpoint),
                activation,
                Optional.empty());
        return new RecoveredActiveStep(
                recovery, lease, StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static RecoveredActiveStep recoveredWithInactiveCheckpoint(String suffix) {
        RecoveredActiveStep original = recovered(suffix);
        Checkpoint checkpoint = original.recovery().checkpoint().checkpoint();
        Checkpoint inactive = new Checkpoint(
                checkpoint.taskFrameId(),
                checkpoint.planId(),
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                checkpoint.lastEventSequence(),
                PlanExecutionState.PAUSED,
                checkpoint.stepStates(),
                checkpoint.receiptReferences(),
                checkpoint.createdAt());
        PersistedStepActivation activation = original.recovery().activation();
        PersistedStepActivation withInactiveCheckpoint = new PersistedStepActivation(
                activation.planId(),
                activation.stepId(),
                activation.leaseOwnerId(),
                activation.fencingToken(),
                activation.activationEvent(),
                new VersionedCheckpoint(3, inactive));
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                original.recovery().taskFrame(),
                original.recovery().plan(),
                new VersionedCheckpoint(3, inactive),
                withInactiveCheckpoint,
                Optional.empty());
        return new RecoveredActiveStep(
                recovery, original.lease(), StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static RecoveredActiveStep recoveredWithMismatchedActivationLease(String suffix) {
        RecoveredActiveStep original = recovered(suffix);
        PersistedStepActivation activation = original.recovery().activation();
        PersistedStepActivation mismatched = new PersistedStepActivation(
                activation.planId(),
                activation.stepId(),
                "other-owner-" + suffix,
                activation.fencingToken(),
                activation.activationEvent(),
                activation.activatedCheckpoint());
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                original.recovery().taskFrame(),
                original.recovery().plan(),
                original.recovery().checkpoint(),
                mismatched,
                Optional.empty());
        return new RecoveredActiveStep(
                recovery, original.lease(), StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static RecoveredActiveStep recoveredWithPlanIdMismatchedFromCheckpointAndActivation(
            String suffix) {
        RecoveredActiveStep original = recovered(suffix);
        Plan originalPlan = original.recovery().plan();
        Plan mismatchedPlan = new Plan(
                new PlanId("plan-snapshot-" + suffix),
                originalPlan.taskFrameId(),
                originalPlan.revisions());
        LeaseRecord originalLease = original.lease();
        LeaseRecord matchingRecoveryPlanLease = new LeaseRecord(
                mismatchedPlan.id(),
                originalLease.ownerId(),
                originalLease.leaseToken(),
                originalLease.fencingToken(),
                originalLease.acquiredAt(),
                originalLease.expiresAt());
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                original.recovery().taskFrame(),
                mismatchedPlan,
                original.recovery().checkpoint(),
                original.recovery().activation(),
                Optional.empty());
        return new RecoveredActiveStep(
                recovery,
                matchingRecoveryPlanLease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static RecoveredActiveStep recoveredWithActivationStepMissingFromCurrentPlan(String suffix) {
        RecoveredActiveStep original = recovered(suffix);
        PlanRevision originalRevision = original.recovery().plan().latestRevision();
        PlanStep originalStep = originalRevision.steps().get(0);
        PlanStep replacement = new PlanStep(
                new PlanStepId("step-current-" + suffix),
                originalStep.intent(),
                originalStep.expectedOutcome(),
                originalStep.dependencies(),
                originalStep.completionCriteria(),
                originalStep.executionHints());
        PlanRevision currentRevision = new PlanRevision(
                originalRevision.id(),
                originalRevision.taskFrameId(),
                originalRevision.number(),
                originalRevision.parentRevisionId(),
                originalRevision.reason(),
                originalRevision.createdAt(),
                List.of(replacement),
                originalRevision.completedFacts());
        Plan currentPlan = new Plan(
                original.recovery().plan().id(),
                original.recovery().plan().taskFrameId(),
                List.of(currentRevision));
        PersistedStepRecoveryActive recovery = new PersistedStepRecoveryActive(
                original.recovery().taskFrame(),
                currentPlan,
                original.recovery().checkpoint(),
                original.recovery().activation(),
                Optional.empty());
        return new RecoveredActiveStep(
                recovery, original.lease(), StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }

    static EffectIntent intent(RecoveredActiveStep recoveredStep, String suffix) {
        return new EffectIntent(
                new ToolCallId("call-" + suffix),
                recoveredStep.planId(),
                recoveredStep.recovery().activation().stepId(),
                "workspace.edit",
                new ObjectValue(Map.of()));
    }

    static PersistedEffectIntent persisted(
            RecoveredActiveStep recoveredStep,
            EffectIntent intent) {
        return new PersistedEffectIntent(
                intent,
                recoveredStep.lease().ownerId(),
                recoveredStep.lease().fencingToken(),
                recoveredStep.recovery().activation().activationEvent().id());
    }

    static final class RecordingEffectIntentRepository implements EffectIntentRepository {
        private final Function<EffectIntentRequest, PersistenceResult<PersistedEffectIntent>> persistResult;
        private final AtomicInteger persistCalls = new AtomicInteger();
        private final ConcurrentLinkedQueue<EffectIntentRequest> requests =
                new ConcurrentLinkedQueue<>();

        RecordingEffectIntentRepository(
                Function<EffectIntentRequest, PersistenceResult<PersistedEffectIntent>> persistResult) {
            this.persistResult = persistResult;
        }

        @Override
        public PersistenceResult<PersistedEffectIntent> persist(EffectIntentRequest request) {
            persistCalls.incrementAndGet();
            requests.add(request);
            return persistResult.apply(request);
        }

        @Override
        public PersistenceResult<PersistedEffectIntent> find(ToolCallId toolCallId) {
            throw new AssertionError("find is outside the single-turn kernel");
        }

        int persistCalls() {
            return persistCalls.get();
        }

        List<EffectIntentRequest> requests() {
            return List.copyOf(requests);
        }
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
