package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
final class ProductStepInterruptionCodec {
    static final int FORMAT_VERSION = 1;
    private static final String REQUEST = "step-interruption-request";
    private static final String RESULT = "step-interruption-result";
    private static final String CORRUPT =
            "Stored V2 step interruption payload is invalid";

    private final ObjectMapper json;
    private final ProductExecutionStartCodec executionCodec;

    ProductStepInterruptionCodec(
            ObjectMapper json, ProductExecutionStartCodec executionCodec) {
        this.json = json.copy();
        this.executionCodec = executionCodec;
    }

    EncodedPayload encodeRequest(Candidate candidate) {
        ObjectNode root = parse(executionCodec.encodeRequest(
                candidate.executionCarrier()).json());
        root.put("kind", REQUEST);
        root.put("interruptionKind", candidate.kind().name());
        root.put("expectedRevisionId",
                candidate.expectedRevisionId().value());
        root.put("expectedRevisionNumber",
                candidate.expectedRevisionNumber());
        root.put("expectedCheckpointVersion",
                candidate.expectedCheckpointVersion());
        root.put("expectedEventHeadSequence",
                candidate.expectedEventHeadSequence());
        root.put("stepId", candidate.stepId().value());
        return encode(root);
    }

    EncodedPayload encodeResult(PersistedStepInterruption result) {
        ExecutionStartRequest carrier = new ExecutionStartRequest(
                result.planId(), "result-carrier",
                result.fencingToken(), result.interruptionEvent(),
                result.interruptedCheckpoint().checkpoint());
        ObjectNode encoded = parse(
                executionCodec.encodeRequest(carrier).json());
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT_VERSION);
        root.put("kind", RESULT);
        root.put("interruptionKind", result.kind().name());
        root.put("planId", result.planId().value());
        root.put("stepId", result.stepId().value());
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        root.set("startEvent", encoded.get("startEvent").deepCopy());
        ObjectNode checkpoint =
                (ObjectNode) encoded.get("startedCheckpoint").deepCopy();
        checkpoint.put("version", result.interruptedCheckpoint().version());
        root.set("startedCheckpoint", checkpoint);
        return encode(root);
    }

    DecodedRequest decodeRequest(
            int version, String expectedHash, String payload) {
        ObjectNode root = verified(
                version, expectedHash, payload, REQUEST);
        try {
            StepInterruptionKind kind = StepInterruptionKind.valueOf(
                    text(root, "interruptionKind"));
            ObjectNode carrier = json.createObjectNode();
            carrier.put("format",
                    ProductExecutionStartCodec.FORMAT_VERSION);
            carrier.put("kind", "request");
            copy(root, carrier, "planId");
            copy(root, carrier, "leaseToken");
            copy(root, carrier, "fencingToken");
            copy(root, carrier, "startEvent");
            copy(root, carrier, "startedCheckpoint");
            EncodedPayload encoded = encodeRaw(carrier);
            ExecutionStartRequest activation =
                    executionCodec.decodeRequest(
                            ProductExecutionStartCodec.FORMAT_VERSION,
                            encoded.sha256(), encoded.json());
            PlanRevisionId revisionId = new PlanRevisionId(
                    text(root, "expectedRevisionId"));
            long revisionNumber =
                    number(root, "expectedRevisionNumber");
            long checkpointVersion =
                    number(root, "expectedCheckpointVersion");
            long eventSequence =
                    number(root, "expectedEventHeadSequence");
            PlanStepId stepId =
                    new PlanStepId(text(root, "stepId"));
            Object request = switch (kind) {
                case PAUSE -> new StepPauseRequest(
                        activation.planId(), activation.leaseToken(),
                        activation.fencingToken(), revisionId,
                        revisionNumber, checkpointVersion, eventSequence,
                        stepId, activation.startEvent(),
                        activation.startedCheckpoint());
                case FAIL -> new StepFailRequest(
                        activation.planId(), activation.leaseToken(),
                        activation.fencingToken(), revisionId,
                        revisionNumber, checkpointVersion, eventSequence,
                        stepId, activation.startEvent(),
                        activation.startedCheckpoint());
                case CANCEL -> new StepCancelRequest(
                        activation.planId(), activation.leaseToken(),
                        activation.fencingToken(), revisionId,
                        revisionNumber, checkpointVersion, eventSequence,
                        stepId, activation.startEvent(),
                        activation.startedCheckpoint());
            };
            DecodedRequest decoded = new DecodedRequest(
                    kind, request, Candidate.from(kind, request));
            requireCanonical(encodeRequest(decoded.candidate()),
                    expectedHash, payload);
            return decoded;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    PersistedStepInterruption decodeResult(
            int version, String expectedHash, String payload) {
        ObjectNode root = verified(
                version, expectedHash, payload, RESULT);
        try {
            StepInterruptionKind kind = StepInterruptionKind.valueOf(
                    text(root, "interruptionKind"));
            ObjectNode carrier = json.createObjectNode();
            carrier.put("format",
                    ProductExecutionStartCodec.FORMAT_VERSION);
            carrier.put("kind", "request");
            copy(root, carrier, "planId");
            carrier.put("leaseToken", "result-carrier");
            copy(root, carrier, "fencingToken");
            copy(root, carrier, "startEvent");
            ObjectNode checkpoint =
                    (ObjectNode) required(
                            root, "startedCheckpoint").deepCopy();
            long checkpointVersion = number(checkpoint, "version");
            checkpoint.remove("version");
            carrier.set("startedCheckpoint", checkpoint);
            EncodedPayload encoded = encodeRaw(carrier);
            ExecutionStartRequest activation =
                    executionCodec.decodeRequest(
                            ProductExecutionStartCodec.FORMAT_VERSION,
                            encoded.sha256(), encoded.json());
            PersistedStepInterruption result = new PersistedStepInterruption(
                    activation.planId(),
                    new PlanStepId(text(root, "stepId")), kind,
                    text(root, "leaseOwnerId"),
                    activation.fencingToken(), activation.startEvent(),
                    new VersionedCheckpoint(
                            checkpointVersion,
                            activation.startedCheckpoint()));
            requireCanonical(encodeResult(result), expectedHash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    private ObjectNode verified(
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
            JsonNode parsed = json.readTree(bytes);
            if (!(parsed instanceof ObjectNode root)
                    || integer(root, "format") != FORMAT_VERSION
                    || !kind.equals(text(root, "kind"))) {
                throw corrupt();
            }
            return root;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    private ObjectNode parse(String value) {
        try {
            return (ObjectNode) json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to compose V2 step interruption payload",
                    exception);
        }
    }

    private void copy(
            JsonNode source, ObjectNode target, String field) {
        target.set(field, required(source, field).deepCopy());
    }

    private EncodedPayload encode(ObjectNode root) {
        root.put("format", FORMAT_VERSION);
        return encodeRaw(root);
    }

    private EncodedPayload encodeRaw(ObjectNode root) {
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 step interruption", exception);
        }
        return new EncodedPayload(FORMAT_VERSION, sha256(bytes),
                new String(bytes, StandardCharsets.UTF_8));
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

    record DecodedRequest(
            StepInterruptionKind kind, Object request, Candidate candidate) {
    }

    record Candidate(
            StepInterruptionKind kind,
            io.paperagent.v2.contracts.PlanId planId,
            String leaseToken,
            long fencingToken,
            io.paperagent.v2.contracts.PlanRevisionId expectedRevisionId,
            long expectedRevisionNumber,
            long expectedCheckpointVersion,
            long expectedEventHeadSequence,
            io.paperagent.v2.contracts.PlanStepId stepId,
            io.paperagent.v2.contracts.EventEnvelope event,
            io.paperagent.v2.contracts.Checkpoint checkpoint,
            Object request,
            String eventPath,
            String checkpointPath) {

        static Candidate from(StepInterruptionKind kind, Object request) {
            return switch (kind) {
                case PAUSE -> {
                    StepPauseRequest value = (StepPauseRequest) request;
                    yield new Candidate(kind, value.planId(),
                            value.leaseToken(), value.fencingToken(),
                            value.expectedRevisionId(),
                            value.expectedRevisionNumber(),
                            value.expectedCheckpointVersion(),
                            value.expectedEventHeadSequence(), value.stepId(),
                            value.pauseEvent(), value.pausedCheckpoint(), value,
                            "request.pauseEvent",
                            "request.pausedCheckpoint");
                }
                case FAIL -> {
                    StepFailRequest value = (StepFailRequest) request;
                    yield new Candidate(kind, value.planId(),
                            value.leaseToken(), value.fencingToken(),
                            value.expectedRevisionId(),
                            value.expectedRevisionNumber(),
                            value.expectedCheckpointVersion(),
                            value.expectedEventHeadSequence(), value.stepId(),
                            value.failureEvent(), value.failedCheckpoint(), value,
                            "request.failureEvent",
                            "request.failedCheckpoint");
                }
                case CANCEL -> {
                    StepCancelRequest value = (StepCancelRequest) request;
                    yield new Candidate(kind, value.planId(),
                            value.leaseToken(), value.fencingToken(),
                            value.expectedRevisionId(),
                            value.expectedRevisionNumber(),
                            value.expectedCheckpointVersion(),
                            value.expectedEventHeadSequence(), value.stepId(),
                            value.cancellationEvent(),
                            value.cancelledCheckpoint(), value,
                            "request.cancellationEvent",
                            "request.cancelledCheckpoint");
                }
            };
        }

        ExecutionStartRequest executionCarrier() {
            return new ExecutionStartRequest(
                    planId, leaseToken, fencingToken, event, checkpoint);
        }
    }
}
