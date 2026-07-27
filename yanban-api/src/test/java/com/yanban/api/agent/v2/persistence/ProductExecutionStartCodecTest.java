package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventPayloadRef;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductExecutionStartCodecTest {
    private final ProductExecutionStartCodec codec =
            new ProductExecutionStartCodec(new ObjectMapper());

    @Test
    void roundTripsCompleteRequestAndResultWithAllInlineValuesAndNanoseconds() {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap("plan-a", "task-a");
        ExecutionStartRequest request =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-a", 7, "event-a");
        PersistedExecutionStart result = result(request, "owner-a");

        var encodedRequest = codec.encodeRequest(request);
        var encodedResult = codec.encodeResult(result);

        ExecutionStartRequest decoded = codec.decodeRequest(
                encodedRequest.formatVersion(),
                encodedRequest.sha256(),
                encodedRequest.json());
        assertEquals(request, decoded);
        assertEquals(result, codec.decodeResult(
                encodedResult.formatVersion(),
                encodedResult.sha256(),
                encodedResult.json()));
        assertEquals(789, request.startEvent().occurredAt().getNano() % 1000);
        assertFalse(encodedRequest.json().contains("javaClass"));
    }

    @Test
    void roundTripsReferencePayloadAndEmptyCausation() {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap("plan-a", "task-a");
        ExecutionStartRequest base =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-a", 1, "event-a",
                        ProductExecutionStartTestFixtures.referencePayload());
        EventEnvelope withoutCause = new EventEnvelope(
                base.startEvent().id(), base.startEvent().taskFrameId(),
                base.startEvent().planId(), 1, base.startEvent().occurredAt(),
                base.startEvent().type(), java.util.Optional.empty(),
                base.startEvent().correlationId(),
                new EventPayloadRef("synthetic-reference"));
        ExecutionStartRequest request = new ExecutionStartRequest(
                base.planId(), base.leaseToken(), base.fencingToken(),
                withoutCause, base.startedCheckpoint());

        var encoded = codec.encodeRequest(request);
        assertEquals(request, codec.decodeRequest(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));
    }

    @Test
    void mapInsertionOrderHasOneCanonicalEncoding() {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap("plan-a", "task-a");
        ExecutionStartRequest first =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-a", 1, "event-a");
        Map<PlanStepId, StepExecutionState> reversed = new LinkedHashMap<>();
        first.startedCheckpoint().stepStates().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(PlanStepId::value)))
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        Checkpoint cp = first.startedCheckpoint();
        ExecutionStartRequest second = new ExecutionStartRequest(
                first.planId(), first.leaseToken(), first.fencingToken(),
                first.startEvent(), new Checkpoint(
                        cp.taskFrameId(), cp.planId(), cp.revisionId(),
                        cp.revisionNumber(), cp.lastEventSequence(),
                        cp.planState(), reversed, cp.receiptReferences(),
                        cp.createdAt()));

        assertEquals(first, second);
        assertEquals(codec.encodeRequest(first), codec.encodeRequest(second));
    }

    @Test
    void rejectsHashFormatCanonicalAndStructuralCorruptionWithoutPayloadExcerpt() {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap("plan-a", "task-a");
        ExecutionStartRequest request =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-a", 1, "event-a");
        var encoded = codec.encodeRequest(request);

        assertSafeFailure(() -> codec.decodeRequest(
                encoded.formatVersion(), "0".repeat(64), encoded.json()));
        assertSafeFailure(() -> codec.decodeRequest(
                999, encoded.sha256(), encoded.json()));
        String spaced = encoded.json() + " ";
        String spacedHash = sha256(spaced);
        assertSafeFailure(() -> codec.decodeRequest(1, spacedHash, spaced));
        String changed = encoded.json().replace("\"kind\":\"request\"",
                "\"kind\":\"unknown\"");
        assertSafeFailure(() -> codec.decodeRequest(1, sha256(changed), changed));
    }

    private static PersistedExecutionStart result(
            ExecutionStartRequest request, String owner) {
        return new PersistedExecutionStart(
                request.planId(), owner, request.fencingToken(),
                request.startEvent(),
                new VersionedCheckpoint(2, request.startedCheckpoint()));
    }

    private static void assertSafeFailure(Runnable runnable) {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, runnable::run);
        assertEquals("Stored V2 execution start payload is invalid",
                failure.getMessage());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
