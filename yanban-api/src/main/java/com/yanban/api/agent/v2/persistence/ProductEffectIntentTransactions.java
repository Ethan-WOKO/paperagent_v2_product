package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
class ProductEffectIntentTransactions {
    private static final String PARTIAL = "effectIntent.source";

    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductStepInterruptionJpaRepository interruptions;
    private final ProductLeaseJpaRepository leases;
    private final ProductLeaseTimeSource timeSource;
    private final ProductEffectIntentJpaRepository intents;
    private final ProductReceiptToolCallClaimJpaRepository claims;
    private final ProductReceiptMarkerReader receiptMarkers;
    private final ProductEffectIntentCodec codec;
    private final EntityManager entityManager;

    ProductEffectIntentTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductStepInterruptionJpaRepository interruptions,
            ProductLeaseJpaRepository leases,
            ProductLeaseTimeSource timeSource,
            ProductEffectIntentJpaRepository intents,
            ProductReceiptToolCallClaimJpaRepository claims,
            ProductReceiptMarkerReader receiptMarkers,
            ProductEffectIntentCodec codec,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.activations = activations;
        this.activationCodec = activationCodec;
        this.interruptions = interruptions;
        this.leases = leases;
        this.timeSource = timeSource;
        this.intents = intents;
        this.claims = claims;
        this.receiptMarkers = receiptMarkers;
        this.codec = codec;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<PersistedEffectIntent> persist(
            EffectIntentRequest request, PersistedStepRecoveryActive active) {
        ProductPlanBootstrapEntity bootstrap = bootstraps.lockByPlanId(
                request.intent().planId().value()).orElse(null);
        if (bootstrap == null) {
            return partial();
        }
        ProductEffectIntentEntity existing = intents.findById(
                request.intent().toolCallId().value()).orElse(null);
        if (existing != null) {
            return replay(existing, request);
        }
        PersistenceResult<PersistedEffectIntent> authority =
                validateAuthority(request, active);
        if (authority != null) {
            return authority;
        }
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        request.intent().planId().value())
                .orElse(null);
        if (lease == null || lease.releasedAt() != null) {
            return rejected(PersistenceErrorCode.LEASE_NOT_HELD,
                    "request.intent.planId");
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
        Instant now = timeSource.observe();
        if (!lease.expiresAt().isAfter(now)) {
            return rejected(PersistenceErrorCode.LEASE_EXPIRED,
                    "request.intent.planId");
        }
        ProductReceiptToolCallClaimEntity claim = claims.lockByToolCallId(
                request.intent().toolCallId().value()).orElse(null);
        if (claim != null) {
            if (!ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                    claim.ownerKind())) {
                return receiptMarkers.hasOnlyValidOrdinaryFacts(
                        request.intent().toolCallId().value())
                        ? ownership()
                        : receiptPartial();
            }
            return partial();
        }
        entityManager.persist(new ProductReceiptToolCallClaimEntity(
                request.intent().toolCallId().value(),
                ProductReceiptOwnership.EFFECT_INTENT));
        entityManager.flush();
        PersistedEffectIntent result = new PersistedEffectIntent(
                request.intent(), lease.ownerId(), lease.fencingToken(),
                request.expectedActivationEventId());
        ProductEffectIntentCodec.EncodedPayload encodedRequest =
                codec.encodeRequest(request);
        ProductEffectIntentCodec.EncodedPayload encodedResult =
                codec.encodeResult(result);
        entityManager.persist(new ProductEffectIntentEntity(
                request.intent().toolCallId().value(),
                request.intent().planId().value(),
                request.intent().stepId().value(),
                request.expectedActivationEventId().value(),
                request.intent().kind(), lease.ownerId(),
                lease.fencingToken(), encodedRequest, encodedResult, now));
        entityManager.flush();
        return PersistenceResult.applied(result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedEffectIntent> replay(
            EffectIntentRequest request) {
        ProductEffectIntentEntity row = intents.findById(
                request.intent().toolCallId().value()).orElse(null);
        if (row != null) {
            return replay(row, request);
        }
        ProductReceiptToolCallClaimEntity claim = claims.findById(
                request.intent().toolCallId().value()).orElse(null);
        if (claim == null) {
            return null;
        }
        return ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                claim.ownerKind())
                ? partial()
                : receiptMarkers.hasOnlyValidOrdinaryFacts(
                request.intent().toolCallId().value())
                ? ownership()
                : receiptPartial();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<PersistedEffectIntent> find(ToolCallId id) {
        ProductEffectIntentEntity row =
                intents.findById(id.value()).orElse(null);
        if (row == null) {
            ProductReceiptToolCallClaimEntity claim =
                    claims.findById(id.value()).orElse(null);
            if (claim != null
                    && ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                    claim.ownerKind())) {
                return partial();
            }
            return rejected(PersistenceErrorCode.NOT_FOUND, "toolCallId");
        }
        Marker marker = marker(row);
        return marker == null
                ? partial()
                : PersistenceResult.found(marker.result());
    }

