package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductPlanExecutionContextCodecTest {
    private final ProductPlanExecutionContextCodec codec =
            new ProductPlanExecutionContextCodec(new ObjectMapper());

    @Test
    void reservationDocumentsRoundTripCanonically() {
        var bootstrap =
                ProductPlanExecutionContextTestFixtures.bootstrap("plan-a", "task-a");
        var request = ProductPlanExecutionContextTestFixtures.reservation(
                bootstrap, "secret-token", 3,
                ProductPlanExecutionContextTestFixtures.spec("a"));
        var result = new PersistedPlanExecutionContextReserved(
                request.planId(), request.materializationSpec(), "owner-a", 3);
        var encodedRequest = codec.encodeReservationRequest(request);
        var encodedResult = codec.encodeReservationResult(result);

        assertEquals(request, codec.decodeReservationRequest(
                1, encodedRequest.sha256(), encodedRequest.json()));
        assertEquals(result, codec.decodeReservationResult(
                1, encodedResult.sha256(), encodedResult.json()));
        assertEquals(encodedRequest,
                codec.encodeReservationRequest(codec.decodeReservationRequest(
                        1, encodedRequest.sha256(), encodedRequest.json())));
    }

    @Test
    void confirmationDocumentsRoundTripAllAuthority() {
        var bootstrap =
                ProductPlanExecutionContextTestFixtures.bootstrap("plan-a", "task-a");
        var spec = ProductPlanExecutionContextTestFixtures.spec("a");
        var request = ProductPlanExecutionContextTestFixtures.confirmation(
                bootstrap, "secret-token", 4, spec);
        var reservation = new PersistedPlanExecutionContextReserved(
                bootstrap.plan().id(), spec, "reservation-owner", 3);
        var result = new PersistedPlanExecutionContextConfirmed(
                reservation, "confirmation-owner", 4,
                ProductPlanExecutionContextTestFixtures.FINGERPRINT);
        var encodedRequest = codec.encodeConfirmationRequest(request);
        var encodedResult = codec.encodeConfirmationResult(result);

        assertEquals(request, codec.decodeConfirmationRequest(
                1, encodedRequest.sha256(), encodedRequest.json()));
        assertEquals(result, codec.decodeConfirmationResult(
                1, encodedResult.sha256(), encodedResult.json()));
    }

    @Test
    void rejectsVersionHashMissingUnknownAndNonCanonicalDocuments() {
        var bootstrap =
                ProductPlanExecutionContextTestFixtures.bootstrap("plan-a", "task-a");
        var request = ProductPlanExecutionContextTestFixtures.reservation(
                bootstrap, "secret-token", 1,
                ProductPlanExecutionContextTestFixtures.spec("a"));
        var encoded = codec.encodeReservationRequest(request);
        for (Runnable decode : java.util.List.<Runnable>of(
                () -> codec.decodeReservationRequest(
                        2, encoded.sha256(), encoded.json()),
                () -> codec.decodeReservationRequest(
                        1, "0".repeat(64), encoded.json()),
                () -> codec.decodeReservationRequest(
                        1, encoded.sha256(),
                        encoded.json().replace(
                                "\"planId\"", "\"unknown\"")),
                () -> {
                    String extra = encoded.json().replace(
                            "\"kind\":", "\"extra\":true,\"kind\":");
                    codec.decodeReservationRequest(
                            1, sha(extra), extra);
                })) {
            IllegalStateException error =
                    assertThrows(IllegalStateException.class, decode::run);
            assertEquals(
                    "Stored V2 Plan execution context payload is invalid",
                    error.getMessage());
        }
    }

    @Test
    void corruptionErrorsNeverExposeStoredAuthority() {
        String payload = "{\"private\":\"do-not-expose\"}";
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> codec.decodeConfirmationResult(
                        1, sha(payload), payload));
        assertFalse(error.getMessage().contains("do-not-expose"));
        assertEquals(null, error.getCause());
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
