package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
class ProductEffectOutcomeTransactions {
    private static final String PARTIAL = "effectOutcome.source";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductStepRecoveryTransactions recovery;
    private final ProductLeaseJpaRepository leases;
    private final ProductEffectOutcomeTimeSource timeSource;
    private final ProductEffectOutcomeProgressJpaRepository progress;
    private final ProductEffectOutcomeResultJpaRepository results;
    private final ProductEffectOutcomeCodec codec;
    private final ProductEffectOutcomeMarkerReader markers;
    private final ProductReceiptJpaRepository receipts;
    private final ProductReceiptCodec receiptCodec;
    private final ProductReceiptMarkerReader receiptMarkers;
    private final EntityManager entityManager;

    ProductEffectOutcomeTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductStepRecoveryTransactions recovery,
            ProductLeaseJpaRepository leases,
            ProductEffectOutcomeTimeSource timeSource,
            ProductEffectOutcomeProgressJpaRepository progress,
            ProductEffectOutcomeResultJpaRepository results,
            ProductEffectOutcomeCodec codec,
            ProductEffectOutcomeMarkerReader markers,
            ProductReceiptJpaRepository receipts,
            ProductReceiptCodec receiptCodec,
            ProductReceiptMarkerReader receiptMarkers,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.recovery = recovery;
        this.leases = leases;
        this.timeSource = timeSource;
        this.progress = progress;
        this.results = results;
        this.codec = codec;
        this.markers = markers;
        this.receipts = receipts;
        this.receiptCodec = receiptCodec;
        this.receiptMarkers = receiptMarkers;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedEffectProgress> replayProgress(
            EffectProgressRequest request) {
        ProductEffectOutcomeProgressEntity row = progress.findById(
                request.progress().id().value()).orElse(null);
        if (row == null) {
            return null;
        }
        if (markers.progressStream(row.toolCallId()) == null) {
            return partial();
        }
        return replay(row, request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedEffectProgress> appendProgress(
            EffectProgressRequest request,
            PersistedEffectIntent expectedIntent,
            PersistedStepRecoveryActive active) {
        ProductPlanBootstrapEntity bootstrap = bootstraps.lockByPlanId(
                expectedIntent.intent().planId().value()).orElse(null);
        if (bootstrap == null) {
            return partial();
        }
        ProductEffectOutcomeProgressEntity existing = progress.findById(
                request.progress().id().value()).orElse(null);
        if (existing != null) {
            return replay(existing, request);
        }
        List<ProductEffectOutcomeProgressEntity> stream =
                progress.findAllByToolCallIdOrderBySequenceAsc(
                        request.progress().toolCallId().value());
        if (!stream.isEmpty()
                && markers.progressStream(
                        request.progress().toolCallId().value()) == null) {
            return partial();
        }
        ProductEffectOutcomeResultEntity finalRow = results.findById(
                request.progress().toolCallId().value()).orElse(null);
        if (finalRow != null) {
            return markers.result(finalRow) == null
                    ? partial()
                    : rejected(PersistenceErrorCode.EFFECT_OUTCOME_FINALIZED,
                    "request.progress.toolCallId");
        }
        PersistenceResult<PersistedEffectProgress> authority =
                authority(expectedIntent, active);
        if (authority != null) {
            return authority;
        }
        LeaseCheck leaseCheck = liveLease(
                expectedIntent, request.leaseToken(),
                request.fencingToken());
        if (leaseCheck.failure() != null) {
            return rejected(leaseCheck.failure().code(),
                    leaseCheck.failure().path());
        }
        ProductLeaseEntity lease = leaseCheck.lease();
        long expectedSequence = stream.isEmpty()
                ? 1 : stream.get(stream.size() - 1).sequence() + 1;
        if (request.progress().sequence() != expectedSequence) {
            return rejected(
                    PersistenceErrorCode.EFFECT_PROGRESS_OUT_OF_SEQUENCE,
                    "request.progress.sequence");
        }
        Instant now = leaseCheck.observedAt();
        PersistedEffectProgress result = new PersistedEffectProgress(
                request.progress(), lease.ownerId(), lease.fencingToken());
        entityManager.persist(new ProductEffectOutcomeProgressEntity(
                request.progress().id().value(),
                request.progress().toolCallId().value(),
                request.progress().sequence(),
                lease.ownerId(), lease.fencingToken(),
                codec.encodeProgressRequest(request),
                codec.encodeProgressResult(result), now));
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<List<PersistedEffectProgress>> readProgress(
            ToolCallId toolCallId) {
        List<ProductEffectOutcomeProgressEntity> rows =
                progress.findAllByToolCallIdOrderBySequenceAsc(
                        toolCallId.value());
        if (rows.isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.NOT_FOUND, "toolCallId");
        }
        List<PersistedEffectProgress> decoded =
                markers.progressStream(toolCallId.value());
        return decoded == null
                ? partial()
                : PersistenceResult.found(decoded);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedEffectProgress> classifyProgress(
            EffectProgressRequest request) {
        ProductEffectOutcomeProgressEntity existing = progress.findById(
                request.progress().id().value()).orElse(null);
        if (existing != null) {
            return replay(existing, request);
        }
        List<ProductEffectOutcomeProgressEntity> stream =
                progress.findAllByToolCallIdOrderBySequenceAsc(
                        request.progress().toolCallId().value());
        if (!stream.isEmpty()
                && markers.progressStream(
                request.progress().toolCallId().value()) == null) {
            return partial();
        }
        ProductEffectOutcomeResultEntity finalRow = results.findById(
                request.progress().toolCallId().value()).orElse(null);
        if (finalRow != null) {
            return markers.result(finalRow) == null
                    ? partial()
                    : rejected(
                    PersistenceErrorCode.EFFECT_OUTCOME_FINALIZED,
                    "request.progress.toolCallId");
        }
        long expected = stream.isEmpty()
                ? 1 : stream.get(stream.size() - 1).sequence() + 1;
        return request.progress().sequence() == expected
                ? null
                : rejected(
                PersistenceErrorCode.EFFECT_PROGRESS_OUT_OF_SEQUENCE,
                "request.progress.sequence");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedEffectResult> replayResult(
            EffectResultRequest request) {
        ProductEffectOutcomeResultEntity row = results.findById(
                request.receipt().toolCallId().value()).orElse(null);
        if (row != null) {
            return replay(row, request);
        }
        return receiptCollision(request.receipt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedEffectResult> recordResult(
            EffectResultRequest request,
            PersistedEffectIntent expectedIntent,
            PersistedStepRecoveryActive active) {
        ProductPlanBootstrapEntity bootstrap = bootstraps.lockByPlanId(
                expectedIntent.intent().planId().value()).orElse(null);
        if (bootstrap == null) {
            return partial();
        }
        ProductEffectOutcomeResultEntity existing = results.findById(
                request.receipt().toolCallId().value()).orElse(null);
        if (existing != null) {
            return replay(existing, request);
        }
        PersistenceResult<PersistedEffectResult> collision =
                receiptCollision(request.receipt());
        if (collision != null) {
            return collision;
        }
        List<ProductEffectOutcomeProgressEntity> progressRows =
                progress.findAllByToolCallIdOrderBySequenceAsc(
                        request.receipt().toolCallId().value());
        if (!progressRows.isEmpty()
                && markers.progressStream(
                request.receipt().toolCallId().value()) == null) {
            return partial();
        }
        PersistenceResult<PersistedEffectResult> authority =
                authority(expectedIntent, active);
        if (authority != null) {
            return authority;
        }
        LeaseCheck leaseCheck = liveLease(
                expectedIntent, request.leaseToken(),
                request.fencingToken());
        if (leaseCheck.failure() != null) {
            return rejected(leaseCheck.failure().code(),
                    leaseCheck.failure().path());
        }
        ProductLeaseEntity lease = leaseCheck.lease();
        Instant now = leaseCheck.observedAt();
        PersistedEffectResult result = new PersistedEffectResult(
                request.receipt(), lease.ownerId(), lease.fencingToken());
        ProductReceiptCodec.EncodedPayload encodedReceipt =
                receiptCodec.encode(request.receipt());
        entityManager.persist(new ProductReceiptEntity(
                request.receipt().id().value(),
                request.receipt().toolCallId().value(),
                ProductReceiptOwnership.EFFECT_INTENT,
                "EFFECT_OUTCOME", encodedReceipt, now));
        entityManager.flush();
        entityManager.persist(new ProductEffectOutcomeResultEntity(
                request.receipt().toolCallId().value(),
                request.receipt().id().value(),
                lease.ownerId(), lease.fencingToken(),
                codec.encodeResultRequest(request),
                codec.encodeResultResult(result), now));
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedEffectResult> findResult(
            ToolCallId toolCallId) {
        ProductEffectOutcomeResultEntity row =
                results.findById(toolCallId.value()).orElse(null);
        if (row == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.NOT_FOUND, "toolCallId");
        }
        ProductEffectOutcomeMarkerReader.ResultMarker marker =
                markers.result(row);
        return marker == null
                ? partial()
                : PersistenceResult.found(marker.result());
    }

    private <T> PersistenceResult<T> authority(
            PersistedEffectIntent expected,
            PersistedStepRecoveryActive active) {
        PersistenceResult<StepRecoverySnapshot> inspected =
                recovery.inspectLocked(expected.intent().planId());
        if (inspected.outcome() != PersistenceOutcome.FOUND
                || !(inspected.value().orElse(null)
                instanceof PersistedStepRecoveryActive current)) {
            if (inspected.failure().map(failure -> failure.code()
                    == PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE)
                    .orElse(false)) {
                return rejected(
                        PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                        "effectIntent.stepId");
            }
            return inspected.failure().isPresent()
                    ? partial()
                    : rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "effectIntent.stepId");
        }
        PersistedEffectIntent durable =
                markers.intent(expected.intent().toolCallId().value());
        if (durable == null || !durable.equals(expected)
                || !current.equals(active)
                || !current.planId().equals(expected.intent().planId())
                || !current.activation().activationEvent().id().equals(
                        expected.activationEventId())) {
            return partial();
        }
        if (!current.activation().stepId().equals(
                expected.intent().stepId())
                || current.checkpoint().checkpoint().stepStates().get(
                expected.intent().stepId()) != StepExecutionState.ACTIVE) {
            return rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "effectIntent.stepId");
        }
        return null;
    }

    private PersistenceResult<PersistedEffectResult> receiptCollision(
            ExecutionReceipt requested) {
        ProductReceiptEntity row =
                receipts.findById(requested.id().value()).orElse(null);
        ProductEffectOutcomeResultEntity marker =
                results.findByReceiptId(requested.id().value()).orElse(null);
        if (row == null && marker == null) {
            return null;
        }
        if (row == null) {
            return partial();
        }
        if (marker != null) {
            return markers.result(marker) == null
                    ? partial()
                    : rejected(
                    PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                    "request.receipt.id");
        }
        if (receiptMarkers.marker(row) == null) {
            return partial();
        }
        return rejected(
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "request.receipt.id");
    }

    private PersistenceResult<PersistedEffectProgress> replay(
            ProductEffectOutcomeProgressEntity row,
            EffectProgressRequest request) {
        ProductEffectOutcomeMarkerReader.ProgressMarker marker =
                markers.progress(row);
        if (marker == null) {
            return partial();
        }
        var stored = marker.request();
        if (!stored.progress().toolCallId().equals(
                request.progress().toolCallId())) {
            return conflict("request.progress.toolCallId");
        }
        if (stored.progress().sequence()
                != request.progress().sequence()) {
            return conflict("request.progress.sequence");
        }
        if (!stored.progress().occurredAt().equals(
                request.progress().occurredAt())) {
            return conflict("request.progress.occurredAt");
        }
        if (!stored.progress().details().equals(
                request.progress().details())) {
            return conflict("request.progress.details");
        }
        if (!stored.leaseToken().equals(request.leaseToken())) {
            return conflict("request.leaseToken");
        }
        if (stored.fencingToken() != request.fencingToken()) {
            return conflict("request.fencingToken");
        }
        return PersistenceResult.replayed(marker.result());
    }

    private PersistenceResult<PersistedEffectResult> replay(
            ProductEffectOutcomeResultEntity row,
            EffectResultRequest request) {
        ProductEffectOutcomeMarkerReader.ResultMarker marker =
                markers.result(row);
        if (marker == null) {
            return partial();
        }
        String difference = receiptDifference(
                marker.request().receipt(), request.receipt());
        if (difference != null) {
            return conflict(difference);
        }
        if (!marker.request().leaseToken().equals(request.leaseToken())) {
            return conflict("request.leaseToken");
        }
        if (marker.request().fencingToken() != request.fencingToken()) {
            return conflict("request.fencingToken");
        }
        return PersistenceResult.replayed(marker.result());
    }

    private LeaseCheck liveLease(
            PersistedEffectIntent intent,
            String leaseToken,
            long fencingToken) {
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        intent.intent().planId().value())
                .orElse(null);
        if (lease == null || lease.releasedAt() != null) {
            return new LeaseCheck(null, null, new PersistenceFailure(
                    PersistenceErrorCode.LEASE_NOT_HELD,
                    "effectIntent.planId"));
        }
        if (!lease.leaseToken().equals(leaseToken)) {
            return new LeaseCheck(null, null, new PersistenceFailure(
                    PersistenceErrorCode.LEASE_TOKEN_INVALID,
                    "request.leaseToken"));
        }
        if (lease.fencingToken() != fencingToken) {
            return new LeaseCheck(null, null, new PersistenceFailure(
                    PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                    "request.fencingToken"));
        }
        Instant now = timeSource.observe();
        if (!lease.expiresAt().isAfter(now)) {
            return new LeaseCheck(null, null, new PersistenceFailure(
                    PersistenceErrorCode.LEASE_EXPIRED,
                    "effectIntent.planId"));
        }
        return new LeaseCheck(lease, now, null);
    }

    private static String receiptDifference(
            ExecutionReceipt stored, ExecutionReceipt requested) {
        if (!stored.id().equals(requested.id())) {
            return "request.receipt.id";
        }
        if (!stored.toolCallId().equals(requested.toolCallId())) {
            return "request.receipt.toolCallId";
        }
        if (stored.status() != requested.status()) {
            return "request.receipt.status";
        }
        if (!stored.startedAt().equals(requested.startedAt())) {
            return "request.receipt.startedAt";
        }
        if (!stored.endedAt().equals(requested.endedAt())) {
            return "request.receipt.endedAt";
        }
        if (!stored.exitCode().equals(requested.exitCode())) {
            return "request.receipt.exitCode";
        }
        if (!stored.resultCode().equals(requested.resultCode())) {
            return "request.receipt.resultCode";
        }
        if (!stored.standardOutput().equals(requested.standardOutput())) {
            return "request.receipt.standardOutput";
        }
        if (!stored.standardError().equals(requested.standardError())) {
            return "request.receipt.standardError";
        }
        if (!stored.artifactReferences().equals(
                requested.artifactReferences())) {
            return "request.receipt.artifactReferences";
        }
        if (!stored.resultingDiff().equals(requested.resultingDiff())) {
            return "request.receipt.resultingDiff";
        }
        return stored.eventReferences().equals(requested.eventReferences())
                ? null : "request.receipt.eventReferences";
    }

    private static <T> PersistenceResult<T> conflict(String path) {
        return rejected(PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static <T> PersistenceResult<T> partial() {
        return rejected(
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE, PARTIAL);
    }

    private static <T> PersistenceResult<T> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record LeaseCheck(
            ProductLeaseEntity lease,
            Instant observedAt,
            PersistenceFailure failure) {
    }
}
