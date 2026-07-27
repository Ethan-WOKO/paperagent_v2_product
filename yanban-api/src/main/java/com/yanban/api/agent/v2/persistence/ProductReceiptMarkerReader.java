package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class ProductReceiptMarkerReader {
    private final ProductReceiptJpaRepository receipts;
    private final ProductReceiptToolCallClaimJpaRepository claims;
    private final ProductReceiptCodec codec;

    ProductReceiptMarkerReader(
            ProductReceiptJpaRepository receipts,
            ProductReceiptToolCallClaimJpaRepository claims,
            ProductReceiptCodec codec) {
        this.receipts = receipts;
        this.claims = claims;
        this.codec = codec;
    }

    ExecutionReceipt marker(ProductReceiptEntity row) {
        try {
            ProductReceiptToolCallClaimEntity claim =
                    claims.findById(row.toolCallId()).orElse(null);
            if (claim == null
                    || !ProductReceiptOwnership.ORDINARY_RECEIPT.name()
                    .equals(claim.ownerKind())
                    || !ProductReceiptOwnership.ORDINARY_RECEIPT.name()
                    .equals(row.toolCallClaimOwnerKind())
                    || !ProductReceiptOwnership.ORDINARY_RECEIPT.name()
                    .equals(row.receiptOwnerKind())) {
                return null;
            }
            ExecutionReceipt receipt = codec.decode(
                    row.payloadFormatVersion(), row.payloadSha256(),
                    row.payloadJson());
            return row.receiptId().equals(receipt.id().value())
                    && row.toolCallId().equals(receipt.toolCallId().value())
                    ? receipt
                    : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    ExecutionReceipt effectOutcomeMarker(ProductReceiptEntity row) {
        try {
            ProductReceiptToolCallClaimEntity claim =
                    claims.findById(row.toolCallId()).orElse(null);
            if (claim == null
                    || !ProductReceiptOwnership.EFFECT_INTENT.name()
                    .equals(claim.ownerKind())
                    || !ProductReceiptOwnership.EFFECT_INTENT.name()
                    .equals(row.toolCallClaimOwnerKind())
                    || !"EFFECT_OUTCOME".equals(row.receiptOwnerKind())) {
                return null;
            }
            ExecutionReceipt receipt = codec.decode(
                    row.payloadFormatVersion(), row.payloadSha256(),
                    row.payloadJson());
            return row.receiptId().equals(receipt.id().value())
                    && row.toolCallId().equals(receipt.toolCallId().value())
                    ? receipt
                    : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    boolean hasOnlyValidOrdinaryFacts(String toolCallId) {
        List<ProductReceiptEntity> rows =
                receipts.findAllByToolCallId(toolCallId);
        return !rows.isEmpty()
                && rows.stream().allMatch(row -> marker(row) != null);
    }
}
