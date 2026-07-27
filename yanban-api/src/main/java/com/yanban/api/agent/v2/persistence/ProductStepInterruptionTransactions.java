package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductStepInterruptionTransactions {
    private static final String PARTIAL = "stepInterruption";
    private static final String ELIGIBILITY = "stepInterruption";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductExecutionStartCodec startCodec;
    private final ProductPlanExecutionContextJpaRepository contexts;
    private final ProductPlanExecutionContextCodec contextCodec;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductLeaseJpaRepository leases;
    private final ProductLeaseTimeSource timeSource;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductStepInterruptionCodec codec;
    private final EntityManager entityManager;

    ProductStepInterruptionTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec,
            ProductPlanExecutionContextJpaRepository contexts,
            ProductPlanExecutionContextCodec contextCodec,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductLeaseJpaRepository leases,
            ProductLeaseTimeSource timeSource,
            ProductStepInterruptionJpaRepository interruptions,
            ProductStepCompletionJpaRepository completions,
            ProductStepInterruptionCodec codec,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.bootstrapCodec = bootstrapCodec;
        this.starts = starts;
        this.startCodec = startCodec;
        this.contexts = contexts;
        this.contextCodec = contextCodec;
        this.activations = activations;
        this.activationCodec = activationCodec;
        this.leases = leases;
        this.timeSource = timeSource;
        this.interruptions = interruptions;
        this.completions = completions;
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
                    || leases.findFirstByPlanIdOrderByFencingTokenDesc(
                            candidate.planId().value()).isPresent();
            return occupied ? partial() : rejected(
                    PersistenceErrorCode.NOT_FOUND, "request.planId");
        }

        if (!completions.findAllByPlanId(
                candidate.planId().value()).isEmpty()) {
            return partial();
        }
        ActiveSource source = activeSource(candidate.planId(), bootstrapRow);
        List<ProductStepInterruptionEntity> own =
                interruptions.findAllByPlanId(candidate.planId().value());
        if (!own.isEmpty()) {
            return own.size() == 1 && source != null
                    ? replay(own.get(0), candidate, source)
                    : partial();
        }
        if (source == null) {
            return partial();
        }

        ProductStepInterruptionEntity reused = interruptions.findById(
                candidate.event().id().value()).orElse(null);
        if (reused != null) {
            return decodeMarker(reused) == null ? partial()
                    : conflict(candidate.eventPath() + ".id");
        }
        if (starts.findByStartEventId(
                        candidate.event().id().value()).isPresent()
                || activations.findById(
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
                validate(candidate, source);
        if (invalid != null) {
            return invalid;
        }

        VersionedCheckpoint interrupted = new VersionedCheckpoint(
                4, candidate.checkpoint());
        PersistedStepInterruption result = new PersistedStepInterruption(
                candidate.planId(), candidate.stepId(), candidate.kind(),
                lease.ownerId(), lease.fencingToken(), candidate.event(),
                interrupted);
        Checkpoint active = source.activation().result()
                .activatedCheckpoint().checkpoint();
        ProductStepInterruptionEntity row =
                new ProductStepInterruptionEntity(
                        candidate.planId().value(),
                        candidate.stepId().value(),
                        candidate.event().id().value(),
                        candidate.kind().name(),
                        active.revisionId().value(),
                        active.revisionNumber(),
                        interrupted.checkpoint().revisionId().value(),
                        interrupted.checkpoint().revisionNumber(),
                        3, 4, 2, 3, lease.ownerId(),
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
            if (own.size() != 1 || bootstrap == null) {
                return partial();
            }
            ActiveSource source = activeSource(
                    candidate.planId(), bootstrap);
            return source == null ? partial()
                    : replay(own.get(0), candidate, source);
        }
        ProductStepInterruptionEntity event = interruptions.findById(
                candidate.event().id().value()).orElse(null);
        if (event == null) {
            return null;
        }
        Marker marker = decodeMarker(event);
        return marker == null
                ? partial()
                : conflict(candidate.eventPath() + ".id");
    }

    private PersistenceResult<PersistedStepInterruption> replay(
            ProductStepInterruptionEntity row,
            ProductStepInterruptionCodec.Candidate candidate,
            ActiveSource source) {
        Marker marker = decodeMarker(row);
        if (marker == null || !markerMatchesSource(row, marker, source)) {
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

    private Marker decodeMarker(ProductStepInterruptionEntity row) {
        try {
            ProductStepInterruptionCodec.DecodedRequest decoded =
                    codec.decodeRequest(
                            row.requestFormatVersion(), row.requestSha256(),
                            row.requestJson());
            PersistedStepInterruption result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            ProductStepInterruptionCodec.Candidate request =
                    decoded.candidate();
            VersionedCheckpoint checkpoint =
                    result.interruptedCheckpoint();
            if (!row.interruptionKind().equals(decoded.kind().name())
                    || result.kind() != decoded.kind()
                    || !row.planId().equals(request.planId().value())
                    || !row.planId().equals(result.planId().value())
                    || !row.stepId().equals(request.stepId().value())
                    || !row.stepId().equals(result.stepId().value())
                    || !row.interruptionEventId().equals(
                            request.event().id().value())
                    || !row.interruptionEventId().equals(
                            result.interruptionEvent().id().value())
                    || !row.sourceRevisionId().equals(
                            request.expectedRevisionId().value())
                    || row.sourceRevisionNumber()
                            != request.expectedRevisionNumber()
                    || !row.resultRevisionId().equals(
                            checkpoint.checkpoint().revisionId().value())
                    || row.resultRevisionNumber()
                            != checkpoint.checkpoint().revisionNumber()
                    || row.sourceCheckpointVersion()
                            != request.expectedCheckpointVersion()
                    || row.resultCheckpointVersion() != checkpoint.version()
                    || row.sourceEventSequence()
                            != request.expectedEventHeadSequence()
                    || row.resultEventSequence()
                            != result.interruptionEvent().sequence()
                    || !row.leaseOwnerId().equals(result.leaseOwnerId())
                    || row.fencingToken() != request.fencingToken()
                    || row.fencingToken() != result.fencingToken()
                    || !request.event().equals(
                            result.interruptionEvent())
                    || !request.checkpoint().equals(
                            checkpoint.checkpoint())
                    || row.committedAt() == null) {
                return null;
            }
            return new Marker(
                    decoded.kind(), decoded.request(), result);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean markerMatchesSource(
            ProductStepInterruptionEntity row, Marker marker,
            ActiveSource source) {
        Checkpoint active = source.activation().result()
                .activatedCheckpoint().checkpoint();
        ProductStepInterruptionCodec.Candidate request =
                ProductStepInterruptionCodec.Candidate.from(
                        marker.kind(), marker.request());
        Checkpoint target =
                marker.result().interruptedCheckpoint().checkpoint();
        return starts.findByStartEventId(
                        row.interruptionEventId()).isEmpty()
                && activations.findById(
                        row.interruptionEventId()).isEmpty()
                && row.sourceRevisionId().equals(active.revisionId().value())
                && row.sourceRevisionNumber() == active.revisionNumber()
                && row.sourceCheckpointVersion() == 3
                && row.sourceEventSequence() == 2
                && row.resultCheckpointVersion() == 4
                && row.resultEventSequence() == 3
                && marker.result().interruptedCheckpoint().version() == 4
                && marker.result().interruptionEvent().sequence() == 3
                && marker.result().interruptionEvent().planId()
                        .equals(source.bootstrap().plan().id())
                && marker.result().interruptionEvent().taskFrameId()
                        .equals(source.bootstrap().taskFrame().id())
                && request.expectedRevisionId().equals(active.revisionId())
                && request.expectedRevisionNumber()
                        == active.revisionNumber()
                && request.expectedCheckpointVersion() == 3
                && request.expectedEventHeadSequence() == 2
                && request.stepId().equals(
                        source.activation().result().stepId())
                && eligible(source, request.stepId())
                && target.taskFrameId().equals(active.taskFrameId())
                && target.planId().equals(active.planId())
                && target.revisionId().equals(active.revisionId())
                && target.revisionNumber() == active.revisionNumber()
                && target.lastEventSequence() == 3
                && !target.createdAt().isBefore(active.createdAt())
                && target.receiptReferences().equals(
                        active.receiptReferences())
                && target.stepStates().keySet().equals(
                        active.stepStates().keySet())
                && onlyTargetInterrupted(
                        active, target, request.stepId(), marker.kind())
                && target.planState() == planState(marker.kind())
                && CheckpointValidators.validate(
                        target, source.bootstrap().taskFrame(),
                        source.bootstrap().plan(), active).isEmpty();
    }

    private PersistenceResult<PersistedStepInterruption> validate(
            ProductStepInterruptionCodec.Candidate candidate,
            ActiveSource source) {
        Checkpoint current = source.activation().result()
                .activatedCheckpoint().checkpoint();
        PlanRevision revision = source.bootstrap().plan().latestRevision();
        if (!revision.id().equals(candidate.expectedRevisionId())) {
            return stale("request.expectedRevisionId");
        }
        if (revision.number() != candidate.expectedRevisionNumber()) {
            return stale("request.expectedRevisionNumber");
        }
        if (candidate.expectedCheckpointVersion() != 3) {
            return stale("request.expectedCheckpointVersion");
        }
        if (candidate.expectedEventHeadSequence() != 2) {
            return stale("request.expectedEventHeadSequence");
        }
        if (!eligible(source, candidate.stepId())) {
            return rejected(
                    PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                    ELIGIBILITY);
        }
        EventEnvelope event = candidate.event();
        if (!event.planId().equals(candidate.planId())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    candidate.eventPath() + ".planId");
        }
        if (!event.taskFrameId().equals(
                source.bootstrap().taskFrame().id())) {
            return rejected(PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    candidate.eventPath() + ".taskFrameId");
        }
        if (event.sequence() != 3) {
            return rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    candidate.eventPath() + ".sequence");
        }
        Checkpoint checkpoint = candidate.checkpoint();
        if (checkpoint.lastEventSequence() != 3) {
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
                        checkpoint, source.bootstrap().taskFrame(),
                        source.bootstrap().plan(), current).isEmpty()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    candidate.checkpointPath());
        }
        return null;
    }

    private static boolean eligible(
            ActiveSource source, PlanStepId stepId) {
        Checkpoint checkpoint = source.activation().result()
                .activatedCheckpoint().checkpoint();
        PlanRevision revision = source.bootstrap().plan().latestRevision();
        PlanStep target = revision.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst().orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || checkpoint.stepStates().get(stepId)
                        != StepExecutionState.ACTIVE
                || revision.completedFacts().containsKey(stepId)
                || !source.activation().request().stepId().equals(stepId)
                || !source.activation().result().stepId().equals(stepId)) {
            return false;
        }
        int active = 0;
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : checkpoint.stepStates().entrySet()) {
            if (entry.getValue() == StepExecutionState.ACTIVE) {
                active++;
            } else if (entry.getValue() != StepExecutionState.NOT_STARTED
                    && entry.getValue() != StepExecutionState.SUCCEEDED) {
                return false;
            }
        }
        return active == 1;
    }

    private ActiveSource activeSource(
            PlanId requested, ProductPlanBootstrapEntity bootstrapRow) {
        ProductExecutionStartEntity startRow = starts.findById(
                requested.value()).orElse(null);
        if (startRow == null) {
            return null;
        }
        try {
            PersistedPlanBootstrap bootstrap = bootstrapCodec.decode(
                    bootstrapRow.payloadFormatVersion(),
                    bootstrapRow.payloadSha256(),
                    bootstrapRow.payloadJson());
            if (!canonicalBootstrap(
                    requested, bootstrapRow, bootstrap)) {
                return null;
            }
            ExecutionStartRequest startRequest = startCodec.decodeRequest(
                    startRow.requestFormatVersion(),
                    startRow.requestSha256(), startRow.requestJson());
            PersistedExecutionStart started = startCodec.decodeResult(
                    startRow.resultFormatVersion(),
                    startRow.resultSha256(), startRow.resultJson());
            if (!canonicalStart(
                    bootstrap, startRow, startRequest, started)
                    || !canonicalContext(bootstrap, started)) {
                return null;
            }
            List<ProductStepActivationEntity> rows =
                    activations.findAllByPlanId(requested.value());
            if (rows.size() != 1) {
                return null;
            }
            Activation activation = activation(
                    rows.get(0), bootstrap, started);
            return activation == null ? null
                    : new ActiveSource(bootstrap, started, activation);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Activation activation(
            ProductStepActivationEntity row,
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart started) {
        try {
            StepActivationRequest request = activationCodec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedStepActivation result = activationCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            Checkpoint h0 = started.startedCheckpoint().checkpoint();
            Checkpoint active = result.activatedCheckpoint().checkpoint();
            boolean valid = row.committedAt() != null
                    && starts.findByStartEventId(
                            row.activationEventId()).isEmpty()
                    && row.planId().equals(bootstrap.plan().id().value())
                    && row.planId().equals(request.planId().value())
                    && row.planId().equals(result.planId().value())
                    && row.stepId().equals(request.stepId().value())
                    && row.stepId().equals(result.stepId().value())
                    && row.activationEventId().equals(
                            request.activationEvent().id().value())
                    && row.activationEventId().equals(
                            result.activationEvent().id().value())
                    && row.sourceRevisionId().equals(h0.revisionId().value())
                    && row.sourceRevisionNumber() == h0.revisionNumber()
                    && row.resultRevisionId().equals(
                            active.revisionId().value())
                    && row.resultRevisionNumber() == active.revisionNumber()
                    && row.sourceCheckpointVersion() == 2
                    && row.resultCheckpointVersion() == 3
                    && row.sourceEventSequence() == 1
                    && row.resultEventSequence() == 2
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == request.fencingToken()
                    && row.fencingToken() == result.fencingToken()
                    && request.activationEvent().equals(
                            result.activationEvent())
                    && request.activatedCheckpoint().equals(active)
                    && request.expectedRevisionId().equals(h0.revisionId())
                    && request.expectedRevisionNumber()
                            == h0.revisionNumber()
                    && request.expectedCheckpointVersion() == 2
                    && request.expectedEventHeadSequence() == 1
                    && result.activatedCheckpoint().version() == 3
                    && result.activationEvent().sequence() == 2
                    && result.activationEvent().planId()
                            .equals(bootstrap.plan().id())
                    && result.activationEvent().taskFrameId()
                            .equals(bootstrap.taskFrame().id())
                    && active.planId().equals(bootstrap.plan().id())
                    && active.taskFrameId().equals(bootstrap.taskFrame().id())
                    && active.revisionId().equals(h0.revisionId())
                    && active.revisionNumber() == h0.revisionNumber()
                    && active.lastEventSequence() == 2
                    && active.planState() == PlanExecutionState.ACTIVE
                    && active.receiptReferences().equals(
                            h0.receiptReferences())
                    && active.stepStates().keySet().equals(
                            h0.stepStates().keySet())
                    && h0.stepStates().get(request.stepId())
                            == StepExecutionState.NOT_STARTED
                    && onlyTargetActivated(
                            h0, active, request.stepId())
                    && CheckpointValidators.validate(
                            active, bootstrap.taskFrame(),
                            bootstrap.plan(), h0).isEmpty();
            return valid ? new Activation(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean canonicalContext(
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart started) {
        ProductPlanExecutionContextEntity row = contexts.findById(
                bootstrap.plan().id().value()).orElse(null);
        if (bootstrap.taskFrame().sourceProjectVersion().isEmpty()) {
            return row == null;
        }
        if (row == null) {
            return false;
        }
        try {
            PlanExecutionContextReservationRequest request =
                    contextCodec.decodeReservationRequest(
                            row.reservationRequestFormatVersion(),
                            row.reservationRequestSha256(),
                            row.reservationRequestJson());
            PersistedPlanExecutionContextReserved reserved =
                    contextCodec.decodeReservationResult(
                            row.reservationResultFormatVersion(),
                            row.reservationResultSha256(),
                            row.reservationResultJson());
            Checkpoint h0 = started.startedCheckpoint().checkpoint();
            if (!row.planId().equals(request.planId().value())
                    || !row.planId().equals(reserved.planId().value())
                    || !row.workspaceId().equals(request
                            .materializationSpec().workspaceId().value())
                    || !request.materializationSpec().equals(
                            reserved.materializationSpec())
                    || !row.reservationLeaseOwnerId().equals(
                            reserved.leaseOwnerId())
                    || row.reservationFencingToken()
                            != reserved.fencingToken()
                    || request.fencingToken() != reserved.fencingToken()
                    || !request.expectedRevisionId().equals(h0.revisionId())
                    || request.expectedRevisionNumber()
                            != h0.revisionNumber()
                    || request.expectedCheckpointVersion() != 2
                    || request.expectedEventHeadSequence() != 1
                    || bootstrap.taskFrame().sourceProjectVersion()
                            .filter(version -> version.equals(
                                    reserved.materializationSpec()
                                            .sourceProjectVersion()))
                            .isEmpty()
                    || row.confirmationLeaseOwnerId() == null
                    || row.confirmationFencingToken() == null
                    || row.confirmationRequestFormatVersion() == null
                    || row.confirmationRequestSha256() == null
                    || row.confirmationRequestJson() == null
                    || row.confirmationResultFormatVersion() == null
                    || row.confirmationResultSha256() == null
                    || row.confirmationResultJson() == null
                    || row.sourceManifestFingerprint() == null) {
                return false;
            }
            PlanExecutionContextConfirmationRequest confirmationRequest =
                    contextCodec.decodeConfirmationRequest(
                            row.confirmationRequestFormatVersion(),
                            row.confirmationRequestSha256(),
                            row.confirmationRequestJson());
            PersistedPlanExecutionContextConfirmed confirmed =
                    contextCodec.decodeConfirmationResult(
                            row.confirmationResultFormatVersion(),
                            row.confirmationResultSha256(),
                            row.confirmationResultJson());
            return confirmationRequest.planId().equals(request.planId())
                    && confirmationRequest.materializationSpec().equals(
                            request.materializationSpec())
                    && confirmed.reservation().equals(reserved)
                    && row.confirmationLeaseOwnerId().equals(
                            confirmed.leaseOwnerId())
                    && row.confirmationFencingToken()
                            == confirmed.fencingToken()
                    && confirmationRequest.fencingToken()
                            == confirmed.fencingToken()
                    && confirmationRequest.sourceManifestFingerprint().equals(
                            confirmed.sourceManifestFingerprint())
                    && row.sourceManifestFingerprint().equals(
                            confirmed.sourceManifestFingerprint().value());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean canonicalBootstrap(
            PlanId requested, ProductPlanBootstrapEntity row,
            PersistedPlanBootstrap bootstrap) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        VersionedCheckpoint initial = bootstrap.initialCheckpoint();
        Checkpoint checkpoint = initial.checkpoint();
        PlanRevision revision = plan.latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return row.planId().equals(requested.value())
                && row.planId().equals(plan.id().value())
                && row.taskFrameId().equals(task.id().value())
                && task.id().equals(plan.taskFrameId())
                && initial.version() == 1
                && checkpoint.taskFrameId().equals(task.id())
                && checkpoint.planId().equals(plan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState()
                        == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(steps)
                && checkpoint.stepStates().values().stream().allMatch(
                        state -> state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static boolean canonicalStart(
            PersistedPlanBootstrap bootstrap,
            ProductExecutionStartEntity row,
            ExecutionStartRequest request,
            PersistedExecutionStart result) {
        Checkpoint started = result.startedCheckpoint().checkpoint();
        PlanRevision revision = bootstrap.plan().latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return row.committedAt() != null
                && row.planId().equals(bootstrap.plan().id().value())
                && request.planId().equals(bootstrap.plan().id())
                && result.planId().equals(bootstrap.plan().id())
                && row.startEventId().equals(
                        request.startEvent().id().value())
                && row.startEventId().equals(
                        result.startEvent().id().value())
                && row.leaseOwnerId().equals(result.leaseOwnerId())
                && row.fencingToken() == request.fencingToken()
                && row.fencingToken() == result.fencingToken()
                && request.startEvent().equals(result.startEvent())
                && request.startedCheckpoint().equals(started)
                && result.startedCheckpoint().version() == 2
                && request.startEvent().sequence() == 1
                && request.startEvent().planId()
                        .equals(bootstrap.plan().id())
                && request.startEvent().taskFrameId()
                        .equals(bootstrap.taskFrame().id())
                && started.lastEventSequence() == 1
                && started.planId().equals(bootstrap.plan().id())
                && started.taskFrameId().equals(bootstrap.taskFrame().id())
                && started.revisionId().equals(revision.id())
                && started.revisionNumber() == revision.number()
                && started.planState() == PlanExecutionState.ACTIVE
                && started.stepStates().keySet().equals(steps)
                && started.stepStates().values().stream().allMatch(
                        state -> state == StepExecutionState.NOT_STARTED)
                && started.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static boolean onlyTargetActivated(
            Checkpoint source, Checkpoint target, PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected =
                    entry.getKey().equals(targetId)
                            ? StepExecutionState.ACTIVE
                            : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
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

    private record ActiveSource(
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart started,
            Activation activation) {
    }

    private record Activation(
            StepActivationRequest request,
            PersistedStepActivation result) {
    }

    private record Marker(
            StepInterruptionKind kind,
            Object request,
            PersistedStepInterruption result) {
    }
}
