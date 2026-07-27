package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductStepActivationCodecTest {
    private final ProductExecutionStartCodec execution =
            new ProductExecutionStartCodec(new ObjectMapper());
    private final ProductStepActivationCodec codec =
            new ProductStepActivationCodec(new ObjectMapper(), execution);

    @Test
    void requestAndResultRoundTripDeterministically() {
        var bootstrap = ProductPlanBootstrapTestFixtures.workspace(
                "plan-a", "task-a");
        StepActivationRequest request =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "token-a", 7, "activation-a");
        PersistedStepActivation result = new PersistedStepActivation(
                request.planId(), request.stepId(), "owner-a", 7,
                request.activationEvent(),
                new VersionedCheckpoint(3, request.activatedCheckpoint()));

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
    void digestAndFormatCorruptionFailSanitized() {
        var request = ProductStepActivationTestFixtures.request(
                ProductPlanBootstrapTestFixtures.workspace(
                        "plan-a", "task-a"),
                "token-a", 1, "activation-a");
        var encoded = codec.encodeRequest(request);
        IllegalStateException hash = assertThrows(
                IllegalStateException.class,
                () -> codec.decodeRequest(1, "0".repeat(64), encoded.json()));
        IllegalStateException format = assertThrows(
                IllegalStateException.class,
                () -> codec.decodeRequest(2, encoded.sha256(), encoded.json()));
        assertEquals("Stored V2 step activation payload is invalid",
                hash.getMessage());
        assertEquals(hash.getMessage(), format.getMessage());
    }

    @Test
    void nonCanonicalFieldOrderOrUnknownFieldsAreRejected() {
        var request = ProductStepActivationTestFixtures.request(
                ProductPlanBootstrapTestFixtures.workspace(
                        "plan-a", "task-a"),
                "token-a", 1, "activation-a");
        var encoded = codec.encodeRequest(request);
        String changed = encoded.json().replaceFirst(
                "\\{", "{\"unknown\":true,");
        String digest = java.util.HexFormat.of().formatHex(hash(changed));
        assertThrows(IllegalStateException.class,
                () -> codec.decodeRequest(1, digest, changed));
    }

    private static byte[] hash(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
