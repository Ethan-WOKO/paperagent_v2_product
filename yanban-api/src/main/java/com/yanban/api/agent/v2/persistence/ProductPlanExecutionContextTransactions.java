package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextSnapshot;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
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
class ProductPlanExecutionContextTransactions {
    private static final String CONTEXT = "planExecutionContext";
    private static final String SOURCE = "planExecutionContext.source";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductPlanBootstrapCodec bootstrapCodec;
    private final ProductExecutionStartJpaRepository starts;
    private final ProductExecutionStartCodec startCodec;
    private final ProductLeaseJpaRepository leases;
    private final ProductLeaseTimeSource timeSource;
    private final ProductPlanExecutionContextJpaRepository contexts;
    private final ProductPlanExecutionContextCodec codec;
    private final EntityManager entityManager;

    ProductPlanExecutionContextTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec,
            ProductLeaseJpaRepository leases,
            ProductLeaseTimeSource timeSource,
            ProductPlanExecutionContextJpaRepository contexts,
            ProductPlanExecutionContextCodec codec,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.bootstrapCodec = bootstrapCodec;
        this.starts = starts;
        this.startCodec = startCodec;
        this.leases = leases;
        this.timeSource = timeSource;
        this.contexts = contexts;
        this.codec = codec;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedPlanExecutionContextReserved> reserve(
            PlanExecutionContextReservationRequest request) {
        Optional<ProductPlanBootstrapEntity> locked =
                bootstraps.lockByPlanId(request.planId().value());
        if (locked.isEmpty()) {
            return contexts.existsById(request.planId().value())
                    || starts.existsById(request.planId().value())
                    ? partial()
                    : rejected(PersistenceErrorCode.NOT_FOUND,
                            "request.planId");
        }

        Optional<ProductPlanExecutionContextEntity> existing =
                contexts.findById(request.planId().value());
        if (existing.isPresent()) {
            Decoded decoded = decode(existing.get());
            Source existingSource = source(locked.get());
            if (decoded == null
                    || existingSource == null
                    || !sourceMatchesContext(existingSource, decoded)) {
                return partial();
            }
            return decoded.reservationRequest().equals(request)
                    ? PersistenceResult.replayed(decoded.reservation())
                    : rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.planId");
        }

        Source source = source(locked.get());
        if (source == null) {
            return partial();
        }
        if (source.bootstrap().taskFrame().sourceProjectVersion().isEmpty()) {
            return rejected(
                    PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                    SOURCE);
        }
        if (!source.started().startedCheckpoint().checkpoint()
                .revisionId().equals(request.expectedRevisionId())) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedRevisionId");
        }
        if (source.started().startedCheckpoint().checkpoint()
                .revisionNumber() != request.expectedRevisionNumber()) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedRevisionNumber");
        }
        if (source.started().startedCheckpoint().version()
                != request.expectedCheckpointVersion()) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedCheckpointVersion");
        }
        if (source.started().startEvent().sequence()
                != request.expectedEventHeadSequence()) {
            return rejected(PersistenceErrorCode.STALE_VERSION,
                    "request.expectedEventHeadSequence");
        }
        if (request.expectedCheckpointVersion() != 2
                || request.expectedEventHeadSequence() != 1) {
            return rejected(
                    PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                    SOURCE);
        }
        if (!source.bootstrap().taskFrame().sourceProjectVersion().orElseThrow()
                .equals(request.materializationSpec().sourceProjectVersion())) {
            return rejected(
                    PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                    "request.materializationSpec.sourceProjectVersion");
        }

        LeaseValidation leaseValidation = validateLease(
                request.planId(), request.leaseToken(), request.fencingToken());
        if (leaseValidation.errorCode() != null) {
            return rejected(
                    leaseValidation.errorCode(), leaseValidation.errorPath());
        }
        ProductLeaseEntity lease = leaseValidation.lease();

        Optional<ProductPlanExecutionContextEntity> workspaceOwner =
                contexts.findByWorkspaceId(
                        request.materializationSpec().workspaceId().value());
        if (workspaceOwner.isPresent()) {
            return decode(workspaceOwner.get()) == null
                    ? partial()
                    : rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.materializationSpec.workspaceId");
        }

        PersistedPlanExecutionContextReserved result =
                new PersistedPlanExecutionContextReserved(
                        request.planId(), request.materializationSpec(),
                        lease.ownerId(), lease.fencingToken());
        var entity = new ProductPlanExecutionContextEntity(
                request.planId().value(),
                request.materializationSpec().workspaceId().value(),
                lease.ownerId(), lease.fencingToken(),
                codec.encodeReservationRequest(request),
                codec.encodeReservationResult(result));
        entityManager.persist(entity);
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedPlanExecutionContextConfirmed> confirm(
            PlanExecutionContextConfirmationRequest request) {
        Optional<ProductPlanBootstrapEntity> locked =
                bootstraps.lockByPlanId(request.planId().value());
        if (locked.isEmpty()) {
            return contexts.existsById(request.planId().value())
                    || starts.existsById(request.planId().value())
                    ? partial()
                    : rejected(PersistenceErrorCode.NOT_FOUND,
                            "request.planId");
        }
        Source source = source(locked.get());
        if (source == null) {
            return partial();
        }
        if (source.bootstrap().taskFrame().sourceProjectVersion().isEmpty()) {
            return rejected(
                    PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_NOT_ELIGIBLE,
                    SOURCE);
        }
        ProductPlanExecutionContextEntity entity =
                contexts.findById(request.planId().value()).orElse(null);
        if (entity == null) {
            return rejected(PersistenceErrorCode.NOT_FOUND, CONTEXT);
        }
        Decoded decoded = decode(entity);
        if (decoded == null) {
            return partial();
        }
        if (decoded.confirmation() != null) {
            return decoded.confirmationRequest().equals(request)
                    ? PersistenceResult.replayed(decoded.confirmation())
                    : rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                            "request.planId");
        }
        if (!sourceMatchesContext(source, decoded)) {
            return partial();
        }
        if (!request.materializationSpec()
                .equals(decoded.reservation().materializationSpec())) {
            return rejected(PersistenceErrorCode.CONFLICTING_REPLAY,
                    "request.materializationSpec");
        }

        LeaseValidation leaseValidation = validateLease(
                request.planId(), request.leaseToken(), request.fencingToken());
        if (leaseValidation.errorCode() != null) {
            return rejected(
                    leaseValidation.errorCode(), leaseValidation.errorPath());
        }
        ProductLeaseEntity lease = leaseValidation.lease();
        PersistedPlanExecutionContextConfirmed result =
                new PersistedPlanExecutionContextConfirmed(
                        decoded.reservation(), lease.ownerId(),
                        lease.fencingToken(),
                        request.sourceManifestFingerprint());
        entity.confirm(
                lease.ownerId(), lease.fencingToken(),
                codec.encodeConfirmationRequest(request),
                codec.encodeConfirmationResult(result),
                request.sourceManifestFingerprint().value());
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public PersistenceResult<PlanExecutionContextSnapshot> inspect(
            PlanId planId) {
        Optional<ProductPlanBootstrapEntity> locked =
                bootstraps.lockByPlanIdForInspection(planId.value());
        if (locked.isEmpty()) {
            return contexts.existsById(planId.value())
                    || starts.existsById(planId.value())
                    ? partial()
                    : rejected(PersistenceErrorCode.NOT_FOUND, "planId");
        }
        Source source = source(locked.get());
        if (source == null) {
            return partial();
        }
        ProductPlanExecutionContextEntity entity =
                contexts.findById(planId.value()).orElse(null);
        if (entity == null) {
            return rejected(PersistenceErrorCode.NOT_FOUND, CONTEXT);
        }
        Decoded decoded = decode(entity);
        if (decoded == null
                || !sourceMatchesContext(source, decoded)) {
            return partial();
        }
        return PersistenceResult.found(
                decoded.confirmation() == null
                        ? decoded.reservation()
                        : decoded.confirmation());
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = true)
    public WorkspaceOwnerStatus workspaceOwnerStatus(String workspaceId) {
        ProductPlanExecutionContextEntity entity =
                contexts.findByWorkspaceId(workspaceId).orElse(null);
        if (entity == null) {
            return WorkspaceOwnerStatus.ABSENT;
        }
        Decoded decoded = decode(entity);
        ProductPlanBootstrapEntity bootstrap = bootstraps
                .lockByPlanIdForInspection(entity.planId()).orElse(null);
        Source source = bootstrap == null ? null : source(bootstrap);
        return decoded == null
                        || source == null
                        || !sourceMatchesContext(source, decoded)
                ? WorkspaceOwnerStatus.PARTIAL
                : WorkspaceOwnerStatus.CANONICAL;
    }

    private Source source(ProductPlanBootstrapEntity bootstrapRow) {
        ProductExecutionStartEntity startRow =
                starts.findById(bootstrapRow.planId()).orElse(null);
        if (startRow == null) {
            return null;
        }
        try {
            PersistedPlanBootstrap bootstrap = bootstrapCodec.decode(
                    bootstrapRow.payloadFormatVersion(),
                    bootstrapRow.payloadSha256(),
                    bootstrapRow.payloadJson());
            if (!canonicalBootstrap(bootstrapRow, bootstrap)) {
                return null;
            }
            ExecutionStartRequest request = startCodec.decodeRequest(
                    startRow.requestFormatVersion(),
                    startRow.requestSha256(), startRow.requestJson());
            PersistedExecutionStart started = startCodec.decodeResult(
                    startRow.resultFormatVersion(),
                    startRow.resultSha256(), startRow.resultJson());
            return canonicalStart(bootstrap, startRow, request, started)
                    ? new Source(bootstrap, started)
                    : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Decoded decode(ProductPlanExecutionContextEntity entity) {
        try {
            var request = codec.decodeReservationRequest(
                    entity.reservationRequestFormatVersion(),
                    entity.reservationRequestSha256(),
                    entity.reservationRequestJson());
            var reserved = codec.decodeReservationResult(
                    entity.reservationResultFormatVersion(),
                    entity.reservationResultSha256(),
                    entity.reservationResultJson());
            if (!entity.planId().equals(request.planId().value())
                    || !entity.planId().equals(reserved.planId().value())
                    || !entity.workspaceId().equals(
                            request.materializationSpec().workspaceId().value())
                    || !request.materializationSpec()
                            .equals(reserved.materializationSpec())
                    || !entity.reservationLeaseOwnerId()
                            .equals(reserved.leaseOwnerId())
                    || entity.reservationFencingToken()
                            != reserved.fencingToken()
                    || request.fencingToken() != reserved.fencingToken()) {
                return null;
            }
            boolean absent = entity.confirmationLeaseOwnerId() == null
                    && entity.confirmationFencingToken() == null
                    && entity.confirmationRequestFormatVersion() == null
                    && entity.confirmationRequestSha256() == null
                    && entity.confirmationRequestJson() == null
                    && entity.confirmationResultFormatVersion() == null
                    && entity.confirmationResultSha256() == null
                    && entity.confirmationResultJson() == null
                    && entity.sourceManifestFingerprint() == null;
            if (absent) {
                return new Decoded(request, reserved, null, null);
            }
            if (entity.confirmationLeaseOwnerId() == null
                    || entity.confirmationFencingToken() == null
                    || entity.confirmationRequestFormatVersion() == null
                    || entity.confirmationRequestSha256() == null
                    || entity.confirmationRequestJson() == null
                    || entity.confirmationResultFormatVersion() == null
                    || entity.confirmationResultSha256() == null
                    || entity.confirmationResultJson() == null
                    || entity.sourceManifestFingerprint() == null) {
                return null;
            }
            var confirmationRequest = codec.decodeConfirmationRequest(
                    entity.confirmationRequestFormatVersion(),
                    entity.confirmationRequestSha256(),
                    entity.confirmationRequestJson());
            var confirmed = codec.decodeConfirmationResult(
                    entity.confirmationResultFormatVersion(),
                    entity.confirmationResultSha256(),
                    entity.confirmationResultJson());
            if (!confirmationRequest.planId().equals(request.planId())
                    || !confirmationRequest.materializationSpec()
                            .equals(request.materializationSpec())
                    || !confirmed.reservation().equals(reserved)
                    || !entity.confirmationLeaseOwnerId()
                            .equals(confirmed.leaseOwnerId())
                    || entity.confirmationFencingToken()
                            != confirmed.fencingToken()
                    || confirmationRequest.fencingToken()
                            != confirmed.fencingToken()
                    || !confirmationRequest.sourceManifestFingerprint()
                            .equals(confirmed.sourceManifestFingerprint())
                    || !entity.sourceManifestFingerprint().equals(
                            confirmed.sourceManifestFingerprint().value())) {
                return null;
            }
            return new Decoded(
                    request, reserved, confirmationRequest, confirmed);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private LeaseValidation validateLease(
            PlanId planId, String token, long fence) {
        ProductLeaseEntity lease =
                leases.findFirstByPlanIdOrderByFencingTokenDesc(
                        planId.value()).orElse(null);
        Instant now = timeSource.observe().truncatedTo(ChronoUnit.MICROS);
        if (lease == null || lease.releasedAt() != null) {
            return LeaseValidation.failure(
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        }
        if (!lease.leaseToken().equals(token)) {
            return LeaseValidation.failure(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken");
        }
        if (lease.fencingToken() != fence) {
            return LeaseValidation.failure(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken");
        }
        if (!now.isBefore(lease.expiresAt())) {
            return LeaseValidation.failure(
                    PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        }
        return LeaseValidation.valid(lease);
    }

    private static boolean sourceMatchesContext(
            Source source, Decoded decoded) {
        Checkpoint head = source.started().startedCheckpoint().checkpoint();
        return decoded.reservationRequest().expectedRevisionId()
                        .equals(head.revisionId())
                && decoded.reservationRequest().expectedRevisionNumber()
                        == head.revisionNumber()
                && decoded.reservationRequest().expectedCheckpointVersion()
                        == source.started().startedCheckpoint().version()
                && decoded.reservationRequest().expectedEventHeadSequence()
                        == source.started().startEvent().sequence()
                && source.bootstrap().taskFrame().sourceProjectVersion()
                        .filter(version -> version.equals(
                                decoded.reservation().materializationSpec()
                                        .sourceProjectVersion()))
                        .isPresent();
    }

    private static boolean canonicalBootstrap(
            ProductPlanBootstrapEntity row,
            PersistedPlanBootstrap bootstrap) {
        TaskFrame task = bootstrap.taskFrame();
        Plan plan = bootstrap.plan();
        VersionedCheckpoint source = bootstrap.initialCheckpoint();
        Checkpoint checkpoint = source.checkpoint();
        PlanRevision revision = plan.latestRevision();
        Set<PlanStepId> steps = revision.steps().stream()
                .map(step -> step.id()).collect(Collectors.toSet());
        return row.planId().equals(plan.id().value())
                && row.taskFrameId().equals(task.id().value())
                && task.id().equals(plan.taskFrameId())
                && source.version() == 1
                && checkpoint.taskFrameId().equals(task.id())
                && checkpoint.planId().equals(plan.id())
                && checkpoint.revisionId().equals(revision.id())
                && checkpoint.revisionNumber() == revision.number()
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && checkpoint.stepStates().keySet().equals(steps)
                && checkpoint.stepStates().values().stream()
                        .allMatch(state ->
                                state == StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty();
    }

    private static boolean canonicalStart(
            PersistedPlanBootstrap bootstrap,
            ProductExecutionStartEntity row,
            ExecutionStartRequest request,
            PersistedExecutionStart result) {
        Checkpoint started = result.startedCheckpoint().checkpoint();
        PlanRevision latest = bootstrap.plan().latestRevision();
        Set<PlanStepId> stepIds = latest.steps().stream()
                .map(step -> step.id()).collect(Collectors.toSet());
        return row.committedAt() != null
                && row.planId().equals(bootstrap.plan().id().value())
                && request.planId().equals(bootstrap.plan().id())
                && result.planId().equals(bootstrap.plan().id())
                && row.startEventId().equals(request.startEvent().id().value())
                && row.startEventId().equals(result.startEvent().id().value())
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
                && started.taskFrameId()
                        .equals(bootstrap.taskFrame().id())
                && started.revisionId()
                        .equals(latest.id())
                && started.revisionNumber()
                        == latest.number()
                && latest.completedFacts().isEmpty()
                && started.planState() == PlanExecutionState.ACTIVE
                && started.stepStates().keySet().equals(stepIds)
                && started.stepStates().values().stream()
                        .allMatch(state ->
                                state == StepExecutionState.NOT_STARTED)
                && started.receiptReferences().isEmpty();
    }

    private static <T> PersistenceResult<T> partial() {
        return rejected(
                PersistenceErrorCode.PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                CONTEXT);
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record Source(
            PersistedPlanBootstrap bootstrap,
            PersistedExecutionStart started) {
    }

    private record Decoded(
            PlanExecutionContextReservationRequest reservationRequest,
            PersistedPlanExecutionContextReserved reservation,
            PlanExecutionContextConfirmationRequest confirmationRequest,
            PersistedPlanExecutionContextConfirmed confirmation) {
    }

    enum WorkspaceOwnerStatus {
        ABSENT,
        CANONICAL,
        PARTIAL
    }

    private record LeaseValidation(
            ProductLeaseEntity lease,
            PersistenceErrorCode errorCode,
            String errorPath) {
        static LeaseValidation valid(ProductLeaseEntity lease) {
            return new LeaseValidation(lease, null, null);
        }

        static LeaseValidation failure(
                PersistenceErrorCode code, String path) {
            return new LeaseValidation(null, code, path);
        }
    }
}
