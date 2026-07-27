package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductEffectOutcomeCodecTest {
    private final ProductReceiptCodec receiptCodec =
            new ProductReceiptCodec(new ObjectMapper());
    private final ProductEffectOutcomeCodec codec =
            new ProductEffectOutcomeCodec(
                    new ObjectMapper(), receiptCodec);

    @Test
    void progressRoundTripsEveryDetailShapeCanonically() {
        EffectProgress progress = new EffectProgress(
                new EffectProgressId("progress-a"),
                new ToolCallId("tool-a"), 1,
                Instant.parse("2026-07-28T00:00:01Z"),
                new ObjectValue(Map.of(
                        "text", new TextValue("secret-detail"),
                        "number", new NumberValue(new BigDecimal("1.250")),
                        "boolean", new BooleanValue(true),
                        "null", NullValue.INSTANCE,
                        "list", new ListValue(List.of(
                                new TextValue("x"),
                                new ObjectValue(Map.of(
                                        "nested", new NumberValue(
                                                new BigDecimal("2")))))))));
        EffectProgressRequest request =
                new EffectProgressRequest(progress, "secret-token", 7);
        PersistedEffectProgress result =
                new PersistedEffectProgress(progress, "owner-a", 7);

        var encodedRequest = codec.encodeProgressRequest(request);
        var encodedResult = codec.encodeProgressResult(result);

        assertEquals(request, codec.decodeProgressRequest(
                encodedRequest.formatVersion(), encodedRequest.sha256(),
                encodedRequest.json()));
        assertEquals(result, codec.decodeProgressResult(
                encodedResult.formatVersion(), encodedResult.sha256(),
                encodedResult.json()));
        assertFalse(request.toString().contains("secret-detail"));
        assertFalse(result.toString().contains("secret-detail"));
    }

    @Test
    void resultRoundTripsEveryReceiptStatusAndRejectsTampering() {
        for (ReceiptStatus status : ReceiptStatus.values()) {
            ExecutionReceipt receipt = receipt(status);
            EffectResultRequest request =
                    new EffectResultRequest(receipt, "secret-token", 9);
            PersistedEffectResult result =
                    new PersistedEffectResult(receipt, "owner-a", 9);
            var encodedRequest = codec.encodeResultRequest(request);
            var encodedResult = codec.encodeResultResult(result);
            assertEquals(request, codec.decodeResultRequest(
                    encodedRequest.formatVersion(), encodedRequest.sha256(),
                    encodedRequest.json()));
            assertEquals(result, codec.decodeResultResult(
                    encodedResult.formatVersion(), encodedResult.sha256(),
                    encodedResult.json()));
            assertThrows(IllegalArgumentException.class, () ->
                    codec.decodeResultRequest(
                            encodedRequest.formatVersion(),
                            "0".repeat(64), encodedRequest.json()));
        }
    }

    static ExecutionReceipt receipt(
            ReceiptStatus status, String receiptId, String toolCallId) {
        Optional<Integer> exit = switch (status) {
            case SUCCESS -> Optional.of(0);
            case FAILURE -> Optional.of(2);
            case CANCELLED, TIMEOUT -> Optional.empty();
        };
        Optional<String> code = status == ReceiptStatus.SUCCESS
                ? Optional.empty() : Optional.of(status.name());
        return new ExecutionReceipt(
                new ReceiptId(receiptId), new ToolCallId(toolCallId), status,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:00:01Z"),
                exit, code, OutputCapture.inline("secret-stdout", true),
                OutputCapture.empty(), List.of(), Optional.empty(),
                List.of());
    }

    private static ExecutionReceipt receipt(ReceiptStatus status) {
        return receipt(status, "receipt-" + status.name().toLowerCase(),
                "tool-" + status.name().toLowerCase());
    }
}
