package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Repository
class ProductStepInterruptionTransactions {
    private static final String PARTIAL = "stepInterruption";
    private static final String ELIGIBILITY = "stepInterruption";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductPlanExecutionContextJpaRepository contexts;
    private final ProductStepActivationJpaRepository activations;
    private final ProductLeaseJpaRepository leases;
    private final ProductLeaseTimeSource timeSource;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductActiveStepReplanJpaRepository replans;
    private final ProductStepRecoveryTransactions recovery;
    private final ProductStepInterruptionMarkerReader markerReader;
    private final ProductStepInterruptionCodec codec;
    private final EntityManager entityManager;

    ProductStepInterruptionTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductExecutionStartJpaRepository starts,
            ProductPlanExecutionContextJpaRepository contexts,
            ProductStepActivationJpaRepository activations,
            ProductLeaseJpaRepository leases,
            ProductLeaseTimeSource timeSource,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepCompletionJpaRepository completions,
            ProductActiveStepReplanJpaRepository replans,
            ProductStepRecoveryTransactions recovery,
            ProductStepInterruptionMarkerReader markerReader,
            ProductStepInterruptionCodec codec,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.starts = starts;
        this.contexts = contexts;
        this.activations = activations;
        this.leases = leases;
        this.timeSource = timeSource;
        this.interruptions = interruptions;
        this.completions = completions;
        this.replans = replans;
        this.recovery = recovery;
        this.markerReader = markerReader;
        this.codec = codec;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedStepInterruption> pause(
            StepPauseRequest request) {
        return interrupt(ProductStepInterruptionCodec.Candidate.from(
                StepInterruptionKind.PAUSE, request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedStepInterruption> fail(
            StepFailRequest request) {
        return interrupt(ProductStepInterruptionCodec.Candidate.from(
                StepInterruptionKind.FAIL, request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedStepInterruption> cancel(
            StepCancelRequest request) {
        return interrupt(ProductStepInterruptionCodec.Candidate.from(
                StepInterruptionKind.CANCEL, request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedStepInterruption> classifyPause(
            StepPauseRequest request) {
        return classify(ProductStepInterruptionCodec.Candidate.from(
                StepInterruptionKind.PAUSE, request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedStepInterruption> classifyFail(
            StepFailRequest request) {
        return classify(ProductStepInterruptionCodec.Candidate.from(
                StepInterruptionKind.FAIL, request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedStepInterruption> classifyCancel(
            StepCancelRequest request) {
        return classify(ProductStepInterruptionCodec.Candidate.from(
                StepInterruptionKind.CANCEL, request));
    }

    private PersistenceResult<PersistedStepInterruption> interrupt(
            ProductStepInterruptionCodec.Candidate candidate) {
        ProductPlanBootstrapEntity bootstrapRow = bootstraps.lockByPlanId(
                candidate.planId().value()).orElse(null);
        if (bootstrapRow == null) {
            boolean occupied = starts.existsById(candidate.planId().value())
                    || contexts.existsById(candidate.planId().value())
                    || !activations.findAllByPlanId(
                            candidate.planId().value()).isEmpty()
                    || !interruptions.findAllByPlanId(
                            candidate.planId().value()).isEmpty()
                    || !completions.findAllByPlanId(
                            candidate.planId().value()).isEmpty()
                    || !replans.findAllByPlanIdOrderBySourceEventSequenceAsc(
                            candidate.planId().value()).isEmpty()
                    || leases.findFirstByPlanIdOrderByFencingTokenDesc(
                            candidate.planId().value()).isPresent();
            return occupied ? partial() : rejected(
                    PersistenceErrorCode.NOT_FOUND, "request.planId");
        }

        List<ProductStepInterruptionEntity> own =
                interruptions.findAllByPlanId(candidate.planId().value());
        if (!own.isEmpty()) {
            return own.size() == 1
                    ? replayExact(own.get(0), candidate) : partial();
        }
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectLocked(candidate.planId());
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || inspected.failure().isPresent()
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryActive active)) {
            return inspected.failure()
                    .filter(failure -> failure.code()
                            == PersistenceErrorCode
                            .STEP_RECOVERY_NOT_ELIGIBLE)
                    .isPresent()
                    ? rejected(
                    PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                    ELIGIBILITY)
                    : partial();
        }
        if (!active.activation().stepId().equals(candidate.stepId())) {
            return rejected(
                    PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                    ELIGIBILITY);
        }

        ProductStepInterruptionEntity reused = interruptions.findById(
                candidate.event().id().value()).orElse(null);
        if (reused != null) {
            return markerReader.decode(reused) == null ? partial()
                    : conflict(candidate.eventPath() + ".id");
        }
        if (starts.findByStartEventId(
                        candidate.event().id().value()).isPresent()
                || activations.findById(
                        candidate.event().id().value()).isPresent()
                || replans.findBySupersessionEventId(
                        candidate.event().id().value()).isPresent()
                || replans.findByReplanEventId(
                        candidate.event().id().value()).isPresent()) {
            return conflict(candidate.eventPath() + ".id");
        }

        Instant now = timeSource.observe().truncatedTo(ChronoUnit.MICROS);
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        candidate.planId().value()).orElse(null);
        if (lease == null || lease.releasedAt() != null) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.planId");
        }
        if (!lease.leaseToken().equals(candidate.leaseToken())) {
            return rejected(PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != candidate.fencingToken()) {
            return rejected(PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (!now.isBefore(lease.expiresAt())) {
            return rejected(PersistenceErrorCode.LEASE_EXPIRED,
                    "request.planId");
        }

        PersistenceResult<PersistedStepInterruption> invalid =
                validate(candidate, active);
        if (invalid != null) {
            return invalid;
        }

        long sourceCheckpointVersion = active.checkpoint().version();
        long sourceEventSequence =
                active.checkpoint().checkpoint().lastEventSequence();
        VersionedCheckpoint interrupted = new VersionedCheckpoint(
                sourceCheckpointVersion + 1, candidate.checkpoint());
        PersistedStepInterruption result = new PersistedStepInterruption(
                candidate.planId(), candidate.stepId(), candidate.kind(),
                lease.ownerId(), lease.fencingToken(), candidate.event(),
                interrupted);
        Checkpoint activeCheckpoint = active.checkpoint().checkpoint();
        ProductStepInterruptionEntity row =
                new ProductStepInterruptionEntity(
                        candidate.planId().value(),
                        candidate.stepId().value(),
                        candidate.event().id().value(),
                        candidate.kind().name(),
                        activeCheckpoint.revisionId().value(),
                        activeCheckpoint.revisionNumber(),
                        interrupted.checkpoint().revisionId().value(),
                        interrupted.checkpoint().revisionNumber(),
                        sourceCheckpointVersion,
                        interrupted.version(),
                        sourceEventSequence,
                        candidate.event().sequence(),
                        lease.ownerId(),
                        lease.fencingToken(), codec.encodeRequest(candidate),
                        codec.encodeResult(result), now);
        entityManager.persist(row);
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    private PersistenceResult<PersistedStepInterruption> classify(
            ProductStepInterruptionCodec.Candidate candidate) {
        ProductPlanBootstrapEntity bootstrap = bootstraps
                .lockByPlanIdForInspection(candidate.planId().value())
                .orElse(null);
        List<ProductStepInterruptionEntity> own =
                interruptions.findAllByPlanId(candidate.planId().value());
        if (!own.isEmpty()) {
            return own.size() == 1 && bootstrap != null
                    ? replayExact(own.get(0), candidate) : partial();
        }
        if (replans.findBySupersessionEventId(
                        candidate.event().id().value()).isPresent()
                || replans.findByReplanEventId(
                        candidate.event().id().value()).isPresent()) {
            return conflict(candidate.eventPath() + ".id");
        }
        ProductStepInterruptionEntity event = interruptions.findById(
                candidate.event().id().value()).orElse(null);
        if (event == null) {
            return null;
        }
        ProductStepInterruptionMarkerReader.Marker marker =
                markerReader.decode(event);
        return marker == null
                ? partial()
                : conflict(candidate.eventPath() + ".id");
    }

    private PersistenceResult<PersistedStepInterruption> replayExact(
            ProductStepInterruptionEntity row,
            ProductStepInterruptionCodec.Candidate candidate) {
        ProductStepInterruptionMarkerReader.Marker marker =
                markerReader.decode(row);
        if (marker == null
                || starts.findByStartEventId(
                        row.interruptionEventId()).isPresent()
                || activations.findById(
                        row.interruptionEventId()).isPresent()
                || completions.findById(
                        row.interruptionEventId()).isPresent()
                || replans.findBySupersessionEventId(
                        row.interruptionEventId()).isPresent()
                || replans.findByReplanEventId(
                        row.interruptionEventId()).isPresent()) {
            return partial();
        }
        if (!row.interruptionEventId().equals(
                candidate.event().id().value())) {
            return partial();
        }
        return marker.kind() == candidate.kind()
                && marker.request().equals(candidate.request())
                ? PersistenceResult.replayed(marker.result())
                : conflict(candidate.eventPath() + ".id");
    }

    private PersistenceResult<PersistedStepInterruption> validate(
            ProductStepInterruptionCodec.Candidate candidate,
            PersistedStepRecoveryActive active) {
        Checkpoint current = active.checkpoint().checkpoint();
        PlanRevision revision = active.plan().latestRevision();
        if (!revision.id().equals(candidate.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (revision.number() != candidate.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (candidate.expectedCheckpointVersion()
                != active.checkpoint().version()) {
            return stale("request.expectedCheckpointVersion");
        }
        if (candidate.expectedEventHeadSequence()
                != current.lastEventSequence()) {
            return stale("request.expectedEventHeadSequence");
        }
        if (!eligible(active, candidate.stepId())) {
            return rejected(
                    PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                    ELIGIBILITY);
        }
        EventEnvelope event = candidate.event();
        if (!event.planId().equals(candidate.planId())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    candidate.eventPath() + ".planId");
        }
        if (!event.taskFrameId().equals(active.taskFrame().id())) {
            return rejected(PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    candidate.eventPath() + ".taskFrameId");
        }
        long nextSequence = current.lastEventSequence() + 1;
        if (event.sequence() != nextSequence) {
            return rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    candidate.eventPath() + ".sequence");
        }
        Checkpoint checkpoint = candidate.checkpoint();
        if (checkpoint.lastEventSequence() != nextSequence) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    candidate.checkpointPath() + ".lastEventSequence");
        }
        if (!checkpoint.taskFrameId().equals(current.taskFrameId())
                || !checkpoint.planId().equals(current.planId())
                || !checkpoint.revisionId().equals(current.revisionId())
                || checkpoint.revisionNumber() != current.revisionNumber()
                || checkpoint.createdAt().isBefore(current.createdAt())
                || !checkpoint.receiptReferences().equals(
                        current.receiptReferences())
                || !checkpoint.stepStates().keySet().equals(
                        current.stepStates().keySet())
                || !onlyTargetInterrupted(current, checkpoint,
                        candidate.stepId(), candidate.kind())
                || checkpoint.planState() != planState(candidate.kind())
                || !CheckpointValidators.validate(
                        checkpoint, active.taskFrame(),
                        active.plan(), current).isEmpty()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    candidate.checkpointPath());
        }
        return null;
    }

    private static boolean eligible(
            PersistedStepRecoveryActive active, PlanStepId stepId) {
        Checkpoint checkpoint = active.checkpoint().checkpoint();
        PlanRevision revision = active.plan().latestRevision();
        PlanStep target = revision.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst().orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || checkpoint.stepStates().get(stepId)
                        != StepExecutionState.ACTIVE
                || revision.completedFacts().containsKey(stepId)
                || !active.activation().stepId().equals(stepId)) {
            return false;
        }
        int activeCount = 0;
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : checkpoint.stepStates().entrySet()) {
            if (entry.getValue() == StepExecutionState.ACTIVE) {
                activeCount++;
            } else if (entry.getValue() != StepExecutionState.NOT_STARTED
                    && entry.getValue() != StepExecutionState.SUCCEEDED
                    && entry.getValue()
                    != StepExecutionState.SUPERSEDED_BY_REPLAN) {
                return false;
            }
        }
        return activeCount == 1;
    }

    private static boolean onlyTargetInterrupted(
            Checkpoint source, Checkpoint target, PlanStepId targetId,
            StepInterruptionKind kind) {
        StepExecutionState targetState = stepState(kind);
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected =
                    entry.getKey().equals(targetId)
                            ? targetState : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private static StepExecutionState stepState(
            StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    private static PlanExecutionState planState(
            StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    private static PersistenceResult<PersistedStepInterruption> stale(
            String path) {
        return rejected(PersistenceErrorCode.STALE_VERSION, path);
    }

    private static PersistenceResult<PersistedStepInterruption> conflict(
            String path) {
        return rejected(PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static PersistenceResult<PersistedStepInterruption> partial() {
        return rejected(
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                PARTIAL);
    }

    private static PersistenceResult<PersistedStepInterruption> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

}
