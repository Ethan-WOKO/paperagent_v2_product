package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductStepCompletionCodecTest {
    private final ProductExecutionStartCodec execution =
            new ProductExecutionStartCodec(new ObjectMapper());
    private final ProductStepCompletionCodec codec =
            new ProductStepCompletionCodec(new ObjectMapper(), execution);

    @Test
    void fullRequestAndResultRoundTripCanonically() {
        var scenario = scenario();
        var request = ProductStepCompletionTestFixtures.request(
                scenario, "token-a", 7, "completion-a", List.of());
        var result = new PersistedStepCompletion(
                request.planId(), request.stepId(), "owner-a", 7,
                request.completionEvent(), request.completedRevision(),
                new VersionedCheckpoint(4, request.completedCheckpoint()));
        var encodedRequest = codec.encodeRequest(request);
        var encodedResult = codec.encodeResult(result);
        assertEquals(encodedRequest, codec.encodeRequest(request));
        assertEquals(encodedResult, codec.encodeResult(result));
        assertEquals(request, codec.decodeRequest(
                encodedRequest.formatVersion(), encodedRequest.sha256(),
                encodedRequest.json()));
        assertEquals(result, codec.decodeResult(
                encodedResult.formatVersion(), encodedResult.sha256(),
                encodedResult.json()));
    }

    @Test
    void corruptAndNoncanonicalDocumentsFailWithSanitizedMessage()
            throws Exception {
        var request = ProductStepCompletionTestFixtures.request(
                scenario(), "secret-token", 1, "completion-a", List.of());
        var encoded = codec.encodeRequest(request);
        IllegalStateException hash = assertThrows(
                IllegalStateException.class, () -> codec.decodeRequest(
                        1, "0".repeat(64), encoded.json()));
        assertEquals("Stored V2 step completion payload is invalid",
                hash.getMessage());
        String changed = encoded.json().replaceFirst(
                "\\{", "{\"unknown\":\"secret-token\",");
        String digest = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        changed.getBytes(StandardCharsets.UTF_8)));
        IllegalStateException noncanonical = assertThrows(
                IllegalStateException.class,
                () -> codec.decodeRequest(1, digest, changed));
        assertEquals(hash.getMessage(), noncanonical.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(
                noncanonical.toString().contains("secret-token"));
    }

    private static ProductEffectIntentTestFixtures.Scenario scenario() {
        var bootstrap = ProductPlanBootstrapTestFixtures.workspace(
                "plan-a", "task-a");
        var activation = ProductStepActivationTestFixtures.request(
                bootstrap, "token-a", 7, "activation-a");
        var persisted = new io.paperagent.v2.persistence.PersistedStepActivation(
                activation.planId(), activation.stepId(), "owner-a", 7,
                activation.activationEvent(),
                new VersionedCheckpoint(3, activation.activatedCheckpoint()));
        return new ProductEffectIntentTestFixtures.Scenario(
                bootstrap, activation, persisted);
    }
}
