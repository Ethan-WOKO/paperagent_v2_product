package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ContentHash;
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
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class StepRecoveryCompositionTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-26T00:00:00Z");

    private StepRecoveryCompositionTestFixtures() {
    }

    static StepRecoveryRequest request(String suffix) {
        return new StepRecoveryRequest(
                new PlanId("plan-" + suffix),
                new StepRecoveryLeaseAttempt(
                        "owner-" + suffix,
                        "token-" + suffix,
                        T0.plus(Duration.ofMinutes(5))));
    }

    static LeaseRecord matchingLease(StepRecoveryRequest request, long fencingToken) {
        return new LeaseRecord(
                request.planId(),
                request.leaseAttempt().leaseOwnerId(),
                request.leaseAttempt().leaseToken(),
                fencingToken,
                T0,
                request.leaseAttempt().leaseExpiresAt());
    }

    static PersistedStepRecoveryActive active(
            String planSuffix,
            String snapshotSuffix,
            boolean sourceBacked) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + planSuffix);
        PlanId planId = new PlanId("plan-" + planSuffix);
        PlanStepId stepId = new PlanStepId("step-" + planSuffix);
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "goal " + snapshotSuffix,
                List.of("object"),
                List.of("deliverable"),
                List.of("constraint"),
                sourceBacked
                        ? Optional.of(new ProjectVersionRef(
                                "project-" + planSuffix,
                                "version-" + planSuffix))
                        : Optional.empty(),
                profile(),
                T0);
        PlanStep step = new PlanStep(
                stepId,
                "do " + snapshotSuffix,
                "verify " + snapshotSuffix,
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + planSuffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + snapshotSuffix,
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        states.put(stepId, StepExecutionState.ACTIVE);
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                2,
                PlanExecutionState.ACTIVE,
                states,
                List.of(),
                T0.plusSeconds(2));
        EventEnvelope activationEvent = new EventEnvelope(
                new EventId("activation-" + snapshotSuffix),
                taskFrameId,
                planId,
                2,
                T0.plusSeconds(2),
                new EventType("step-activation"),
                Optional.empty(),
                "correlation-" + snapshotSuffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        PersistedStepActivation activation = new PersistedStepActivation(
                planId,
                stepId,
                "activation-owner-" + snapshotSuffix,
                1,
                activationEvent,
                new VersionedCheckpoint(3, checkpoint));
        Optional<PersistedPlanExecutionContextConfirmed> context = sourceBacked
                ? Optional.of(confirmedContext(planId, planSuffix))
                : Optional.empty();
        return new PersistedStepRecoveryActive(
                taskFrame,
                plan,
                new VersionedCheckpoint(3, checkpoint),
                activation,
                context);
    }

    static final class ScriptedStepRecoveryRepository implements StepRecoveryRepository {
        private final List<PersistenceResult<StepRecoverySnapshot>> results;
        private final AtomicInteger inspectCalls = new AtomicInteger();
        private RuntimeException exception;
        private boolean returnNull;

        ScriptedStepRecoveryRepository(
                List<PersistenceResult<StepRecoverySnapshot>> results) {
            this.results = List.copyOf(results);
        }

        static ScriptedStepRecoveryRepository found(
                PersistedStepRecoveryActive initial,
                PersistedStepRecoveryActive postLease) {
            return new ScriptedStepRecoveryRepository(List.of(
                    PersistenceResult.found(initial),
                    PersistenceResult.found(postLease)));
        }

        @Override
        public PersistenceResult<StepRecoverySnapshot> inspect(PlanId planId) {
            int index = inspectCalls.getAndIncrement();
            if (exception != null) {
                throw exception;
            }
            if (returnNull) {
                return null;
            }
            if (index >= results.size()) {
                throw new AssertionError("unexpected inspect invocation " + index);
            }
            return results.get(index);
        }

        int inspectCalls() {
            return inspectCalls.get();
        }

        void throwWith(RuntimeException exception) {
            this.exception = exception;
        }

        void returnNull() {
            this.returnNull = true;
        }
    }

    static final class ScriptedLeaseRepository implements LeaseRepository {
        private PersistenceResult<LeaseRecord> acquireResult;
        private final AtomicInteger acquireCalls = new AtomicInteger();
        private RuntimeException exception;
        private boolean returnNull;

        ScriptedLeaseRepository(PersistenceResult<LeaseRecord> acquireResult) {
            this.acquireResult = acquireResult;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            acquireCalls.incrementAndGet();
            if (exception != null) {
                throw exception;
            }
            if (returnNull) {
                return null;
            }
            return acquireResult;
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            throw new AssertionError("renew is outside Step recovery composition");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(PlanId planId, String leaseToken) {
            throw new AssertionError("release is outside Step recovery composition");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            throw new AssertionError("find is outside Step recovery composition");
        }

        int acquireCalls() {
            return acquireCalls.get();
        }

        void throwWith(RuntimeException exception) {
            this.exception = exception;
        }

        void returnNull() {
            this.returnNull = true;
        }
    }

    private static PersistedPlanExecutionContextConfirmed confirmedContext(
            PlanId planId,
            String suffix) {
        ProjectVersionRef source = new ProjectVersionRef(
                "project-" + suffix, "version-" + suffix);
        PersistedPlanExecutionContextReserved reservation =
                new PersistedPlanExecutionContextReserved(
                        planId,
                        new WorkspaceMaterializationSpec(
                                new WorkspaceId("workspace-" + suffix),
                                source,
                                new WorkspaceMaterializationLimits(10, 20, 1)),
                        "context-owner-" + suffix,
                        1);
        return new PersistedPlanExecutionContextConfirmed(
                reservation,
                "context-owner-" + suffix,
                1,
                new ContentHash("sha256", "a".repeat(64)));
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
