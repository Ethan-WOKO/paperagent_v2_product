package com.yanban.api.agent.v2.chain.persistence;

import com.yanban.api.agent.v2.persistence.ProductStepRecoveryTransactions;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedPlanReplan;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanReplanRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class ProductPlanReplanTransactions {
    private static final String ROOT = "planReplan";
    private static final String BINDING_NOT_FOUND =
            "CHAIN_TASK_BINDING_NOT_FOUND";
    private static final String BINDING_AMBIGUOUS =
            "CHAIN_TASK_BINDING_AMBIGUOUS";

    private final ProductPlanReplanJpaRepository rows;
    private final ProductPlanReplanMarkerReader markers;
    private final ProductPlanReplanCodec codec;
    private final ProductStepRecoveryTransactions recovery;
    private final ProductChainTimeSource time;
    private final EntityManager entityManager;

    public ProductPlanReplanTransactions(
            ProductPlanReplanJpaRepository rows,
            ProductPlanReplanMarkerReader markers,
            ProductPlanReplanCodec codec,
            ProductStepRecoveryTransactions recovery,
            ProductChainTimeSource time,
            EntityManager entityManager) {
        this.rows = rows;
        this.markers = markers;
        this.codec = codec;
        this.recovery = recovery;
        this.time = time;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedPlanReplan> replan(
            PlanReplanRequest request) {
        Binding binding = lockBinding(request.planId().value());
        if (binding.failure() != null) {
            return binding.failure();
        }
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectLocked(request.planId());
        if (inspected.outcome() != PersistenceOutcome.FOUND) {
            return inspected.failure().isPresent()
                    ? partial() : rejected(PersistenceErrorCode.NOT_FOUND,
                            "request.planId");
        }
        ProductPlanReplanEntity existing = rows.findByReplanEventId(
                request.replanEvent().id().value()).orElse(null);
        if (existing != null) {
            return existing.taskId().equals(binding.taskId())
                    ? replay(existing, request, binding.taskId()) : partial();
        }
        if (!(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryReady ready)
                || !eligible(ready)) {
            return rejected(PersistenceErrorCode.PLAN_REPLAN_NOT_ELIGIBLE,
                    ROOT);
        }

        Lease lease = liveLease(request);
        if (lease.failure() != null) {
            return lease.failure();
        }
        PersistenceResult<PersistedPlanReplan> invalid = validate(
                request, ready);
        if (invalid != null) {
            return invalid;
        }
        if (eventOccupied(request.replanEvent().id().value())) {
            return conflict();
        }

        Plan replannedPlan = replannedPlan(request, ready.plan());
        if (replannedPlan == null) {
            return rejected(PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                    "request.replannedRevision");
        }
        PersistenceResult<PersistedPlanReplan> checkpointFailure =
                validateCheckpoint(request, ready, replannedPlan);
        if (checkpointFailure != null) {
            return checkpointFailure;
        }

        VersionedCheckpoint checkpoint = new VersionedCheckpoint(
                request.expectedCheckpointVersion() + 1,
                request.replannedCheckpoint());
        PersistedPlanReplan result = new PersistedPlanReplan(
                request.planId(), lease.ownerId(), lease.fencingToken(),
                request.replanEvent(), request.replannedRevision(), checkpoint);
        ProductPlanReplanEntity row = new ProductPlanReplanEntity(
                binding.taskId(), request.planId().value(),
                request.expectedEventHeadSequence(),
                request.expectedRevisionId().value(),
                request.expectedRevisionNumber(),
                request.replannedRevision().id().value(),
                request.replannedRevision().number(),
                request.expectedCheckpointVersion(), checkpoint.version(),
                request.replanEvent().id().value(),
                request.replanEvent().sequence(), lease.ownerId(),
                lease.fencingToken(), codec.encodeRequest(request),
                codec.encodeResult(result), time.now());
        entityManager.persist(row);
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedPlanReplan> classify(
            PlanReplanRequest request) {
        Binding binding = lockBinding(request.planId().value());
        if (binding.failure() != null) {
            return binding.failure();
        }
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectLocked(request.planId());
        if (inspected.outcome() != PersistenceOutcome.FOUND) {
            return inspected.failure().isPresent()
                    ? partial() : rejected(PersistenceErrorCode.NOT_FOUND,
                            "request.planId");
        }
        ProductPlanReplanEntity event = rows.findByReplanEventId(
                request.replanEvent().id().value()).orElse(null);
        if (event != null) {
            return event.taskId().equals(binding.taskId())
                    ? replay(event, request, binding.taskId()) : partial();
        }
        List<ProductPlanReplanEntity> source = rows
                .findAllByPlanIdOrderBySourceEventSequenceAsc(
                        request.planId().value()).stream()
                .filter(row -> row.sourceEventSequence()
                        == request.expectedEventHeadSequence())
                .toList();
        for (ProductPlanReplanEntity winner : source) {
            try {
                markers.decode(winner, binding.taskId());
            } catch (RuntimeException corrupt) {
                return partial();
            }
        }
        if (!source.isEmpty()
                || eventOccupied(request.replanEvent().id().value())) {
            return conflict();
        }
        return null;
    }

    private PersistenceResult<PersistedPlanReplan> replay(
            ProductPlanReplanEntity row, PlanReplanRequest request,
            String expectedTaskId) {
        try {
            ProductPlanReplanMarkerReader.Marker marker =
                    markers.decode(row, expectedTaskId);
            return marker.request().equals(request)
                    ? PersistenceResult.replayed(marker.result()) : conflict();
        } catch (RuntimeException corrupt) {
            return partial();
        }
    }

    @SuppressWarnings("unchecked")
    private Binding lockBinding(String planId) {
        List<String> taskIds = entityManager.createNativeQuery("""
                SELECT task_id
                  FROM agent_v2_chain_plan_bindings
                 WHERE plan_id = :planId
                """, String.class).setParameter("planId", planId)
                .getResultList();
        if (taskIds.isEmpty()) {
            return new Binding(null, rejected(PersistenceErrorCode.NOT_FOUND,
                    BINDING_NOT_FOUND));
        }
        if (taskIds.size() != 1) {
            return new Binding(null, rejected(
                    PersistenceErrorCode.PLAN_REPLAN_PARTIAL_STATE,
                    BINDING_AMBIGUOUS));
        }
        List<?> locked = entityManager.createNativeQuery("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """).setParameter("taskId", taskIds.get(0)).getResultList();
        return locked.size() == 1
                ? new Binding(taskIds.get(0), null)
                : new Binding(null, partial());
    }

    private Lease liveLease(PlanReplanRequest request) {
        List<?> values = entityManager.createNativeQuery("""
                SELECT owner_id,lease_token,fencing_token,expires_at,released_at
                  FROM agent_v2_plan_leases
                 WHERE plan_id = :planId
                 ORDER BY fencing_token DESC
                 LIMIT 1
                """).setParameter("planId", request.planId().value())
                .getResultList();
        if (values.isEmpty()) {
            return Lease.failure(rejected(PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.planId"));
        }
        Object[] row = (Object[]) values.get(0);
        String owner = row[0].toString();
        String token = row[1].toString();
        long fence = ((Number) row[2]).longValue();
        Instant expires = instant(row[3]);
        if (row[4] != null) {
            return Lease.failure(rejected(PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.planId"));
        }
        if (!token.equals(request.leaseToken())) {
            return Lease.failure(rejected(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken"));
        }
        if (fence != request.fencingToken()) {
            return Lease.failure(rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken"));
        }
        if (!time.now().isBefore(expires)) {
            return Lease.failure(rejected(PersistenceErrorCode.LEASE_EXPIRED,
                    "request.planId"));
        }
        return new Lease(owner, fence, null);
    }

    private static PersistenceResult<PersistedPlanReplan> validate(
            PlanReplanRequest request, PersistedStepRecoveryReady ready) {
        PlanRevision latest = ready.plan().latestRevision();
        Checkpoint head = ready.checkpoint().checkpoint();
        if (!latest.id().equals(request.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (latest.number() != request.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (ready.checkpoint().version()
                != request.expectedCheckpointVersion()) {
            return stale("request.expectedCheckpointVersion");
        }
        if (head.lastEventSequence()
                != request.expectedEventHeadSequence()) {
            return stale("request.expectedEventHeadSequence");
        }
        EventEnvelope event = request.replanEvent();
        if (!event.planId().equals(request.planId())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    "request.replanEvent.planId");
        }
        if (!event.taskFrameId().equals(ready.taskFrame().id())) {
            return rejected(PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.replanEvent.taskFrameId");
        }
        if (event.sequence() <= head.lastEventSequence()) {
            return rejected(PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.replanEvent.sequence");
        }
        return null;
    }

    private static boolean eligible(PersistedStepRecoveryReady ready) {
        Checkpoint checkpoint = ready.checkpoint().checkpoint();
        PlanRevision latest = ready.plan().latestRevision();
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || checkpoint.stepStates().size() != latest.steps().size()) {
            return false;
        }
        for (PlanStep step : latest.steps()) {
            StepExecutionState expected = latest.completedFacts()
                    .containsKey(step.id()) ? StepExecutionState.SUCCEEDED
                    : StepExecutionState.NOT_STARTED;
            if (checkpoint.stepStates().get(step.id()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static Plan replannedPlan(
            PlanReplanRequest request, Plan current) {
        PlanRevision previous = current.latestRevision();
        PlanRevision replanned = request.replannedRevision();
        if (replanned.number() != previous.number() + 1
                || !replanned.parentRevisionId().equals(
                        java.util.Optional.of(previous.id()))
                || replanned.createdAt().isBefore(previous.createdAt())
                || !replanned.taskFrameId().equals(current.taskFrameId())
                || !replanned.completedFacts().equals(
                        previous.completedFacts())) {
            return null;
        }
        List<PlanRevision> revisions = new ArrayList<>(current.revisions());
        revisions.add(replanned);
        try {
            return new Plan(current.id(), current.taskFrameId(), revisions);
        } catch (ContractViolationException invalid) {
            return null;
        }
    }

    private static PersistenceResult<PersistedPlanReplan> validateCheckpoint(
            PlanReplanRequest request, PersistedStepRecoveryReady ready,
            Plan replannedPlan) {
        Checkpoint current = ready.checkpoint().checkpoint();
        Checkpoint candidate = request.replannedCheckpoint();
        PlanRevision revision = request.replannedRevision();
        if (candidate.lastEventSequence()
                != request.replanEvent().sequence()
                || !candidate.taskFrameId().equals(current.taskFrameId())
                || !candidate.planId().equals(current.planId())
                || !candidate.revisionId().equals(revision.id())
                || candidate.revisionNumber() != revision.number()
                || candidate.createdAt().isBefore(current.createdAt())
                || !candidate.receiptReferences().equals(
                        current.receiptReferences())
                || !expectedStepShape(candidate, revision)
                || candidate.planState() != PlanExecutionState.ACTIVE
                || !CheckpointValidators.validate(candidate,
                        ready.taskFrame(), replannedPlan, current).isEmpty()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.replannedCheckpoint");
        }
        return null;
    }

    private static boolean expectedStepShape(
            Checkpoint checkpoint, PlanRevision revision) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return checkpoint.stepStates().keySet().equals(stepIds)
                && revision.steps().stream().allMatch(step ->
                checkpoint.stepStates().get(step.id())
                        == (revision.completedFacts().containsKey(step.id())
                        ? StepExecutionState.SUCCEEDED
                        : StepExecutionState.NOT_STARTED));
    }

    private boolean eventOccupied(String eventId) {
        Number count = (Number) entityManager.createNativeQuery("""
                SELECT (
                  (SELECT COUNT(*) FROM agent_v2_execution_starts
                    WHERE start_event_id = :eventId)
                + (SELECT COUNT(*) FROM agent_v2_step_activations
                    WHERE activation_event_id = :eventId)
                + (SELECT COUNT(*) FROM agent_v2_step_interruptions
                    WHERE interruption_event_id = :eventId)
                + (SELECT COUNT(*) FROM agent_v2_step_completions
                    WHERE completion_event_id = :eventId)
                + (SELECT COUNT(*) FROM agent_v2_active_step_replans
                    WHERE supersession_event_id = :eventId
                       OR replan_event_id = :eventId)
                + (SELECT COUNT(*) FROM agent_v2_plan_replans
                    WHERE replan_event_id = :eventId))
                """).setParameter("eventId", eventId).getSingleResult();
        return count.longValue() != 0;
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.LocalDateTime local) {
            return local.toInstant(java.time.ZoneOffset.UTC);
        }
        return (Instant) value;
    }

    private static PersistenceResult<PersistedPlanReplan> stale(String path) {
        return rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedPlanReplan> conflict() {
        return rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.replanEvent.id");
    }

    private static PersistenceResult<PersistedPlanReplan> partial() {
        return rejected(PersistenceErrorCode.PLAN_REPLAN_PARTIAL_STATE, ROOT);
    }

    private static PersistenceResult<PersistedPlanReplan> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record Binding(
            String taskId,
            PersistenceResult<PersistedPlanReplan> failure) {
    }

    private record Lease(
            String ownerId,
            long fencingToken,
            PersistenceResult<PersistedPlanReplan> failure) {
        static Lease failure(PersistenceResult<PersistedPlanReplan> failure) {
            return new Lease(null, 0, failure);
        }
    }
}
