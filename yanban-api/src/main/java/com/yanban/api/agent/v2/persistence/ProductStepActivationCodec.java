package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public final class ProductStepActivationCodec {
    static final int FORMAT_VERSION = 1;
    private static final String REQUEST = "step-activation-request";
    private static final String RESULT = "step-activation-result";
    private static final String CORRUPT =
            "Stored V2 step activation payload is invalid";

    private final ObjectMapper json;
    private final ProductExecutionStartCodec executionCodec;

    public ProductStepActivationCodec(
            ObjectMapper json, ProductExecutionStartCodec executionCodec) {
        this.json = json.copy();
        this.executionCodec = executionCodec;
    }

    EncodedPayload encodeRequest(StepActivationRequest request) {
        ExecutionStartRequest carrier = new ExecutionStartRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.activationEvent(), request.activatedCheckpoint());
        ObjectNode root = parse(executionCodec.encodeRequest(carrier).json());
        root.put("kind", REQUEST);
        root.put("expectedRevisionId", request.expectedRevisionId().value());
        root.put("expectedRevisionNumber", request.expectedRevisionNumber());
        root.put("expectedCheckpointVersion",
                request.expectedCheckpointVersion());
        root.put("expectedEventHeadSequence",
                request.expectedEventHeadSequence());
        root.put("stepId", request.stepId().value());
        return encode(root);
    }

    EncodedPayload encodeResult(PersistedStepActivation result) {
        ExecutionStartRequest carrier = new ExecutionStartRequest(
                result.planId(), "result-carrier", result.fencingToken(),
                result.activationEvent(),
                result.activatedCheckpoint().checkpoint());
        ObjectNode encoded = parse(
                executionCodec.encodeRequest(carrier).json());
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT_VERSION);
        root.put("kind", RESULT);
        root.put("planId", result.planId().value());
        root.put("stepId", result.stepId().value());
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        root.set("startEvent", encoded.get("startEvent").deepCopy());
        ObjectNode checkpoint =
                (ObjectNode) encoded.get("startedCheckpoint").deepCopy();
        checkpoint.put("version", result.activatedCheckpoint().version());
        root.set("startedCheckpoint", checkpoint);
        return encode(root);
    }

    StepActivationRequest decodeRequest(
            int formatVersion, String expectedHash, String payload) {
        JsonNode root = verified(
                formatVersion, expectedHash, payload, REQUEST);
        try {
            ExecutionStartRequest carrier = decodeRequestCarrier(root);
            StepActivationRequest result = new StepActivationRequest(
                    carrier.planId(), carrier.leaseToken(),
                    carrier.fencingToken(),
                    new PlanRevisionId(text(root, "expectedRevisionId")),
                    number(root, "expectedRevisionNumber"),
                    number(root, "expectedCheckpointVersion"),
                    number(root, "expectedEventHeadSequence"),
                    new PlanStepId(text(root, "stepId")),
                    carrier.startEvent(), carrier.startedCheckpoint());
            requireCanonical(encodeRequest(result), expectedHash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    PersistedStepActivation decodeResult(
            int formatVersion, String expectedHash, String payload) {
        JsonNode root = verified(
                formatVersion, expectedHash, payload, RESULT);
        try {
            ExecutionStartRequest carrier = decodeResultCarrier(root);
            JsonNode checkpoint = required(root, "startedCheckpoint");
            PersistedStepActivation result = new PersistedStepActivation(
                    carrier.planId(), new PlanStepId(text(root, "stepId")),
                    text(root, "leaseOwnerId"), carrier.fencingToken(),
                    carrier.startEvent(), new VersionedCheckpoint(
                            number(checkpoint, "version"),
                            carrier.startedCheckpoint()));
            requireCanonical(encodeResult(result), expectedHash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    private ExecutionStartRequest decodeRequestCarrier(JsonNode root) {
        ObjectNode carrier = json.createObjectNode();
        carrier.put("format", ProductExecutionStartCodec.FORMAT_VERSION);
        carrier.put("kind", "request");
        copy(root, carrier, "planId");
        copy(root, carrier, "leaseToken");
        copy(root, carrier, "fencingToken");
        copy(root, carrier, "startEvent");
        copy(root, carrier, "startedCheckpoint");
        byte[] bytes = bytes(carrier);
        return executionCodec.decodeRequest(
                ProductExecutionStartCodec.FORMAT_VERSION, sha256(bytes),
                new String(bytes, StandardCharsets.UTF_8));
    }

    private ExecutionStartRequest decodeResultCarrier(JsonNode root) {
        ObjectNode carrier = json.createObjectNode();
        carrier.put("format", ProductExecutionStartCodec.FORMAT_VERSION);
        carrier.put("kind", "request");
        copy(root, carrier, "planId");
        carrier.put("leaseToken", "result-carrier");
        copy(root, carrier, "fencingToken");
        copy(root, carrier, "startEvent");
        ObjectNode checkpoint =
                (ObjectNode) required(root, "startedCheckpoint").deepCopy();
        checkpoint.remove("version");
        carrier.set("startedCheckpoint", checkpoint);
        byte[] bytes = bytes(carrier);
        return executionCodec.decodeRequest(
                ProductExecutionStartCodec.FORMAT_VERSION, sha256(bytes),
                new String(bytes, StandardCharsets.UTF_8));
    }

    private void copy(JsonNode source, ObjectNode target, String field) {
        target.set(field, required(source, field).deepCopy());
    }

    private ObjectNode parse(String value) {
        try {
            return (ObjectNode) json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to compose V2 step activation payload", exception);
        }
    }

    private EncodedPayload encode(ObjectNode root) {
        byte[] bytes = bytes(root);
        return new EncodedPayload(
                FORMAT_VERSION, sha256(bytes),
                new String(bytes, StandardCharsets.UTF_8));
    }

    private byte[] bytes(JsonNode root) {
        try {
            return json.writeValueAsBytes(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 step activation", exception);
        }
    }

    private JsonNode verified(
            int version, String expectedHash, String payload, String kind) {
        if (version != FORMAT_VERSION || expectedHash == null
                || payload == null) {
            throw corrupt();
        }
        try {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(
                    sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                    expectedHash.getBytes(StandardCharsets.US_ASCII))) {
                throw corrupt();
            }
            JsonNode root = json.readTree(bytes);
            if (integer(root, "format") != FORMAT_VERSION
                    || !kind.equals(text(root, "kind"))) {
                throw corrupt();
            }
            return root;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    private static void requireCanonical(
            EncodedPayload canonical, String hash, String payload) {
        if (!canonical.sha256().equals(hash)
                || !canonical.json().equals(payload)) {
            throw corrupt();
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field)) {
            throw corrupt();
        }
        return node.get(field);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException(CORRUPT);
    }

    record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
