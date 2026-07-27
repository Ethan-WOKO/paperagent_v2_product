package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventPayloadRef;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public final class ProductExecutionStartCodec {
    static final int FORMAT_VERSION = 1;
    private static final String CORRUPT =
            "Stored V2 execution start payload is invalid";

    private final ObjectMapper json;

    public ProductExecutionStartCodec(ObjectMapper json) {
        this.json = json.copy();
    }

    EncodedPayload encodeRequest(ExecutionStartRequest request) {
        ObjectNode root = root("request");
        root.put("planId", request.planId().value());
        root.put("leaseToken", request.leaseToken());
        root.put("fencingToken", request.fencingToken());
        root.set("startEvent", eventNode(request.startEvent()));
        root.set("startedCheckpoint", checkpointNode(request.startedCheckpoint()));
        return encode(root);
    }

    EncodedPayload encodeResult(PersistedExecutionStart result) {
        ObjectNode root = root("result");
        root.put("planId", result.planId().value());
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        root.set("startEvent", eventNode(result.startEvent()));
        ObjectNode versioned = checkpointNode(result.startedCheckpoint().checkpoint());
        versioned.put("version", result.startedCheckpoint().version());
        root.set("startedCheckpoint", versioned);
        return encode(root);
    }

    ExecutionStartRequest decodeRequest(
            int formatVersion, String expectedHash, String payload) {
        JsonNode root = verified(formatVersion, expectedHash, payload, "request");
        try {
            ExecutionStartRequest result = new ExecutionStartRequest(
                    new PlanId(text(root, "planId")),
                    text(root, "leaseToken"),
                    number(root, "fencingToken"),
                    event(required(root, "startEvent")),
                    checkpoint(required(root, "startedCheckpoint")));
            requireCanonical(encodeRequest(result), expectedHash, payload);
            return result;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    PersistedExecutionStart decodeResult(
            int formatVersion, String expectedHash, String payload) {
        JsonNode root = verified(formatVersion, expectedHash, payload, "result");
        try {
            JsonNode checkpoint = required(root, "startedCheckpoint");
            PersistedExecutionStart result = new PersistedExecutionStart(
                    new PlanId(text(root, "planId")),
                    text(root, "leaseOwnerId"),
                    number(root, "fencingToken"),
                    event(required(root, "startEvent")),
                    new VersionedCheckpoint(
                            number(checkpoint, "version"),
                            checkpoint(checkpoint)));
            requireCanonical(encodeResult(result), expectedHash, payload);
            return result;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    private ObjectNode root(String kind) {
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT_VERSION);
        root.put("kind", kind);
        return root;
    }

    private EncodedPayload encode(ObjectNode root) {
        try {
            byte[] bytes = json.writeValueAsBytes(root);
            return new EncodedPayload(
                    FORMAT_VERSION,
                    sha256(bytes),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 execution start", exception);
        }
    }

    private JsonNode verified(
            int formatVersion,
            String expectedHash,
            String payload,
            String kind) {
        if (formatVersion != FORMAT_VERSION) {
            throw corrupt();
        }
        try {
            byte[] bytes = requiredText(payload).getBytes(StandardCharsets.UTF_8);
            String actual = sha256(bytes);
            if (!MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII),
                    requiredText(expectedHash).getBytes(StandardCharsets.US_ASCII))) {
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

    private ObjectNode eventNode(EventEnvelope event) {
        ObjectNode node = json.createObjectNode();
        node.put("id", event.id().value());
        node.put("taskFrameId", event.taskFrameId().value());
        node.put("planId", event.planId().value());
        node.put("sequence", event.sequence());
        node.put("occurredAt", event.occurredAt().toString());
        node.put("type", event.type().value());
        event.causationId().ifPresentOrElse(
                value -> node.put("causationId", value.value()),
                () -> node.putNull("causationId"));
        node.put("correlationId", event.correlationId());
        node.set("payload", payloadNode(event.payload()));
        return node;
    }

    private EventEnvelope event(JsonNode node) {
        JsonNode causation = required(node, "causationId");
        return new EventEnvelope(
                new EventId(text(node, "id")),
                new TaskFrameId(text(node, "taskFrameId")),
                new PlanId(text(node, "planId")),
                number(node, "sequence"),
                Instant.parse(text(node, "occurredAt")),
                new EventType(text(node, "type")),
                causation.isNull()
                        ? Optional.empty()
                        : Optional.of(new EventId(textValue(causation))),
                text(node, "correlationId"),
                payload(required(node, "payload")));
    }

    private ObjectNode payloadNode(EventPayload payload) {
        ObjectNode node = json.createObjectNode();
        if (payload instanceof InlineEventPayload inline) {
            node.put("type", "inline");
            node.set("value", valueNode(inline.value()));
            return node;
        }
        if (payload instanceof EventPayloadRef reference) {
            node.put("type", "reference");
            node.put("reference", reference.reference());
            return node;
        }
        throw new IllegalArgumentException("Unsupported event payload");
    }

    private EventPayload payload(JsonNode node) {
        return switch (text(node, "type")) {
            case "inline" -> new InlineEventPayload(
                    (ObjectValue) value(required(node, "value")));
            case "reference" -> new EventPayloadRef(text(node, "reference"));
            default -> throw corrupt();
        };
    }

    private ObjectNode valueNode(ContractValue value) {
        ObjectNode node = json.createObjectNode();
        if (value instanceof TextValue text) {
            node.put("type", "text");
            node.put("value", text.value());
        } else if (value instanceof NumberValue number) {
            node.put("type", "number");
            node.put("value", number.value().toString());
        } else if (value instanceof BooleanValue bool) {
            node.put("type", "boolean");
            node.put("value", bool.value());
        } else if (value == NullValue.INSTANCE) {
            node.put("type", "null");
            node.putNull("value");
        } else if (value instanceof ListValue list) {
            node.put("type", "list");
            ArrayNode values = json.createArrayNode();
            list.values().forEach(item -> values.add(valueNode(item)));
            node.set("value", values);
        } else if (value instanceof ObjectValue object) {
            node.put("type", "object");
            ArrayNode entries = json.createArrayNode();
            object.values().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("key", entry.getKey());
                        item.set("value", valueNode(entry.getValue()));
                        entries.add(item);
                    });
            node.set("value", entries);
        } else {
            throw new IllegalArgumentException("Unsupported contract value");
        }
        return node;
    }

    private ContractValue value(JsonNode node) {
        JsonNode encoded = required(node, "value");
        return switch (text(node, "type")) {
            case "text" -> new TextValue(textValue(encoded));
            case "number" -> {
                yield new NumberValue(new java.math.BigDecimal(
                        textValue(encoded)));
            }
            case "boolean" -> {
                if (!encoded.isBoolean()) {
                    throw corrupt();
                }
                yield new BooleanValue(encoded.booleanValue());
            }
            case "null" -> {
                if (!encoded.isNull()) {
                    throw corrupt();
                }
                yield NullValue.INSTANCE;
            }
            case "list" -> {
                if (!encoded.isArray()) {
                    throw corrupt();
                }
                List<ContractValue> values = new ArrayList<>();
                encoded.forEach(item -> values.add(value(item)));
                yield new ListValue(values);
            }
            case "object" -> {
                if (!encoded.isArray()) {
                    throw corrupt();
                }
                Map<String, ContractValue> values = new LinkedHashMap<>();
                encoded.forEach(item -> {
                    String key = text(item, "key");
                    if (values.put(key, value(required(item, "value"))) != null) {
                        throw corrupt();
                    }
                });
                yield new ObjectValue(values);
            }
            default -> throw corrupt();
        };
    }

    private ObjectNode checkpointNode(Checkpoint checkpoint) {
        ObjectNode node = json.createObjectNode();
        node.put("taskFrameId", checkpoint.taskFrameId().value());
        node.put("planId", checkpoint.planId().value());
        node.put("revisionId", checkpoint.revisionId().value());
        node.put("revisionNumber", checkpoint.revisionNumber());
        node.put("lastEventSequence", checkpoint.lastEventSequence());
        node.put("planState", checkpoint.planState().name());
        ArrayNode states = json.createArrayNode();
        checkpoint.stepStates().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PlanStepId::value)))
                .forEach(entry -> {
                    ObjectNode state = json.createObjectNode();
                    state.put("stepId", entry.getKey().value());
                    state.put("state", entry.getValue().name());
                    states.add(state);
                });
        node.set("stepStates", states);
        ArrayNode receipts = json.createArrayNode();
        checkpoint.receiptReferences().forEach(
                value -> receipts.add(value.value()));
        node.set("receiptReferences", receipts);
        node.put("createdAt", checkpoint.createdAt().toString());
        return node;
    }

    private Checkpoint checkpoint(JsonNode node) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        array(node, "stepStates").forEach(item -> {
            PlanStepId id = new PlanStepId(text(item, "stepId"));
            if (states.put(id, StepExecutionState.valueOf(text(item, "state")))
                    != null) {
                throw corrupt();
            }
        });
        List<ReceiptId> receipts = new ArrayList<>();
        array(node, "receiptReferences").forEach(
                value -> receipts.add(new ReceiptId(textValue(value))));
        return new Checkpoint(
                new TaskFrameId(text(node, "taskFrameId")),
                new PlanId(text(node, "planId")),
                new PlanRevisionId(text(node, "revisionId")),
                number(node, "revisionNumber"),
                number(node, "lastEventSequence"),
                PlanExecutionState.valueOf(text(node, "planState")),
                states,
                receipts,
                Instant.parse(text(node, "createdAt")));
    }

    private static void requireCanonical(
            EncodedPayload canonical, String hash, String payload) {
        if (!canonical.sha256().equals(hash) || !canonical.json().equals(payload)) {
            throw corrupt();
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field)) {
            throw corrupt();
        }
        return node.get(field);
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isArray()) {
            throw corrupt();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        return textValue(required(node, field));
    }

    private static String textValue(JsonNode value) {
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

    private static String requiredText(String value) {
        if (value == null) {
            throw corrupt();
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
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
