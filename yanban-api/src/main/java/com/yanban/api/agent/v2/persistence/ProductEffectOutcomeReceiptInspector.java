package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import org.springframework.stereotype.Component;

@Component
class ProductEffectOutcomeReceiptInspector {
    private final ProductReceiptJpaRepository receipts;
    private final ProductEffectOutcomeResultJpaRepository results;
    private final ProductEffectOutcomeMarkerReader markers;

    ProductEffectOutcomeReceiptInspector(
            ProductReceiptJpaRepository receipts,
            ProductEffectOutcomeResultJpaRepository results,
            ProductEffectOutcomeMarkerReader markers) {
        this.receipts = receipts;
        this.results = results;
        this.markers = markers;
    }

    Classification classify(String receiptId) {
        ProductReceiptEntity receipt =
                receipts.findById(receiptId).orElse(null);
        ProductEffectOutcomeResultEntity result =
                results.findByReceiptId(receiptId).orElse(null);
        boolean effectReceipt = receipt != null
                && ProductReceiptOwnership.EFFECT_INTENT.name().equals(
                        receipt.toolCallClaimOwnerKind())
                && "EFFECT_OUTCOME".equals(receipt.receiptOwnerKind());
        if (!effectReceipt && result == null) {
            return Classification.NONE;
        }
        if (!effectReceipt || result == null
                || !receipt.toolCallId().equals(result.toolCallId())
                || markers.result(result) == null) {
            return Classification.PARTIAL;
        }
        return Classification.OWNED;
    }

    ExecutionReceipt receipt(String receiptId) {
        ProductEffectOutcomeResultEntity result =
                results.findByReceiptId(receiptId).orElse(null);
        ProductEffectOutcomeMarkerReader.ResultMarker marker =
                result == null ? null : markers.result(result);
        return marker == null ? null : marker.result().receipt();
    }

    enum Classification {
        NONE,
        OWNED,
        PARTIAL
    }
}
