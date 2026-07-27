package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductExecutionStartTransactions {
    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductLeaseJpaRepository leases;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductExecutionStartCodec codec;
    private final ProductExecutionStartTimeSource timeSource;
    private final EntityManager entityManager;

    ProductExecutionStartTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductLeaseJpaRepository leases,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec codec,
            ProductExecutionStartTimeSource timeSource,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.bootstrapCodec = bootstrapCodec;
        this.leases = leases;
        this.starts = starts;
        this.codec = codec;
        this.timeSource = timeSource;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedExecutionStart> start(
            ExecutionStartRequest request) {
        Optional<ProductPlanBootstrapEntity> locked =
                bootstraps.lockByPlanId(request.planId().value());
        if (locked.isEmpty()) {
            boolean occupied = starts.existsById(request.planId().value())
                    || leases.findFirstByPlanIdOrderByFencingTokenDesc(
                            request.planId().value()).isPresent();
            return occupied
                    ? partial()
                    : rejected(
                            PersistenceErrorCode.NOT_FOUND, "request.planId");
        }

        Optional<ProductExecutionStartEntity> existing =
                starts.findById(request.planId().value());
        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        PersistedPlanBootstrap bootstrap;
        try {
            ProductPlanBootstrapEntity row = locked.get();
            bootstrap = bootstrapCodec.decode(
                    row.payloadFormatVersion(),
                    row.payloadSha256(),
                    row.payloadJson());
        } catch (RuntimeException exception) {
            return partial();
        }
        if (!canonicalBootstrap(bootstrap, request)) {
            return partial();
        }

        Instant effectiveNow =
                timeSource.observe().truncatedTo(ChronoUnit.MICROS);
        Optional<ProductLeaseEntity> current =
                leases.findFirstByPlanIdOrderByFencingTokenDesc(
                        request.planId().value());
        if (current.isEmpty() || current.get().releasedAt() != null) {
            return rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        }
        ProductLeaseEntity lease = current.get();
        if (!lease.leaseToken().equals(request.leaseToken())) {
            return rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != request.fencingToken()) {
            return rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (!effectiveNow.isBefore(lease.expiresAt())) {
            return rejected(
                    PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        }

        PersistenceResult<PersistedExecutionStart> transition =
                validateTransition(request, bootstrap);
        if (transition != null) {
            return transition;
        }

        Optional<ProductExecutionStartEntity> sameEvent =
                starts.findByStartEventId(request.startEvent().id().value());
        if (sameEvent.isPresent()) {
            return rejected(
                    PersistenceErrorCode.CONFLICTING_REPLAY,
                    "request.startEvent.id");
        }

        PersistedExecutionStart result = new PersistedExecutionStart(
                request.planId(),
                lease.ownerId(),
                lease.fencingToken(),
                request.startEvent(),
                new VersionedCheckpoint(2, request.startedCheckpoint()));
        ProductExecutionStartEntity entity = new ProductExecutionStartEntity(
                request.planId().value(),
                request.startEvent().id().value(),
                lease.ownerId(),
                lease.fencingToken(),
                codec.encodeRequest(request),
                codec.encodeResult(result),
                effectiveNow);
        entityManager.persist(entity);
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean eventIdExists(String eventId) {
        return starts.findByStartEventId(eventId).isPresent();
    }

    private PersistenceResult<PersistedExecutionStart> replay(
            ProductExecutionStartEntity entity,
            ExecutionStartRequest request) {
        try {
            ExecutionStartRequest storedRequest = codec.decodeRequest(
                    entity.requestFormatVersion(),
                    entity.requestSha256(),
                    entity.requestJson());
            PersistedExecutionStart storedResult = codec.decodeResult(
                    entity.resultFormatVersion(),
                    entity.resultSha256(),
                    entity.resultJson());
            if (!entity.planId().equals(storedRequest.planId().value())
                    || !entity.planId().equals(storedResult.planId().value())
                    || !entity.startEventId().equals(
                            storedRequest.startEvent().id().value())
                    || !entity.startEventId().equals(
                            storedResult.startEvent().id().value())
                    || !entity.leaseOwnerId().equals(
                            storedResult.leaseOwnerId())
                    || entity.fencingToken() != storedResult.fencingToken()
                    || storedResult.fencingToken()
                            != storedRequest.fencingToken()
                    || !storedResult.startEvent().equals(
                            storedRequest.startEvent())
                    || !storedResult.startedCheckpoint().checkpoint().equals(
                            storedRequest.startedCheckpoint())) {
                return partial();
            }
            return storedRequest.equals(request)
                    ? PersistenceResult.replayed(storedResult)
                    : rejected(
                            PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.planId");
        } catch (RuntimeException exception) {
            return partial();
        }
    }

    private static boolean canonicalBootstrap(
            PersistedPlanBootstrap bootstrap, ExecutionStartRequest request) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        VersionedCheckpoint source = bootstrap.initialCheckpoint();
        if (!plan.id().equals(request.planId())
                || !task.id().equals(plan.taskFrameId())
                || source.version() != 1) {
            return false;
        }
        Checkpoint checkpoint = source.checkpoint();
        PlanRevision revision = plan.latestRevision();
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.taskFrameId().equals(task.id())
                && checkpoint.planId().equals(plan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && revision.completedFacts().isEmpty()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(state ->
                                state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }

    private static PersistenceResult<PersistedExecutionStart> validateTransition(
            ExecutionStartRequest request,
            PersistedPlanBootstrap bootstrap) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        Checkpoint source = bootstrap.initialCheckpoint().checkpoint();
        EventEnvelope event = request.startEvent();
        if (!event.planId().equals(request.planId())) {
            return rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT,
                    "request.startEvent.planId");
        }
        if (!event.taskFrameId().equals(task.id())) {
            return rejected(
                    PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.startEvent.taskFrameId");
        }
        if (event.sequence() != 1) {
            return rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.startEvent.sequence");
        }
        Checkpoint candidate = request.startedCheckpoint();
        if (candidate.lastEventSequence() != 1) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.startedCheckpoint.lastEventSequence");
        }
        if (!canonicalTarget(candidate, task, plan)
                || !CheckpointValidators
                        .validate(candidate, task, plan, source)
                        .isEmpty()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.startedCheckpoint");
        }
        return null;
    }

    private static boolean canonicalTarget(
            Checkpoint checkpoint, TaskFrame task, Plan plan) {
        PlanRevision latest = plan.latestRevision();
        Set<PlanStepId> ids = latest.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.planId().equals(plan.id())
                && checkpoint.taskFrameId().equals(task.id())
                && checkpoint.revisionId().equals(latest.id())
                && checkpoint.revisionNumber() == latest.number()
                && latest.completedFacts().isEmpty()
                && checkpoint.planState() == PlanExecutionState.ACTIVE
                && checkpoint.stepStates().keySet().equals(ids)
                && checkpoint.stepStates().values().stream().allMatch(
                        state -> state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }

    private static PersistenceResult<PersistedExecutionStart> partial() {
        return rejected(
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }
}
