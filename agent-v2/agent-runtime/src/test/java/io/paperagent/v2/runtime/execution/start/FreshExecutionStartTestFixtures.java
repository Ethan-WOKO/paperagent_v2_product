package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
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
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.DeterministicExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.ExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class FreshExecutionStartTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");
    static final String OWNER = "worker-primary";
    static final String TOKEN = "lease-token-secret-primary";

    private FreshExecutionStartTestFixtures() {
    }

    static PersistedPlanBootstrap bootstrap(String suffix) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "Prepare " + suffix,
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve evidence"),
                Optional.empty(),
                executionProfile(),
                T0);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0.plusSeconds(1),
                List.of(new PlanStep(
                        stepId,
                        "execute " + suffix,
                        "deliver " + suffix,
                        Set.of(),
                        List.of("result exists"),
                        new BoundedExecutionHints(
                                2,
                                Duration.ofMinutes(2)))),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                0,
                PlanExecutionState.NOT_STARTED,
                Map.of(stepId, StepExecutionState.NOT_STARTED),
                List.of(),
                T0.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, checkpoint));
    }

    static FreshExecutionStartAttempt attempt(String suffix) {
        return new FreshExecutionStartAttempt(
                OWNER,
                TOKEN,
                T0.plusSeconds(60),
                new ExecutionStartEventDraft(
                        new EventId("event-" + suffix),
                        T0.plusSeconds(3),
                        new EventType("execution-start"),
                        Optional.empty(),
                        "correlation-" + suffix,
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                T0.plusSeconds(4));
    }

    static MaterializedExecutionStart materialized(
            PersistedPlanBootstrap bootstrap,
            FreshExecutionStartAttempt attempt) {
        return new DeterministicExecutionStartMaterializer().materialize(
                new ExecutionStartMaterializationRequest(
                        bootstrap,
                        attempt.eventDraft(),
                        attempt.checkpointCreatedAt()));
    }

    static LeaseRecord lease(
            PersistedPlanBootstrap bootstrap,
            FreshExecutionStartAttempt attempt,
            long fencingToken) {
        return new LeaseRecord(
                bootstrap.plan().id(),
                attempt.leaseOwnerId(),
                attempt.leaseToken(),
                fencingToken,
                T0.plusSeconds(10),
                attempt.leaseExpiresAt());
    }

    static PersistedExecutionStart persisted(
            PersistedPlanBootstrap bootstrap,
            LeaseRecord lease,
            MaterializedExecutionStart materialized) {
        return new PersistedExecutionStart(
                bootstrap.plan().id(),
                lease.ownerId(),
                lease.fencingToken(),
                materialized.startEvent(),
                new VersionedCheckpoint(
                        2,
                        materialized.startedCheckpoint()));
    }

    static FreshExecutionStartRequest request(
            PersistenceResult<PersistedPlanBootstrap> bootstrapResult,
            FreshExecutionStartAttempt attempt) {
        return new FreshExecutionStartRequest(
                bootstrapResult,
                Optional.ofNullable(attempt));
    }

    static ExecutionProfile executionProfile() {
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

    @FunctionalInterface
    interface LeaseAcquire {
        PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt);
    }

    static final class ScriptedLeaseRepository implements LeaseRepository {
        private final LeaseAcquire behavior;
        final AtomicInteger acquireCalls = new AtomicInteger();
        final AtomicInteger findCalls = new AtomicInteger();
        final AtomicInteger renewCalls = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        PlanId planId;
        String ownerId;
        String leaseToken;
        Instant expiresAt;

        ScriptedLeaseRepository(LeaseAcquire behavior) {
            this.behavior = behavior;
        }

        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId candidatePlanId,
                String candidateOwnerId,
                String candidateLeaseToken,
                Instant candidateExpiresAt) {
            if (acquireCalls.incrementAndGet() != 1) {
                throw new AssertionError(
                        "lease acquire must not be retried");
            }
            planId = candidatePlanId;
            ownerId = candidateOwnerId;
            leaseToken = candidateLeaseToken;
            expiresAt = candidateExpiresAt;
            return behavior.acquire(
                    candidatePlanId,
                    candidateOwnerId,
                    candidateLeaseToken,
                    candidateExpiresAt);
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId candidatePlanId,
                String candidateLeaseToken,
                Instant candidateExpiresAt) {
            renewCalls.incrementAndGet();
            return null;
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId candidatePlanId,
                String candidateLeaseToken) {
            releaseCalls.incrementAndGet();
            return null;
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId candidatePlanId) {
            findCalls.incrementAndGet();
            return null;
        }

        void assertOnlyAcquireWasUsed() {
            if (findCalls.get() != 0
                    || renewCalls.get() != 0
                    || releaseCalls.get() != 0) {
                throw new AssertionError(
                        "forbidden lease method was called");
            }
        }
    }

    static final class ScriptedStartRepository
            implements ExecutionStartRepository {
        private final java.util.function.Function<
                ExecutionStartRequest,
                PersistenceResult<PersistedExecutionStart>> behavior;
        final AtomicInteger calls = new AtomicInteger();
        ExecutionStartRequest request;

        ScriptedStartRepository(
                java.util.function.Function<
                        ExecutionStartRequest,
                        PersistenceResult<PersistedExecutionStart>> behavior) {
            this.behavior = behavior;
        }

        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest candidate) {
            if (calls.incrementAndGet() != 1) {
                throw new AssertionError(
                        "atomic start must not be retried");
            }
            request = candidate;
            return behavior.apply(candidate);
        }
    }
}
