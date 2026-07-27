package io.paperagent.v2.runtime.execution.recovery.composition;

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
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.ExecutionStartRepository;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;
import io.paperagent.v2.runtime.execution.recovery.materialization.DeterministicRecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializationRequest;
import io.paperagent.v2.runtime.execution.recovery.materialization.RecoveryReadyExecutionStartMaterializer;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class ExecutionStartRecoveryTestFixtures {
    static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");
    static final String OWNER = "worker-primary";
    static final String TOKEN = "lease-token-secret-primary";
    static final Object NULL = new Object();

    private ExecutionStartRecoveryTestFixtures() {
    }

    static PersistedPlanBootstrap bootstrap(String suffix) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStep first = step("step-first-" + suffix, Set.of());
        PlanStep second = step(
                "step-second-" + suffix,
                Set.of(first.id()));
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "Prepare " + suffix,
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve facts"),
                Optional.empty(),
                executionProfile(),
                T0);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-one-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0.plusSeconds(1),
                List.of(first, second),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                revision.number(),
                0,
                PlanExecutionState.NOT_STARTED,
                Map.of(
                        first.id(), StepExecutionState.NOT_STARTED,
                        second.id(), StepExecutionState.NOT_STARTED),
                List.of(),
                T0.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, checkpoint));
    }

    static PersistedExecutionStartReady ready(String suffix) {
        PersistedPlanBootstrap bootstrap = bootstrap(suffix);
        return new PersistedExecutionStartReady(
                bootstrap,
                bootstrap.plan());
    }

    static PersistedExecutionStartReady revisedReady(
            PersistedExecutionStartReady original,
            String suffix) {
        PlanRevision previous = original.currentPlan().latestRevision();
        PlanStep first = step("step-r2-first-" + suffix, Set.of());
        PlanStep second = step(
                "step-r2-second-" + suffix,
                Set.of(first.id()));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-two-" + suffix),
                original.bootstrap().taskFrame().id(),
                previous.number() + 1,
                Optional.of(previous.id()),
                "pre-start revision " + suffix,
                T0.plusSeconds(3),
                List.of(first, second),
                Map.of());
        Plan current = new Plan(
                original.currentPlan().id(),
                original.currentPlan().taskFrameId(),
                List.of(previous, revision));
        return new PersistedExecutionStartReady(
                original.bootstrap(),
                current);
    }

    static FreshExecutionStartAttempt attempt(String suffix) {
        return attempt(suffix, OWNER, TOKEN);
    }

    static FreshExecutionStartAttempt attempt(
            String suffix,
            String owner,
            String token) {
        return new FreshExecutionStartAttempt(
                owner,
                token,
                T0.plusSeconds(60),
                new ExecutionStartEventDraft(
                        new EventId("event-" + suffix),
                        T0.plusSeconds(3),
                        new EventType("execution-start"),
                        Optional.empty(),
                        "correlation-" + suffix,
                        new InlineEventPayload(
                                new ObjectValue(Map.of(
                                        "kind",
                                        new ObjectValue(Map.of()))))),
                T0.plusSeconds(4));
    }

    static MaterializedExecutionStart materialized(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt) {
        return new DeterministicRecoveryReadyExecutionStartMaterializer()
                .materialize(
                        new RecoveryReadyExecutionStartMaterializationRequest(
                                ready,
                                attempt.eventDraft(),
                                attempt.checkpointCreatedAt()));
    }

    static LeaseRecord lease(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt,
            long fence) {
        return new LeaseRecord(
                ready.planId(),
                attempt.leaseOwnerId(),
                attempt.leaseToken(),
                fence,
                T0.plusSeconds(10),
                attempt.leaseExpiresAt());
    }

    static PersistedExecutionStart persisted(
            PersistedExecutionStartReady ready,
            LeaseRecord lease,
            MaterializedExecutionStart materialized) {
        return new PersistedExecutionStart(
                ready.planId(),
                lease.ownerId(),
                lease.fencingToken(),
                materialized.startEvent(),
                new VersionedCheckpoint(
                        2,
                        materialized.startedCheckpoint()));
    }

    static PersistedExecutionStartCommitted committed(
            PersistedExecutionStartReady ready,
            PersistedExecutionStart persisted) {
        return new PersistedExecutionStartCommitted(
                ready.bootstrap(),
                ready.currentPlan(),
                persisted);
    }

    static ExecutionStartRecoveryRequest request(
            PersistedExecutionStartReady ready,
            FreshExecutionStartAttempt attempt) {
        return new ExecutionStartRecoveryRequest(
                ready.planId(),
                Optional.ofNullable(attempt));
    }

    static PlanStep step(
            String id,
            Set<PlanStepId> dependencies) {
        return new PlanStep(
                new PlanStepId(id),
                "execute " + id,
                "produce " + id,
                dependencies,
                List.of("result exists"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
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

    static final class ScriptedRecoveryRepository
            implements ExecutionStartRecoveryRepository {
        private final List<Object> results;
        private final List<String> trace;
        private int index;
        final AtomicInteger calls = new AtomicInteger();

        ScriptedRecoveryRepository(
                List<Object> results,
                List<String> trace) {
            this.results = List.copyOf(results);
            this.trace = trace;
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<ExecutionStartRecoverySnapshot> inspect(
                PlanId planId) {
            int call = calls.incrementAndGet();
            trace.add("I" + call);
            Object scripted = results.get(index++);
            if (scripted instanceof RuntimeException exception) {
                throw exception;
            }
            if (scripted == NULL) {
                return null;
            }
            return (PersistenceResult<ExecutionStartRecoverySnapshot>) scripted;
        }
    }

    static final class ScriptedMaterializer
            implements RecoveryReadyExecutionStartMaterializer {
        private final List<Object> results;
        private final List<String> trace;
        private int index;
        final List<RecoveryReadyExecutionStartMaterializationRequest>
                requests = new ArrayList<>();

        ScriptedMaterializer(List<Object> results, List<String> trace) {
            this.results = List.copyOf(results);
            this.trace = trace;
        }

        @Override
        public MaterializedExecutionStart materialize(
                RecoveryReadyExecutionStartMaterializationRequest request) {
            requests.add(request);
            trace.add("P" + requests.size());
            Object scripted = results.get(index++);
            if (scripted instanceof RuntimeException exception) {
                throw exception;
            }
            if (scripted == NULL) {
                return null;
            }
            return (MaterializedExecutionStart) scripted;
        }
    }

    static final class ScriptedLeaseRepository
            implements LeaseRepository {
        private final Object result;
        private final List<String> trace;
        final AtomicInteger acquireCalls = new AtomicInteger();
        final AtomicInteger findCalls = new AtomicInteger();
        final AtomicInteger renewCalls = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        PlanId planId;
        String ownerId;
        String leaseToken;
        Instant expiresAt;

        ScriptedLeaseRepository(Object result, List<String> trace) {
            this.result = result;
            this.trace = trace;
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId candidatePlanId,
                String candidateOwnerId,
                String candidateLeaseToken,
                Instant candidateExpiresAt) {
            acquireCalls.incrementAndGet();
            trace.add("A");
            planId = candidatePlanId;
            ownerId = candidateOwnerId;
            leaseToken = candidateLeaseToken;
            expiresAt = candidateExpiresAt;
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            if (result == NULL) {
                return null;
            }
            return (PersistenceResult<LeaseRecord>) result;
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
                throw new AssertionError("forbidden lease method was used");
            }
        }
    }

    static final class ScriptedStartRepository
            implements ExecutionStartRepository {
        private final Object result;
        private final List<String> trace;
        final AtomicInteger calls = new AtomicInteger();
        ExecutionStartRequest request;

        ScriptedStartRepository(Object result, List<String> trace) {
            this.result = result;
            this.trace = trace;
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<PersistedExecutionStart> start(
                ExecutionStartRequest candidate) {
            calls.incrementAndGet();
            trace.add("S");
            request = candidate;
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            if (result == NULL) {
                return null;
            }
            return (PersistenceResult<PersistedExecutionStart>) result;
        }
    }
}
