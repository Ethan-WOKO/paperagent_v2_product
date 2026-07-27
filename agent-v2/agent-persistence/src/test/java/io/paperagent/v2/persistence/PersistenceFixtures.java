package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class PersistenceFixtures {
    static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");
    static final TaskFrameId TASK_ID = new TaskFrameId("task-1");
    static final PlanId PLAN_ID = new PlanId("plan-1");
    static final PlanStepId STEP_1 = new PlanStepId("step-1");
    static final PlanStepId STEP_2 = new PlanStepId("step-2");
    static final ContentHash SOURCE_FINGERPRINT =
            new ContentHash("sha256", "a".repeat(64));

    private PersistenceFixtures() {
    }

    static TaskFrame taskFrame() {
        return taskFrame(TASK_ID, "Prepare a verified paper update");
    }

    static TaskFrame taskFrame(TaskFrameId id, String objective) {
        return new TaskFrame(
                id,
                objective,
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve citations"),
                Optional.of(new ProjectVersionRef("project-1", "version-1")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(5),
                                512 * 1024 * 1024L,
                                1024 * 1024L,
                                8),
                        Set.of()),
                T0);
    }

    static TaskFrame sourceLessTaskFrame(
            TaskFrameId id,
            String objective) {
        TaskFrame source = taskFrame(id, objective);
        return new TaskFrame(
                source.id(),
                source.objective(),
                source.targets(),
                source.deliverables(),
                source.constraints(),
                Optional.empty(),
                source.executionProfile(),
                source.createdAt());
    }

    static PlanStep step(PlanStepId id, Set<PlanStepId> dependencies) {
        return new PlanStep(
                id,
                "Perform " + id.value(),
                "Verify " + id.value(),
                dependencies,
                List.of("result is verified"),
                new BoundedExecutionHints(3, Duration.ofMinutes(2)));
    }

    static PlanRevision revision1() {
        return new PlanRevision(
                new PlanRevisionId("revision-1"),
                TASK_ID,
                1,
                Optional.empty(),
                "initial plan",
                T0,
                List.of(step(STEP_1, Set.of()), step(STEP_2, Set.of(STEP_1))),
                Map.of());
    }

    static PlanRevision revision2(String id, String reason) {
        return new PlanRevision(
                new PlanRevisionId(id),
                TASK_ID,
                2,
                Optional.of(new PlanRevisionId("revision-1")),
                reason,
                T0.plusSeconds(10),
                List.of(step(STEP_1, Set.of()), step(STEP_2, Set.of(STEP_1))),
                Map.of());
    }

    static Plan plan() {
        return plan(PLAN_ID);
    }

    static Plan plan(PlanId planId) {
        return new Plan(planId, TASK_ID, List.of(revision1()));
    }

    static Plan plan(PlanId planId, TaskFrameId taskFrameId, String suffix) {
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0,
                List.of(step(STEP_1, Set.of()), step(STEP_2, Set.of(STEP_1))),
                Map.of());
        return new Plan(planId, taskFrameId, List.of(revision));
    }

    static EventEnvelope event(String id, long sequence) {
        return event(id, TASK_ID, PLAN_ID, sequence);
    }

    static EventEnvelope event(
            String id,
            TaskFrameId taskFrameId,
            PlanId planId,
            long sequence) {
        return new EventEnvelope(
                new EventId(id),
                taskFrameId,
                planId,
                sequence,
                T0.plusSeconds(sequence),
                new EventType("step-progress"),
                Optional.empty(),
                "correlation-1",
                new InlineEventPayload(
                        new ObjectValue(Map.of("message", new TextValue("event-" + sequence)))));
    }

    static ExecutionReceipt receipt(String id, String toolCallId) {
        return new ExecutionReceipt(
                new ReceiptId(id),
                new ToolCallId(toolCallId),
                ReceiptStatus.SUCCESS,
                T0,
                T0.plusSeconds(1),
                Optional.of(0),
                Optional.empty(),
                OutputCapture.inline("ok", false),
                OutputCapture.empty(),
                List.of(),
                Optional.empty(),
                List.of());
    }

    static Checkpoint checkpoint(
            long eventSequence,
            Instant createdAt,
            List<ReceiptId> receipts) {
        return new Checkpoint(
                TASK_ID,
                PLAN_ID,
                new PlanRevisionId("revision-1"),
                1,
                eventSequence,
                PlanExecutionState.ACTIVE,
                Map.of(
                        STEP_1, StepExecutionState.ACTIVE,
                        STEP_2, StepExecutionState.NOT_STARTED),
                receipts,
                createdAt);
    }

    static Checkpoint initialCheckpoint(Plan plan) {
        return executionCheckpoint(plan, 0, PlanExecutionState.NOT_STARTED, T0);
    }

    static Checkpoint startedCheckpoint(Plan plan) {
        return executionCheckpoint(plan, 1, PlanExecutionState.ACTIVE, T0.plusSeconds(1));
    }

    static ExecutionStartRequest executionStartRequest(
            Plan plan,
            String leaseToken,
            long fencingToken,
            String eventId) {
        return new ExecutionStartRequest(
                plan.id(),
                leaseToken,
                fencingToken,
                event(eventId, plan.taskFrameId(), plan.id(), 1),
                startedCheckpoint(plan));
    }

    static StepActivationRequest stepActivationRequest(
            Plan plan,
            String leaseToken,
            long fencingToken,
            String eventId) {
        Checkpoint source = startedCheckpoint(plan);
        return stepActivationRequest(
                plan,
                source,
                2,
                1,
                leaseToken,
                fencingToken,
                STEP_1,
                eventId,
                3);
    }

    static WorkspaceMaterializationSpec workspaceSpec(
            String suffix,
            ProjectVersionRef sourceProjectVersion) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId("workspace-" + suffix),
                sourceProjectVersion,
                new WorkspaceMaterializationLimits(
                        1024 * 1024L,
                        8 * 1024 * 1024L,
                        128));
    }

    static WorkspaceMaterializationSpec workspaceSpec(String suffix) {
        return workspaceSpec(
                suffix,
                taskFrame().sourceProjectVersion().orElseThrow());
    }

    static PlanExecutionContextReservationRequest contextReservationRequest(
            Plan plan,
            String leaseToken,
            long fencingToken,
            WorkspaceMaterializationSpec spec) {
        return new PlanExecutionContextReservationRequest(
                plan.id(),
                leaseToken,
                fencingToken,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                2,
                1,
                spec);
    }

    static PlanExecutionContextConfirmationRequest contextConfirmationRequest(
            Plan plan,
            String leaseToken,
            long fencingToken,
            WorkspaceMaterializationSpec spec) {
        return new PlanExecutionContextConfirmationRequest(
                plan.id(),
                leaseToken,
                fencingToken,
                spec,
                SOURCE_FINGERPRINT);
    }

    static PersistedPlanExecutionContextConfirmed confirmExecutionContext(
            PlanExecutionContextRepository contexts,
            Plan plan,
            String leaseToken,
            long fencingToken,
            WorkspaceMaterializationSpec spec) {
        requireApplied(contexts.reserve(contextReservationRequest(
                plan, leaseToken, fencingToken, spec)));
        PersistenceResult<PersistedPlanExecutionContextConfirmed> result =
                contexts.confirm(contextConfirmationRequest(
                        plan, leaseToken, fencingToken, spec));
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError(
                    "fixture setup failed: " + result);
        }
        return result.value().orElseThrow();
    }

    static StepActivationRequest stepActivationRequest(
            Plan plan,
            Checkpoint source,
            long expectedCheckpointVersion,
            long expectedEventHead,
            String leaseToken,
            long fencingToken,
            PlanStepId stepId,
            String eventId,
            long eventSequence) {
        EventEnvelope event =
                event(eventId, plan.taskFrameId(), plan.id(), eventSequence);
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(source.stepStates());
        states.put(stepId, StepExecutionState.ACTIVE);
        Checkpoint target = new Checkpoint(
                source.taskFrameId(),
                source.planId(),
                source.revisionId(),
                source.revisionNumber(),
                eventSequence,
                PlanExecutionState.ACTIVE,
                states,
                source.receiptReferences(),
                source.createdAt().plusSeconds(1));
        return new StepActivationRequest(
                plan.id(),
                leaseToken,
                fencingToken,
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                expectedCheckpointVersion,
                expectedEventHead,
                stepId,
                event,
                target);
    }

    static InMemoryPersistence bootstrappedPersistence(Clock leaseClock) {
        InMemoryPersistence persistence = new InMemoryPersistence(leaseClock);
        requireApplied(persistence.planBootstraps().bootstrap(
                taskFrame(),
                plan(),
                initialCheckpoint(plan())));
        return persistence;
    }

    static InMemoryPersistence initializedPersistence() {
        return initializedPersistence(new InMemoryPersistence());
    }

    static InMemoryPersistence initializedPersistence(Clock leaseClock) {
        return initializedPersistence(new InMemoryPersistence(leaseClock));
    }

    private static InMemoryPersistence initializedPersistence(
            InMemoryPersistence persistence) {
        requireApplied(persistence.taskFrames().create(taskFrame()));
        requireApplied(persistence.plans().create(plan()));
        return persistence;
    }

    private static void requireApplied(PersistenceResult<?> result) {
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError("fixture setup failed: " + result);
        }
    }

    private static Checkpoint executionCheckpoint(
            Plan plan,
            long eventSequence,
            PlanExecutionState planState,
            Instant createdAt) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        plan.latestRevision().steps().forEach(
                step -> states.put(step.id(), StepExecutionState.NOT_STARTED));
        return new Checkpoint(
                plan.taskFrameId(),
                plan.id(),
                plan.latestRevision().id(),
                plan.latestRevision().number(),
                eventSequence,
                planState,
                states,
                List.of(),
                createdAt);
    }

    static final class MutableCountingClock extends Clock {
        private final AtomicReference<Instant> current;
        private final AtomicInteger observations;
        private final AtomicBoolean failOnObservation;
        private final ZoneId zone;

        MutableCountingClock(Instant initial) {
            this(
                    new AtomicReference<>(initial),
                    new AtomicInteger(),
                    new AtomicBoolean(),
                    ZoneOffset.UTC);
        }

        private MutableCountingClock(
                AtomicReference<Instant> current,
                AtomicInteger observations,
                AtomicBoolean failOnObservation,
                ZoneId zone) {
            this.current = current;
            this.observations = observations;
            this.failOnObservation = failOnObservation;
            this.zone = zone;
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void failOnObservation() {
            failOnObservation.set(true);
        }

        int observationCount() {
            return observations.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableCountingClock(
                    current, observations, failOnObservation, requestedZone);
        }

        @Override
        public Instant instant() {
            observations.incrementAndGet();
            if (failOnObservation.get()) {
                throw new IllegalStateException("Clock must not be observed");
            }
            return current.get();
        }
    }
}
