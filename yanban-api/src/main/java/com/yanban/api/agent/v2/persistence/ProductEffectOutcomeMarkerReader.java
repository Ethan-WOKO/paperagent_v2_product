package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
class ProductEffectOutcomeMarkerReader {
    private final ProductEffectOutcomeProgressJpaRepository progress;
    private final ProductEffectOutcomeResultJpaRepository results;
    private final ProductEffectOutcomeCodec codec;
    private final ProductEffectIntentJpaRepository intents;
    private final ProductEffectIntentCodec intentCodec;
    private final ProductReceiptJpaRepository receipts;
    private final ProductReceiptMarkerReader receiptMarkers;
    private final ProductReceiptToolCallClaimJpaRepository claims;

    ProductEffectOutcomeMarkerReader(
            ProductEffectOutcomeProgressJpaRepository progress,
            ProductEffectOutcomeResultJpaRepository results,
            ProductEffectOutcomeCodec codec,
            ProductEffectIntentJpaRepository intents,
            ProductEffectIntentCodec intentCodec,
            ProductReceiptJpaRepository receipts,
            ProductReceiptMarkerReader receiptMarkers,
            ProductReceiptToolCallClaimJpaRepository claims) {
        this.progress = progress;
        this.results = results;
        this.codec = codec;
        this.intents = intents;
        this.intentCodec = intentCodec;
        this.receipts = receipts;
        this.receiptMarkers = receiptMarkers;
        this.claims = claims;
    }

    ProgressMarker progress(ProductEffectOutcomeProgressEntity row) {
        try {
            EffectProgressRequest request = codec.decodeProgressRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedEffectProgress result = codec.decodeProgressResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            boolean valid = row.committedAt() != null
                    && validIntent(row.toolCallId())
                    && row.progressId().equals(
                            request.progress().id().value())
                    && row.toolCallId().equals(
                            request.progress().toolCallId().value())
                    && row.sequence() == request.progress().sequence()
                    && request.progress().equals(result.progress())
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == request.fencingToken()
                    && row.fencingToken() == result.fencingToken();
            return valid ? new ProgressMarker(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    List<PersistedEffectProgress> progressStream(String toolCallId) {
        List<ProductEffectOutcomeProgressEntity> rows =
                progress.findAllByToolCallIdOrderBySequenceAsc(toolCallId);
        if (rows.isEmpty()) {
            return null;
        }
        List<PersistedEffectProgress> decoded = new ArrayList<>();
        long expected = 1;
        for (ProductEffectOutcomeProgressEntity row : rows) {
            ProgressMarker marker = progress(row);
            if (row.sequence() != expected || marker == null) {
                return null;
            }
            decoded.add(marker.result());
            expected++;
        }
        return List.copyOf(decoded);
    }

    ResultMarker result(ProductEffectOutcomeResultEntity row) {
        try {
            EffectResultRequest request = codec.decodeResultRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedEffectResult result = codec.decodeResultResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            ProductReceiptEntity receiptRow = receipts
                    .findByReceiptIdAndToolCallId(
                            row.receiptId(), row.toolCallId()).orElse(null);
            var receipt = receiptRow == null
                    ? null : receiptMarkers.effectOutcomeMarker(receiptRow);
            boolean valid = row.committedAt() != null
                    && validIntent(row.toolCallId())
                    && row.toolCallId().equals(
                            request.receipt().toolCallId().value())
                    && row.receiptId().equals(
                            request.receipt().id().value())
                    && request.receipt().equals(result.receipt())
                    && request.receipt().equals(receipt)
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == request.fencingToken()
                    && row.fencingToken() == result.fencingToken();
            return valid ? new ResultMarker(request, result) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    boolean validIntent(String toolCallId) {
        return intent(toolCallId) != null;
    }

    PersistedEffectIntent intent(String toolCallId) {
        try {
            ProductEffectIntentEntity row =
                    intents.findById(toolCallId).orElse(null);
            ProductReceiptToolCallClaimEntity claim =
                    claims.findById(toolCallId).orElse(null);
            if (row == null || claim == null
                    || !ProductReceiptOwnership.EFFECT_INTENT.name()
                    .equals(claim.ownerKind())
                    || !ProductReceiptOwnership.EFFECT_INTENT.name()
                    .equals(row.toolCallOwnerKind())) {
                return null;
            }
            var request = intentCodec.decodeRequest(
                    row.requestFormatVersion(), row.requestSha256(),
                    row.requestJson());
            PersistedEffectIntent result = intentCodec.decodeResult(
                    row.resultFormatVersion(), row.resultSha256(),
                    row.resultJson());
            boolean valid = row.toolCallId().equals(
                            request.intent().toolCallId().value())
                    && row.planId().equals(
                            request.intent().planId().value())
                    && row.stepId().equals(
                            request.intent().stepId().value())
                    && row.intentKind().equals(request.intent().kind())
                    && row.activationEventId().equals(
                            request.expectedActivationEventId().value())
                    && row.leaseOwnerId().equals(result.leaseOwnerId())
                    && row.fencingToken() == result.fencingToken()
                    && request.intent().equals(result.intent())
                    && request.fencingToken() == result.fencingToken()
                    && request.expectedActivationEventId().equals(
                            result.activationEventId());
            if (!valid) {
            }
            return valid ? result : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    boolean hasAnyOutcomeState(String toolCallId) {
        return !progress
                .findAllByToolCallIdOrderBySequenceAsc(toolCallId).isEmpty()
                || results.existsById(toolCallId);
    }

    record ProgressMarker(
            EffectProgressRequest request,
            PersistedEffectProgress result) {
    }

    record ResultMarker(
            EffectResultRequest request,
            PersistedEffectResult result) {
    }
}