    private PersistenceResult<PersistedEffectIntent> validateAuthority(
            EffectIntentRequest request, PersistedStepRecoveryActive active) {
        if (!active.planId().equals(request.intent().planId())) {
            return partial();
        }
        if (!active.activation().activationEvent().id().equals(
                request.expectedActivationEventId())) {
            return rejected(PersistenceErrorCode.NOT_FOUND,
                    "request.expectedActivationEventId");
        }
        if (!active.activation().stepId().equals(request.intent().stepId())
                || active.checkpoint().checkpoint().stepStates().get(
                        request.intent().stepId()) != StepExecutionState.ACTIVE) {
            return rejected(PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "request.intent.stepId");
        }
        List<ProductStepActivationEntity> rows =
                activations.findAllByPlanId(request.intent().planId().value());
        if (rows.size() != 1) {
            return partial();
        }
        ProductStepActivationEntity row = rows.get(0);
        PersistedStepActivation decoded;
        try {
            decoded = activationCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
        } catch (RuntimeException exception) {
            return partial();
        }
        if (!decoded.equals(active.activation())
                || !row.planId().equals(request.intent().planId().value())
                || !row.stepId().equals(request.intent().stepId().value())
                || !row.activationEventId().equals(
                        request.expectedActivationEventId().value())
                || row.fencingToken() != decoded.fencingToken()
                || !row.leaseOwnerId().equals(decoded.leaseOwnerId())) {
            return partial();
        }
        if (!interruptions.findAllByPlanId(
                request.intent().planId().value()).isEmpty()) {
            return rejected(
                    PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                    "request.intent.stepId");
        }
        return null;
    }

    private PersistenceResult<PersistedEffectIntent> replay(
            ProductEffectIntentEntity row, EffectIntentRequest request) {
        Marker marker = marker(row);
        if (marker == null) {
            return partial();
        }
        EffectIntentRequest stored = marker.request();
        if (!stored.intent().planId().equals(request.intent().planId())) {
            return conflict("request.intent.planId");
        }
        if (!stored.intent().stepId().equals(request.intent().stepId())) {
            return conflict("request.intent.stepId");
        }
        if (!stored.intent().kind().equals(request.intent().kind())) {
            return conflict("request.intent.kind");
        }
        if (!stored.intent().arguments().equals(
                request.intent().arguments())) {
            return conflict("request.intent.arguments");
        }
        if (!stored.expectedActivationEventId().equals(
                request.expectedActivationEventId())) {
            return conflict("request.expectedActivationEventId");
        }
        if (!stored.leaseToken().equals(request.leaseToken())) {
            return conflict("request.leaseToken");
        }
        if (stored.fencingToken() != request.fencingToken()) {
            return conflict("request.fencingToken");
        }
        return PersistenceResult.replayed(marker.result());
    }

    private Marker marker(ProductEffectIntentEntity row) {
        try {
            EffectIntentRequest request = codec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedEffectIntent result = codec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            ProductReceiptToolCallClaimEntity claim = claims.findById(
                    row.toolCallId()).orElse(null);
            boolean valid = claim != null
                    && ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                    claim.ownerKind())
                    && ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                    row.toolCallOwnerKind())
                    && row.toolCallId().equals(
                            request.intent().toolCallId().value())
                    && row.planId().equals(request.intent().planId().value())
                    && row.stepId().equals(request.intent().stepId().value())
                    && row.intentKind().equals(request.intent().kind())
                    && row.activationEventId().equals(
                            request.expectedActivationEventId().value())
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == result.fencingToken()
                    && request.intent().equals(result.intent())
                    && request.fencingToken() == result.fencingToken()
                    && request.expectedActivationEventId().equals(
                            result.activationEventId());
            return valid ? new Marker(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static PersistenceResult<PersistedEffectIntent> conflict(
            String path) {
        return rejected(PersistenceErrorCode.CONFLICTING_REPLAY, path);
    }

    private static PersistenceResult<PersistedEffectIntent> partial() {
        return rejected(PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                PARTIAL);
    }

    private static PersistenceResult<PersistedEffectIntent> ownership() {
        return rejected(
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "request.intent.toolCallId");
    }

    private static PersistenceResult<PersistedEffectIntent> receiptPartial() {
        return rejected(
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                "receipt.source");
    }

    private static PersistenceResult<PersistedEffectIntent> rejected(
            PersistenceErrorCode code, String path) {
        return PersistenceResult.rejected(code, path);
    }

    private record Marker(
            EffectIntentRequest request, PersistedEffectIntent result) {
    }
}
