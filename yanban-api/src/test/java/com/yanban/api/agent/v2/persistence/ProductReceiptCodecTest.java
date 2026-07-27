package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductReceiptCodecTest {
    private final ProductReceiptCodec codec =
            new ProductReceiptCodec(new ObjectMapper());

    @Test
    void everyStatusAndOptionalStructuredValueRoundTripsCanonically() {
        List<ExecutionReceipt> receipts = List.of(
                receipt("success", ReceiptStatus.SUCCESS,
                        Optional.of(0), Optional.empty(), true),
                receipt("failure", ReceiptStatus.FAILURE,
                        Optional.of(7), Optional.of("TOOL_FAILED"), true),
                receipt("cancelled", ReceiptStatus.CANCELLED,
                        Optional.empty(), Optional.of("CANCELLED"), false),
                receipt("timeout", ReceiptStatus.TIMEOUT,
                        Optional.empty(), Optional.of("TIMEOUT"), false));
        for (ExecutionReceipt receipt : receipts) {
            ProductReceiptCodec.EncodedPayload first = codec.encode(receipt);
            ProductReceiptCodec.EncodedPayload second = codec.encode(receipt);
            assertEquals(first, second);
            assertEquals(receipt, codec.decode(
                    first.formatVersion(), first.sha256(), first.json()));
        }
    }

    @Test
    void malformedHashFormatAndNoncanonicalPayloadFailClosed() {
        ExecutionReceipt receipt = receipt(
                "failure", ReceiptStatus.FAILURE,
                Optional.of(2), Optional.of("FAILED"), true);
        ProductReceiptCodec.EncodedPayload payload = codec.encode(receipt);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                2, payload.sha256(), payload.json()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                payload.formatVersion(), "0".repeat(64), payload.json()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                payload.formatVersion(), payload.sha256(),
                payload.json() + " "));
    }

    static ExecutionReceipt receipt(
            String suffix,
            ReceiptStatus status,
            Optional<Integer> exitCode,
            Optional<String> resultCode,
            boolean structured) {
        return new ExecutionReceipt(
                new ReceiptId("receipt-" + suffix),
                new ToolCallId("tool-" + suffix),
                status,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:00:01.123456Z"),
                exitCode,
                resultCode,
                structured
                        ? OutputCapture.inline("standard output", true)
                        : OutputCapture.empty(),
                structured
                        ? OutputCapture.artifact(
                        new ArtifactRef("stderr-artifact"))
                        : OutputCapture.empty(),
                structured
                        ? List.of(
                        new ArtifactRef("artifact-a"),
                        new ArtifactRef("artifact-b"))
                        : List.of(),
                structured
                        ? Optional.of(new DiffId("diff-a"))
                        : Optional.empty(),
                structured
                        ? List.of(new EventId("event-a"),
                        new EventId("event-b"))
                        : List.of());
    }
}
