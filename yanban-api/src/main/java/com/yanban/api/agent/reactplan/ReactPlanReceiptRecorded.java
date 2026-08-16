package com.yanban.api.agent.reactplan;

import java.util.Objects;

/** A formal Receipt already durably persisted by the product boundary. */
public record ReactPlanReceiptRecorded(
        String toolCallId,
        String receiptId,
        String requestDigest,
        ReactPlanReceiptKind kind,
        String resultCode) implements ReactPlanFact {

    public ReactPlanReceiptRecorded {
        toolCallId = ReactPlanValues.text(toolCallId, "toolCallId");
        receiptId = ReactPlanValues.text(receiptId, "receiptId");
        requestDigest = ReactPlanValues.text(requestDigest, "requestDigest");
        Objects.requireNonNull(kind, "kind");
        resultCode = ReactPlanValues.text(resultCode, "resultCode");
    }
}
