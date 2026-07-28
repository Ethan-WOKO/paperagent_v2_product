package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
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
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
class ProductStepActivationTransactions {
    private static final String PARTIAL = "stepActivation";
    private static final String SOURCE = "stepActivation.source";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductExecutionStartCodec startCodec;
    private final ProductPlanExecutionContextJpaRepository contexts;
    private final ProductPlanExecutionContextCodec contextCodec;
    private final ProductLeaseJpaRepository leases;
    private final ProductLeaseTimeSource timeSource;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductActiveStepReplanJpaRepository replans;
    private final ProductStepActivationCodec codec;
    private final ProductStepRecoveryTransactions recovery;
    private final EntityManager entityManager;

    ProductStepActivationTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec,
            ProductPlanExecutionContextJpaRepository contexts,
            ProductPlanExecutionContextCodec contextCodec,
            ProductLeaseJpaRepository leases,
            ProductLeaseTimeSource timeSource,
            ProductStepActivationJpaRepository activations,
            ProductStepCompletionJpaRepository completions,
            ProductStepInterruptionJpaRepository interruptions,
            ProductActiveStepReplanJpaRepository replans,
            ProductStepActivationCodec codec,
            ProductStepRecoveryTransactions recovery,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.bootstrapCodec = bootstrapCodec;
        this.starts = starts;
        this.startCodec = startCodec;
        this.contexts = contexts;
        this.contextCodec = contextCodec;
        this.leases = leases;
        this.timeSource = timeSource;
        this.activations = activations;
        this.completions = completions;
        this.interruptions = interruptions;
        this.replans = replans;
        this.codec = codec;
        this.recovery = recovery;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedStepActivation> activate(
            StepActivationRequest request) {
        ProductPlanBootstrapEntity bootstrapRow = bootstraps
                .lockByPlanId(request.planId().value()).orElse(null);
        if (bootstrapRow == null) {
            boolean occupied = !activations.findAllByPlanId(
                            request.planId().value()).isEmpty()
                    || starts.existsById(request.planId().value())
                    || contexts.existsById(request.planId().value())
                    || leases.findFirstByPlanIdOrderByFencingTokenDesc(
                            request.planId().value()).isPresent();
            return occupied ? partial() : rejected(
                    PersistenceErrorCode.NOT_FOUND, "request.planId");
        }

        ProductStepActivationEntity existing = activations
                .findByPlanIdAndStepId(
                        request.planId().value(), request.stepId().value())
                .orElse(null);
        if (existing != null) {
            return replay(existing, request, bootstrapRow);
        }

        Source source = source(bootstrapRow);
        if (source == null) {
            return partial();
        }
        ContextStatus context = contextStatus(source);
        if (context == ContextStatus.PARTIAL) {
            return partial();
        }
        if (source.bootstrap().taskFrame().sourceProjectVersion().isEmpty()) {
            if (context != ContextStatus.NONE) {
                return partial();
            }
        } else if (context != ContextStatus.CONFIRMED) {
            return rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    SOURCE);
        }
        var inspected = recovery.inspectLocked(request.planId());
        if (inspected.outcome()
                != io.paperagent.v2.persistence.PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryReady ready)
                || !ready.readyStepId().equals(request.stepId())) {
            return inspected.failure().isPresent()
                    ? partial()
                    : rejected(
                            PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                            SOURCE);
        }

        Instant now = timeSource.observe().truncatedTo(ChronoUnit.MICROS);
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        request.planId().value()).orElse(null);
        if (lease == null || lease.releasedAt() != null) {
            return rejected(
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        }
        if (!lease.leaseToken().equals(request.leaseToken())) {
            return rejected(PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != request.fencingToken()) {
            return rejected(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (!now.isBefore(lease.expiresAt())) {
            return rejected(
                    PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        }

        PersistenceResult<PersistedStepActivation> invalid =
                validate(request, ready);
        if (invalid != null) {
            return invalid;
        }
        if (starts.findByStartEventId(
                        request.activationEvent().id().value()).isPresent()
                || activations.findById(
                        request.activationEvent().id().value()).isPresent()
                || completions.findById(
                        request.activationEvent().id().value()).isPresent()
                || interruptions.findById(
                        request.activationEvent().id().value()).isPresent()
                || replanEventExists(
                        request.activationEvent().id().value())) {
            return rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                    "request.activationEvent.id");
        }

        VersionedCheckpoint activated = new VersionedCheckpoint(
                request.expectedCheckpointVersion() + 1,
                request.activatedCheckpoint());
        PersistedStepActivation result = new PersistedStepActivation(
                request.planId(), request.stepId(), lease.ownerId(),
                lease.fencingToken(), request.activationEvent(), activated);
        Checkpoint head = ready.checkpoint().checkpoint();
        ProductStepActivationEntity row = new ProductStepActivationEntity(
                request.planId().value(), request.stepId().value(),
                request.activationEvent().id().value(),
                head.revisionId().value(), head.revisionNumber(),
                activated.checkpoint().revisionId().value(),
                activated.checkpoint().revisionNumber(),
                ready.checkpoint().version(),
                activated.version(), head.lastEventSequence(),
                request.activationEvent().sequence(), lease.ownerId(),
                lease.fencingToken(), codec.encodeRequest(request),
                codec.encodeResult(result), now);
        entityManager.persist(row);
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    private boolean replanEventExists(String eventId) {
        return replans.findBySupersessionEventId(eventId).isPresent()
                || replans.findByReplanEventId(eventId).isPresent();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedStepActivation> classifyConstraint(
            StepActivationRequest request) {
        ProductPlanBootstrapEntity bootstrap = bootstraps
                .lockByPlanIdForInspection(request.planId().value())
                .orElse(null);
        List<ProductStepActivationEntity> own =
                activations.findAllByPlanId(request.planId().value()).stream()
                        .filter(row -> row.stepId().equals(
                                request.stepId().value()))
                        .toList();
        if (!own.isEmpty()) {
            if (own.size() != 1) {
                return partial();
            }
            return bootstrap == null
                    ? partial()
                    : replay(own.get(0), request, bootstrap);
        }
        if (replanEventExists(request.activationEvent().id().value())) {
            return rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                    "request.activationEvent.id");
        }
        ProductStepActivationEntity event = activations.findById(
                request.activationEvent().id().value()).orElse(null);
        if (event == null) {
            return null;
        }
        Marker marker = decodeMarker(event);
        ProductPlanBootstrapEntity winnerBootstrap = bootstraps
                .lockByPlanIdForInspection(event.planId()).orElse(null);
        var winner = winnerBootstrap == null ? null
                : recovery.inspectLocked(
                        new io.paperagent.v2.contracts.PlanId(event.planId()));
        return marker == null
                || winner == null
                || winner.outcome()
                != io.paperagent.v2.persistence.PersistenceOutcome.FOUND
                ? partial()
                : rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.activationEvent.id");
    }

    private PersistenceResult<PersistedStepActivation> replay(
            ProductStepActivationEntity row, StepActivationRequest request,
            ProductPlanBootstrapEntity bootstrapRow) {
        Marker marker = decodeMarker(row);
        var inspected = recovery.inspectLocked(request.planId());
        if (marker == null || inspected.outcome()
                != io.paperagent.v2.persistence.PersistenceOutcome.FOUND) {
            return partial();
        }
        if (!row.activationEventId().equals(
                request.activationEvent().id().value())) {
            return partial();
        }
        return marker.request().equals(request)
                ? PersistenceResult.replayed(marker.result())
                : rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.activationEvent.id");
    }

    private Marker decodeMarker(ProductStepActivationEntity row) {
        try {
            StepActivationRequest request = codec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedStepActivation result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            VersionedCheckpoint checkpoint = result.activatedCheckpoint();
            if (!row.planId().equals(request.planId().value())
                    || !row.planId().equals(result.planId().value())
                    || !row.stepId().equals(request.stepId().value())
                    || !row.stepId().equals(result.stepId().value())
                    || !row.activationEventId().equals(
                            request.activationEvent().id().value())
                    || !row.activationEventId().equals(
                            result.activationEvent().id().value())
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
                            != result.activationEvent().sequence()
                    || !row.leaseOwnerId().equals(result.leaseOwnerId())
                    || row.fencingToken() != request.fencingToken()
                    || row.fencingToken() != result.fencingToken()
                    || !request.activationEvent().equals(
                            result.activationEvent())
                    || !request.activatedCheckpoint().equals(
                            checkpoint.checkpoint())
                    || row.committedAt() == null) {
                return null;
            }
            return new Marker(request, result);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean markerMatchesSource(
            ProductStepActivationEntity row, Marker marker, Source source) {
        Checkpoint head = source.started().startedCheckpoint().checkpoint();
        return row.sourceRevisionId().equals(head.revisionId().value())
                && row.sourceRevisionNumber() == head.revisionNumber()
                && row.sourceCheckpointVersion()
                        == source.started().startedCheckpoint().version()
                && row.sourceEventSequence()
                        == source.started().startEvent().sequence()
                && marker.request().expectedRevisionId()
                        .equals(head.revisionId())
                && marker.request().expectedRevisionNumber()
                        == head.revisionNumber()
                && marker.request().expectedCheckpointVersion()
                        == source.started().startedCheckpoint().version()
                && marker.request().expectedEventHeadSequence()
                        == source.started().startEvent().sequence();
    }

    private PersistenceResult<PersistedStepActivation> validate(
            StepActivationRequest request, PersistedStepRecoveryReady ready) {
        Checkpoint head = ready.checkpoint().checkpoint();
        Plan plan = ready.plan();
        PlanRevision revision = plan.latestRevision();
        if (!revision.id().equals(request.expectedRevisionId())) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedRevisionId");
        }
        if (revision.number() != request.expectedRevisionNumber()) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedRevisionNumber");
        }
        if (ready.checkpoint().version()
                != request.expectedCheckpointVersion()) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedCheckpointVersion");
        }
        if (head.lastEventSequence()
                != request.expectedEventHeadSequence()) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedEventHeadSequence");
        }
        if (!ready.readyStepId().equals(request.stepId())
                || !eligible(plan, head, request.stepId())) {
            return rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    SOURCE);
        }
        EventEnvelope event = request.activationEvent();
        if (!event.planId().equals(request.planId())) {
            return rejected(PersistenceErrorCode.INVALID_ARGUMENT,
                    "request.activationEvent.planId");
        }
        if (!event.taskFrameId().equals(
                ready.taskFrame().id())) {
            return rejected(PersistenceErrorCode.TASK_FRAME_MISMATCH,
                    "request.activationEvent.taskFrameId");
        }
        if (event.sequence() <= request.expectedEventHeadSequence()) {
            return rejected(
                    PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                    "request.activationEvent.sequence");
        }
        Checkpoint target = request.activatedCheckpoint();
        if (target.lastEventSequence() != event.sequence()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.activatedCheckpoint.lastEventSequence");
        }
        if (!target.taskFrameId().equals(head.taskFrameId())
                || !target.planId().equals(head.planId())
                || !target.revisionId().equals(head.revisionId())
                || target.revisionNumber() != head.revisionNumber()
                || target.planState() != PlanExecutionState.ACTIVE
                || !target.receiptReferences().equals(
                        head.receiptReferences())
                || !target.stepStates().keySet().equals(
                        head.stepStates().keySet())
                || !onlyTargetActivated(
                        head, target, request.stepId())
                || !CheckpointValidators.validate(
                        target, ready.taskFrame(), plan, head)
                        .isEmpty()) {
            return rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.activatedCheckpoint");
        }
        return null;
    }

    private static boolean eligible(
            Plan plan, Checkpoint checkpoint, PlanStepId targetId) {
        PlanRevision revision = plan.latestRevision();
        PlanStep target = revision.steps().stream()
                .filter(step -> step.id().equals(targetId))
                .findFirst().orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || checkpoint.stepStates().get(targetId)
                        != StepExecutionState.NOT_STARTED
                || revision.completedFacts().containsKey(targetId)) {
            return false;
        }
        for (PlanStepId dependency : target.dependencies()) {
            if (checkpoint.stepStates().get(dependency)
                            != StepExecutionState.SUCCEEDED
                    || !revision.completedFacts().containsKey(dependency)) {
                return false;
            }
        }
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : checkpoint.stepStates().entrySet()) {
            StepExecutionState state = entry.getValue();
            if ((!entry.getKey().equals(targetId)
                            && (state == StepExecutionState.ACTIVE
                            || state == StepExecutionState.PAUSED))
                    || state == StepExecutionState.FAILED
                    || state == StepExecutionState.CANCELLED) {
                return false;
            }
        }
        return true;
    }

    private static boolean onlyTargetActivated(
            Checkpoint source, Checkpoint target, PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry
                : source.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? StepExecutionState.ACTIVE : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    private Source source(ProductPlanBootstrapEntity bootstrapRow) {
        ProductExecutionStartEntity startRow = starts
                .findById(bootstrapRow.planId()).orElse(null);
        if (startRow == null) {
            return null;
        }
        try {
            PersistedPlanBootstrap bootstrap = bootstrapCodec.decode(
                    bootstrapRow.payloadFormatVersion(),
                    bootstrapRow.payloadSha256(), bootstrapRow.payloadJson());
            ExecutionStartRequest request = startCodec.decodeRequest(
                    startRow.requestFormatVersion(),
                    startRow.requestSha256(), startRow.requestJson());
            PersistedExecutionStart result = startCodec.decodeResult(
                    startRow.resultFormatVersion(),
                    startRow.resultSha256(), startRow.resultJson());
            return canonicalBootstrap(bootstrapRow, bootstrap)
                    && canonicalStart(bootstrap, startRow, request, result)
                    ? new Source(bootstrap, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private ContextStatus contextStatus(Source source) {
        ProductPlanExecutionContextEntity row = contexts
                .findById(source.bootstrap().plan().id().value())
                .orElse(null);
        if (row == null) {
            return ContextStatus.NONE;
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
            if (!row.planId().equals(request.planId().value())
                    || !row.planId().equals(reserved.planId().value())
                    || !row.workspaceId().equals(
                            request.materializationSpec().workspaceId().value())
                    || !request.materializationSpec().equals(
                            reserved.materializationSpec())
                    || !row.reservationLeaseOwnerId().equals(
                            reserved.leaseOwnerId())
                    || row.reservationFencingToken()
                            != reserved.fencingToken()
                    || request.fencingToken() != reserved.fencingToken()
                    || !matchesContextSource(source, request, reserved)) {
                return ContextStatus.PARTIAL;
            }
            boolean absent = row.confirmationLeaseOwnerId() == null
                    && row.confirmationFencingToken() == null
                    && row.confirmationRequestFormatVersion() == null
                    && row.confirmationRequestSha256() == null
                    && row.confirmationRequestJson() == null
                    && row.confirmationResultFormatVersion() == null
                    && row.confirmationResultSha256() == null
                    && row.confirmationResultJson() == null
                    && row.sourceManifestFingerprint() == null;
            if (absent) {
                return ContextStatus.RESERVED;
            }
            if (row.confirmationLeaseOwnerId() == null
                    || row.confirmationFencingToken() == null
                    || row.confirmationRequestFormatVersion() == null
                    || row.confirmationRequestSha256() == null
                    || row.confirmationRequestJson() == null
                    || row.confirmationResultFormatVersion() == null
                    || row.confirmationResultSha256() == null
                    || row.confirmationResultJson() == null
                    || row.sourceManifestFingerprint() == null) {
                return ContextStatus.PARTIAL;
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
                            confirmed.sourceManifestFingerprint().value())
                    ? ContextStatus.CONFIRMED : ContextStatus.PARTIAL;
        } catch (RuntimeException exception) {
            return ContextStatus.PARTIAL;
        }
    }

    private static boolean matchesContextSource(
            Source source, PlanExecutionContextReservationRequest request,
            PersistedPlanExecutionContextReserved reserved) {
        Checkpoint head = source.started().startedCheckpoint().checkpoint();
        return request.expectedRevisionId().equals(head.revisionId())
                && request.expectedRevisionNumber() == head.revisionNumber()
                && request.expectedCheckpointVersion()
                        == source.started().startedCheckpoint().version()
                && request.expectedEventHeadSequence()
                        == source.started().startEvent().sequence()
                && source.bootstrap().taskFrame().sourceProjectVersion()
                        .filter(version -> version.equals(
                                reserved.materializationSpec()
                                        .sourceProjectVersion()))
                        .isPresent();
    }

    private static boolean canonicalBootstrap(
            ProductPlanBootstrapEntity row,
            PersistedPlanBootstrap bootstrap) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        VersionedCheckpoint initial = bootstrap.initialCheckpoint();
        Checkpoint checkpoint = initial.checkpoint();
        PlanRevision revision = plan.latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(PlanStep::id).collect(Collectors.toSet());
        return row.planId().equals(plan.id().value())
                && row.taskFrameId().equals(task.id().value())
                && task.id().equals(plan.taskFrameId())
                && initial.version() == 1
                && checkpoint.taskFrameId().equals(task.id())
                && checkpoint.planId().equals(plan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(steps)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value ->
                                value == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static boolean canonicalStart(
            PersistedPlanBootstrap bootstrap,
            ProductExecutionStartEntity row, ExecutionStartRequest request,
            PersistedExecutionStart result) {
        Checkpoint started = result.startedCheckpoint().checkpoint();
        PlanRevision latest = bootstrap.plan().latestRevision();
        Set<PlanStepId> stepIds = latest.steps().stream()
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
                && started.revisionId().equals(latest.id())
                && started.revisionNumber() == latest.number()
                && latest.completedFacts().isEmpty()
                && started.planState() == PlanExecutionState.ACTIVE
                && started.stepStates().keySet().equals(stepIds)
                && started.stepStates().values().stream()
                        .allMatch(value ->
                                value == StepExecutionState.NOT_STARTED)
                && started.receiptReferences().isEmpty();
    }

    private static <T> PersistenceResult<T> partial() {
        return rejected(
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE, PARTIAL);
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record Source(
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart started) {
    }

    private record Marker(
            StepActivationRequest request,
            PersistedStepActivation result) {
    }

    private enum ContextStatus {
        NONE,
        RESERVED,
        CONFIRMED,
        PARTIAL
    }
}
