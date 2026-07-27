package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
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
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextSnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;
import io.paperagent.v2.workspace.WorkspaceFileStat;
import io.paperagent.v2.workspace.WorkspacePort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class PlanExecutionContextCompositionTestFixtures {
    static final String SECRET = "opaque-secret-value";
    static final Object NULL = new Object();
    static final Instant T0 = Instant.parse("2026-07-25T00:00:00Z");
    static final PlanId PLAN_ID = new PlanId("plan-" + SECRET);
    static final ContentHash FINGERPRINT =
            new ContentHash("sha256", "a".repeat(64));
    static final ContentHash WRONG_FINGERPRINT =
            new ContentHash("sha256", "b".repeat(64));

    private PlanExecutionContextCompositionTestFixtures() {
    }

    static WorkspaceMaterializationSpec spec(String suffix) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId("workspace-" + suffix + "-" + SECRET),
                new ProjectVersionRef(
                        "project-" + suffix + "-" + SECRET,
                        "version-" + suffix + "-" + SECRET),
                new WorkspaceMaterializationLimits(1024, 8192, 32));
    }

    static PersistedPlanExecutionContextConfirmed persistedContext(
            WorkspaceMaterializationSpec spec,
            ContentHash fingerprint) {
        return new PersistedPlanExecutionContextConfirmed(
                new PersistedPlanExecutionContextReserved(
                        PLAN_ID,
                        spec,
                        "reservation-owner-" + SECRET,
                        7),
                "confirmation-owner-" + SECRET,
                11,
                fingerprint);
    }

    static VerifiedWorkspaceMaterialization verifiedWorkspace(
            WorkspaceMaterializationSpec spec,
            ContentHash fingerprint) {
        return new VerifiedWorkspaceMaterialization(spec, fingerprint);
    }

    static PersistedExecutionStartReady ready(
            String suffix,
            Optional<ProjectVersionRef> source) {
        TaskFrameId taskFrameId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        PlanStep step = new PlanStep(
                stepId,
                "execute " + suffix,
                "produce " + suffix,
                Set.of(),
                List.of("done"),
                new BoundedExecutionHints(
                        2,
                        Duration.ofMinutes(2)));
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "Prepare " + suffix,
                List.of("paper"),
                List.of("workspace"),
                List.of(),
                source,
                executionProfile(),
                T0);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                T0.plusSeconds(1),
                List.of(step),
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
        PersistedPlanBootstrap bootstrap = new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, checkpoint));
        return new PersistedExecutionStartReady(bootstrap, plan);
    }

    static PersistedExecutionStartCommitted committed(
            String suffix,
            Optional<ProjectVersionRef> source) {
        PersistedExecutionStartReady ready = ready(suffix, source);
        PlanRevision revision = ready.currentPlan().latestRevision();
        EventEnvelope event = new EventEnvelope(
                new EventId("event-" + suffix),
                ready.bootstrap().taskFrame().id(),
                ready.planId(),
                1,
                T0.plusSeconds(3),
                new EventType("execution-start"),
                Optional.empty(),
                "correlation-" + suffix,
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                ready.bootstrap().taskFrame().id(),
                ready.planId(),
                revision.id(),
                revision.number(),
                1,
                PlanExecutionState.ACTIVE,
                Map.of(
                        revision.steps().get(0).id(),
                        StepExecutionState.NOT_STARTED),
                List.of(),
                T0.plusSeconds(4));
        PersistedExecutionStart executionStart =
                new PersistedExecutionStart(
                        ready.planId(),
                        "execution-owner-" + suffix,
                        3,
                        event,
                        new VersionedCheckpoint(2, checkpoint));
        return new PersistedExecutionStartCommitted(
                ready.bootstrap(),
                ready.currentPlan(),
                executionStart);
    }

    static PersistenceFailure executionNotFound() {
        return failure(PersistenceErrorCode.NOT_FOUND, "planId");
    }

    static PersistenceFailure executionPartial() {
        return failure(
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    static PersistenceFailure executionAdvanced() {
        return failure(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");
    }

    static PersistenceFailure contextPartial() {
        return failure(
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                "planExecutionContext");
    }

    static List<PersistenceFailure> acquireFailures() {
        return List.of(
                failure(PersistenceErrorCode.INVALID_ARGUMENT, "expiresAt"),
                failure(PersistenceErrorCode.NOT_FOUND, "planId"),
                failure(PersistenceErrorCode.LEASE_HELD, "planId"),
                failure(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "leaseToken"));
    }

    static List<PersistenceFailure> reserveFailures() {
        return List.of(
                contextPartial(),
                failure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"),
                failure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.materializationSpec.workspaceId"),
                failure(
                        PersistenceErrorCode.NOT_FOUND,
                        "request.planId"),
                failure(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "planExecutionContext.source"),
                failure(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "request.materializationSpec"
                                + ".sourceProjectVersion"),
                failure(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId"),
                failure(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "request.leaseToken"),
                failure(
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                        "request.fencingToken"),
                failure(
                        PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId"),
                failure(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionId"),
                failure(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionNumber"),
                failure(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedCheckpointVersion"),
                failure(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedEventHeadSequence"));
    }

    static List<PersistenceFailure> confirmFailures() {
        return List.of(
                contextPartial(),
                failure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.planId"),
                failure(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.materializationSpec"),
                failure(
                        PersistenceErrorCode.NOT_FOUND,
                        "request.planId"),
                failure(
                        PersistenceErrorCode.NOT_FOUND,
                        "planExecutionContext"),
                failure(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                        "planExecutionContext.source"),
                failure(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId"),
                failure(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "request.leaseToken"),
                failure(
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                        "request.fencingToken"),
                failure(
                        PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId"));
    }

    static PersistenceFailure secretNonCanonicalFailure() {
        return failure(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "adapter." + SECRET);
    }

    static PersistenceFailure failure(
            PersistenceErrorCode code,
            String path) {
        return new PersistenceFailure(code, path);
    }

    private static ExecutionProfile executionProfile() {
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

    static ActionThenThrow actionThenThrow(
            Runnable action,
            RuntimeException exception) {
        return new ActionThenThrow(action, exception);
    }

    static final class ScriptedExecutionStartRecoveryRepository
            implements ExecutionStartRecoveryRepository {
        private final Deque<Object> inspections;
        private final List<String> trace;
        final AtomicInteger inspectCalls = new AtomicInteger();
        final List<PlanId> inspectedPlanIds = new ArrayList<>();

        ScriptedExecutionStartRecoveryRepository(
                List<Object> inspections,
                List<String> trace) {
            this.inspections = queue(inspections);
            this.trace = trace;
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<ExecutionStartRecoverySnapshot> inspect(
                PlanId planId) {
            inspectedPlanIds.add(planId);
            trace.add("execution.inspect");
            inspectCalls.incrementAndGet();
            return (PersistenceResult<ExecutionStartRecoverySnapshot>)
                    scripted(inspections, "execution.inspect");
        }
    }

    static final class ScriptedPlanExecutionContextRepository
            implements PlanExecutionContextRepository {
        private final Deque<Object> inspections;
        private final Deque<Object> reservations;
        private final Deque<Object> confirmations;
        private final List<String> trace;
        final AtomicInteger inspectCalls = new AtomicInteger();
        final AtomicInteger reserveCalls = new AtomicInteger();
        final AtomicInteger confirmCalls = new AtomicInteger();
        final List<PlanId> inspectedPlanIds = new ArrayList<>();
        final List<PlanExecutionContextReservationRequest>
                reservationRequests = new ArrayList<>();
        final List<PlanExecutionContextConfirmationRequest>
                confirmationRequests = new ArrayList<>();

        ScriptedPlanExecutionContextRepository(
                List<Object> inspections,
                List<Object> reservations,
                List<Object> confirmations,
                List<String> trace) {
            this.inspections = queue(inspections);
            this.reservations = queue(reservations);
            this.confirmations = queue(confirmations);
            this.trace = trace;
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<PersistedPlanExecutionContextReserved>
                reserve(PlanExecutionContextReservationRequest request) {
            reservationRequests.add(request);
            trace.add("context.reserve");
            reserveCalls.incrementAndGet();
            return (PersistenceResult<PersistedPlanExecutionContextReserved>)
                    scripted(reservations, "context.reserve");
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<PersistedPlanExecutionContextConfirmed>
                confirm(PlanExecutionContextConfirmationRequest request) {
            confirmationRequests.add(request);
            trace.add("context.confirm");
            confirmCalls.incrementAndGet();
            return (PersistenceResult<PersistedPlanExecutionContextConfirmed>)
                    scripted(confirmations, "context.confirm");
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<PlanExecutionContextSnapshot> inspect(
                PlanId planId) {
            inspectedPlanIds.add(planId);
            trace.add("context.inspect");
            inspectCalls.incrementAndGet();
            return (PersistenceResult<PlanExecutionContextSnapshot>)
                    scripted(inspections, "context.inspect");
        }
    }

    static final class ScriptedLeaseRepository implements LeaseRepository {
        private final Deque<Object> acquisitions;
        private final List<String> trace;
        final AtomicInteger acquireCalls = new AtomicInteger();
        final AtomicInteger renewCalls = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        final AtomicInteger findCalls = new AtomicInteger();
        final List<PlanId> planIds = new ArrayList<>();
        final List<String> ownerIds = new ArrayList<>();
        final List<String> leaseTokens = new ArrayList<>();
        final List<Instant> expirations = new ArrayList<>();

        ScriptedLeaseRepository(
                List<Object> acquisitions,
                List<String> trace) {
            this.acquisitions = queue(acquisitions);
            this.trace = trace;
        }

        @SuppressWarnings("unchecked")
        @Override
        public PersistenceResult<LeaseRecord> acquire(
                PlanId planId,
                String ownerId,
                String leaseToken,
                Instant expiresAt) {
            planIds.add(planId);
            ownerIds.add(ownerId);
            leaseTokens.add(leaseToken);
            expirations.add(expiresAt);
            trace.add("lease.acquire");
            acquireCalls.incrementAndGet();
            return (PersistenceResult<LeaseRecord>)
                    scripted(acquisitions, "lease.acquire");
        }

        @Override
        public PersistenceResult<LeaseRecord> renew(
                PlanId planId,
                String leaseToken,
                Instant expiresAt) {
            renewCalls.incrementAndGet();
            throw new AssertionError("lease.renew is forbidden");
        }

        @Override
        public PersistenceResult<LeaseRecord> release(
                PlanId planId,
                String leaseToken) {
            releaseCalls.incrementAndGet();
            throw new AssertionError("lease.release is forbidden");
        }

        @Override
        public PersistenceResult<LeaseRecord> find(PlanId planId) {
            findCalls.incrementAndGet();
            throw new AssertionError("lease.find is forbidden");
        }
    }

    static final class ScriptedWorkspacePort implements WorkspacePort {
        private final Deque<Object> inspections;
        private final Deque<Object> materializations;
        private final List<String> trace;
        final AtomicInteger inspectCalls = new AtomicInteger();
        final AtomicInteger materializeCalls = new AtomicInteger();
        final AtomicInteger unexpectedCalls = new AtomicInteger();
        final List<WorkspaceMaterializationSpec> inspectedSpecs =
                new ArrayList<>();
        final List<WorkspaceMaterializationSpec> materializedSpecs =
                new ArrayList<>();

        ScriptedWorkspacePort(
                List<Object> inspections,
                List<Object> materializations,
                List<String> trace) {
            this.inspections = queue(inspections);
            this.materializations = queue(materializations);
            this.trace = trace;
        }

        @Override
        public VerifiedWorkspaceMaterialization materialize(
                WorkspaceMaterializationSpec spec) {
            materializedSpecs.add(spec);
            trace.add("workspace.materialize");
            materializeCalls.incrementAndGet();
            return (VerifiedWorkspaceMaterialization) scripted(
                    materializations,
                    "workspace.materialize");
        }

        @Override
        public VerifiedWorkspaceMaterialization inspectMaterialization(
                WorkspaceMaterializationSpec spec) {
            inspectedSpecs.add(spec);
            trace.add("workspace.inspect");
            inspectCalls.incrementAndGet();
            return (VerifiedWorkspaceMaterialization) scripted(
                    inspections,
                    "workspace.inspect");
        }

        @Override
        public List<WorkspaceFileStat> list(WorkspaceRef workspace) {
            return unexpected("workspace.list");
        }

        @Override
        public WorkspaceFileStat stat(
                WorkspaceRef workspace,
                ProjectPath path) {
            return unexpected("workspace.stat");
        }

        @Override
        public byte[] read(WorkspaceRef workspace, ProjectPath path) {
            return unexpected("workspace.read");
        }

        @Override
        public void create(
                WorkspaceRef workspace,
                ProjectPath path,
                byte[] content) {
            unexpected("workspace.create");
        }

        @Override
        public void replace(
                WorkspaceRef workspace,
                ProjectPath path,
                byte[] content) {
            unexpected("workspace.replace");
        }

        @Override
        public void delete(WorkspaceRef workspace, ProjectPath path) {
            unexpected("workspace.delete");
        }

        @Override
        public void move(
                WorkspaceRef workspace,
                ProjectPath source,
                ProjectPath target) {
            unexpected("workspace.move");
        }

        @Override
        public WorkspaceDiff diff(
                WorkspaceRef workspace,
                DiffId diffId,
                Instant createdAt) {
            return unexpected("workspace.diff");
        }

        @Override
        public void cleanup(WorkspaceRef workspace) {
            unexpected("workspace.cleanup");
        }

        private <T> T unexpected(String operation) {
            unexpectedCalls.incrementAndGet();
            throw new AssertionError(operation + " is forbidden");
        }
    }

    private static Deque<Object> queue(List<Object> values) {
        return new ArrayDeque<>(values);
    }

    private static Object scripted(
            Deque<Object> values,
            String operation) {
        if (values.isEmpty()) {
            throw new AssertionError(
                    operation + " has no scripted result");
        }
        Object value = values.removeFirst();
        if (value == NULL) {
            return null;
        }
        if (value instanceof ActionThenThrow scriptedFailure) {
            scriptedFailure.action().run();
            throw scriptedFailure.exception();
        }
        if (value instanceof RuntimeException exception) {
            throw exception;
        }
        return value;
    }

    record ActionThenThrow(
            Runnable action,
            RuntimeException exception) {
        ActionThenThrow {
            if (action == null || exception == null) {
                throw new IllegalArgumentException(
                        "action and exception are required");
            }
        }
    }
}
