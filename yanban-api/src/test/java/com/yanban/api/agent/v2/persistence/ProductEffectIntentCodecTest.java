package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductEffectIntentCodecTest {
    private final ProductEffectIntentCodec codec =
            new ProductEffectIntentCodec(new ObjectMapper());

    @Test
    void canonicalRoundTripSupportsEveryContractValueAndSortedObjects() {
        EffectIntentRequest request = request(new ObjectValue(Map.of(
                "z", new ListValue(List.of(new BooleanValue(true),
                        NullValue.INSTANCE)),
                "a", new NumberValue(new BigDecimal("12.340")),
                "m", new TextValue(""))));
        var first = codec.encodeRequest(request);
        var second = codec.encodeRequest(request);
        assertEquals(first, second);
        assertEquals(request, codec.decodeRequest(
                first.formatVersion(), first.sha256(), first.json()));

        PersistedEffectIntent result = new PersistedEffectIntent(
                request.intent(), "owner-a", 7,
                request.expectedActivationEventId());
        var encoded = codec.encodeResult(result);
        assertEquals(result, codec.decodeResult(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));
    }

    @Test
    void malformedUnknownTamperedAndNonCanonicalPayloadsFailClosed() {
        var encoded = codec.encodeRequest(
                request(new ObjectValue(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeRequest(
                2, encoded.sha256(), encoded.json()));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeRequest(
                1, "0".repeat(64), encoded.json()));
        String unknown = encoded.json().replaceFirst(
                "\\{", "{\"unknown\":1,");
        assertThrows(IllegalArgumentException.class, () -> codec.decodeRequest(
                1, encoded.sha256(), unknown));
        assertThrows(IllegalArgumentException.class, () -> codec.decodeRequest(
                1, encoded.sha256(), encoded.json() + " "));
    }

    @Test
    void diagnosticsNeverExposeTokenArgumentsOrOwner() {
        EffectIntentRequest request = request(new ObjectValue(
                Map.of("secret", new TextValue("private-value"))));
        PersistedEffectIntent result = new PersistedEffectIntent(
                request.intent(), "private-owner", 7,
                request.expectedActivationEventId());
        assertFalse(request.toString().contains("private-token"));
        assertFalse(request.toString().contains("private-value"));
        assertFalse(result.toString().contains("private-owner"));
        assertFalse(result.toString().contains("private-value"));
    }

    private static EffectIntentRequest request(ObjectValue arguments) {
        return new EffectIntentRequest(new EffectIntent(
                new ToolCallId("tool-a"), new PlanId("plan-a"),
                new PlanStepId("step-a"), "search", arguments),
                "private-token", 7, new EventId("activation-a"));
    }
}
