package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ToolCallId;

/** Bounded non-authoritative projection; raw receipt payloads are never forwarded. */
public record FinalSynthesisReceiptProjection(
        ReceiptId receiptId,
        ToolCallId toolCallId,
        String status,
        String resultSummary) {
    public FinalSynthesisReceiptProjection {
        if (receiptId == null || toolCallId == null || status == null
                || status.isBlank() || resultSummary == null
                || resultSummary.length() > 512) {
            throw new IllegalArgumentException("receipt projection is invalid");
        }
    }

    @Override
    public String toString() {
        return "FinalSynthesisReceiptProjection[receiptId=<provided>, "
                + "toolCallId=<provided>, status=<provided>, "
                + "resultSummary=<redacted>]";
    }
}
