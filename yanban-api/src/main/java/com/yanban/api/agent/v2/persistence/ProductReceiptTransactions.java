package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ProductReceiptTransactions {
    private static final String PARTIAL = "receipt.source";

    private final ProductReceiptJpaRepository receipts;
    private final ProductReceiptToolCallClaimJpaRepository claims;
    private final ProductReceiptCodec codec;
    private final ProductReceiptMarkerReader markerReader;
    private final ProductReceiptEffectIntentMarkerReader effectIntentMarkers;
    private final ProductEffectOutcomeReceiptInspector effectOutcomeReceipts;
    private final ProductReceiptTimeSource timeSource;
    private final EntityManager entityManager;

    ProductReceiptTransactions(
            ProductReceiptJpaRepository receipts,
            ProductReceiptToolCallClaimJpaRepository claims,
            ProductReceiptCodec codec,
            ProductReceiptMarkerReader markerReader,
            ProductReceiptEffectIntentMarkerReader effectIntentMarkers,
            ObjectProvider<ProductEffectOutcomeReceiptInspector>
                    effectOutcomeReceipts,
            ProductReceiptTimeSource timeSource,
            EntityManager entityManager) {
        this.receipts = receipts;
        this.claims = claims;
        this.codec = codec;
        this.markerReader = markerReader;
        this.effectIntentMarkers = effectIntentMarkers;
        this.effectOutcomeReceipts = effectOutcomeReceipts.getIfAvailable();
        this.timeSource = timeSource;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<ExecutionReceipt> replay(
            ExecutionReceipt receipt) {
        ProductReceiptEntity row =
                receipts.findById(receipt.id().value()).orElse(null);
        if (row != null) {
            return replay(row, receipt);
        }
        return effectReceiptClassification(receipt.id().value());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<ExecutionReceipt> append(
            ExecutionReceipt receipt) {
        ProductReceiptEntity existing =
                receipts.findById(receipt.id().value()).orElse(null);
        if (existing != null) {
            return replay(existing, receipt);
        }
        PersistenceResult<ExecutionReceipt> effect =
                effectReceiptClassification(receipt.id().value());
        if (effect != null) {
            return effect;
        }
        Claim claim = claim(receipt.toolCallId().value());
        if (claim == Claim.OPPOSITE) {
            return ownership();
        }
        if (claim == Claim.EFFECT_PARTIAL) {
            return effectPartial();
        }
        if (claim == Claim.ORPHAN) {
            return partial();
        }
        existing = receipts.findById(receipt.id().value()).orElse(null);
        if (existing != null) {
            return replay(existing, receipt);
        }
        ProductReceiptCodec.EncodedPayload payload = codec.encode(receipt);
        entityManager.persist(new ProductReceiptEntity(
                receipt.id().value(), receipt.toolCallId().value(),
                payload, timeSource.observe()));
        entityManager.flush();
        return PersistenceResult.applied(receipt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<ExecutionReceipt> classifyAndAppend(
            ExecutionReceipt receipt) {
        ProductReceiptEntity existing =
                receipts.findById(receipt.id().value()).orElse(null);
        if (existing != null) {
            return replay(existing, receipt);
        }
        PersistenceResult<ExecutionReceipt> effect =
                effectReceiptClassification(receipt.id().value());
        if (effect != null) {
            return effect;
        }
        ProductReceiptToolCallClaimEntity claim =
                claims.lockByToolCallId(
                        receipt.toolCallId().value()).orElse(null);
        if (claim == null) {
            return null;
        }
        if (!ProductReceiptOwnership.ORDINARY_RECEIPT.name().equals(
                claim.ownerKind())) {
            return effectIntentMarkers.valid(
                    receipt.toolCallId().value())
                    ? ownership()
                    : effectPartial();
        }
        if (!markerReader.hasOnlyValidOrdinaryFacts(
                receipt.toolCallId().value())) {
            return partial();
        }
        existing = receipts.findById(receipt.id().value()).orElse(null);
        if (existing != null) {
            return replay(existing, receipt);
        }
        ProductReceiptCodec.EncodedPayload payload = codec.encode(receipt);
        entityManager.persist(new ProductReceiptEntity(
                receipt.id().value(), receipt.toolCallId().value(),
                payload, timeSource.observe()));
        entityManager.flush();
        return PersistenceResult.applied(receipt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersistenceResult<ExecutionReceipt> find(ReceiptId receiptId) {
        ProductReceiptEntity row =
                receipts.findById(receiptId.value()).orElse(null);
        if (row == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.NOT_FOUND, "receiptId");
        }
        ExecutionReceipt receipt = marker(row);
        if (receipt != null) {
            return PersistenceResult.found(receipt);
        }
        if (effectOutcomeReceipts == null) {
            return partial();
        }
        return switch (effectOutcomeReceipts.classify(row.receiptId())) {
            case NONE -> partial();
            case PARTIAL -> effectOutcomePartial();
            case OWNED -> PersistenceResult.found(
                    effectOutcomeReceipts.receipt(row.receiptId()));
        };
    }

    private Claim claim(String toolCallId) {
        ProductReceiptToolCallClaimEntity row =
                claims.lockByToolCallId(toolCallId).orElse(null);
        if (row == null) {
            entityManager.persist(new ProductReceiptToolCallClaimEntity(
                    toolCallId,
                    ProductReceiptOwnership.ORDINARY_RECEIPT));
            entityManager.flush();
            return Claim.NEW;
        }
        if (!ProductReceiptOwnership.ORDINARY_RECEIPT.name().equals(
                row.ownerKind())) {
            return effectIntentMarkers.valid(toolCallId)
                    ? Claim.OPPOSITE
                    : Claim.EFFECT_PARTIAL;
        }
        return markerReader.hasOnlyValidOrdinaryFacts(toolCallId)
                ? Claim.MATCHING
                : Claim.ORPHAN;
    }

    private PersistenceResult<ExecutionReceipt> replay(
            ProductReceiptEntity row, ExecutionReceipt requested) {
        ExecutionReceipt stored = markerReader.marker(row);
        if (stored == null) {
            PersistenceResult<ExecutionReceipt> effect =
                    effectReceiptClassification(row.receiptId());
            return effect == null ? partial() : effect;
        }
        return stored.equals(requested)
                ? PersistenceResult.replayed(stored)
                : PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "receipt.id");
    }

    private PersistenceResult<ExecutionReceipt> effectReceiptClassification(
            String receiptId) {
        if (effectOutcomeReceipts == null) {
            return null;
        }
        return switch (effectOutcomeReceipts.classify(receiptId)) {
            case NONE -> null;
            case OWNED -> ownershipById();
            case PARTIAL -> effectOutcomePartial();
        };
    }

    private ExecutionReceipt marker(ProductReceiptEntity row) {
        return markerReader.marker(row);
    }

    private static PersistenceResult<ExecutionReceipt> ownership() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "receipt.toolCallId");
    }

    private static PersistenceResult<ExecutionReceipt> ownershipById() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "receipt.id");
    }

    private static PersistenceResult<ExecutionReceipt> effectOutcomePartial() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
    }

    private static PersistenceResult<ExecutionReceipt> partial() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE, PARTIAL);
    }

    private static PersistenceResult<ExecutionReceipt> effectPartial() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
    }

    private enum Claim {
        NEW,
        MATCHING,
        OPPOSITE,
        EFFECT_PARTIAL,
        ORPHAN
    }
}
