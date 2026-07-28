package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventPayload;
import io.paperagent.v2.contracts.EventPayloadRef;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical format-1 codec for the immutable active-Step replan marker.
 *
 * <p>Every polymorphic contract value carries an explicit kind. Decode is
 * followed by byte-for-byte canonical re-encoding, so parser coercion and
 * non-canonical stored documents fail closed.</p>
 */
@Component
public final class ProductActiveStepReplanCodec {
    static final int FORMAT_VERSION = 1;
    private static final String CORRUPT =
            "Stored V2 active-Step replan payload is invalid";

    private final ObjectMapper json;

    public ProductActiveStepReplanCodec(ObjectMapper json) {
        this.json = json.copy();
    }

    EncodedPayload encodeRequest(ActiveStepReplanRequest value) {
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT_VERSION);
        root.put("planId", value.planId().value());
        root.put("leaseToken", value.leaseToken());
        root.put("fencingToken", value.fencingToken());
        root.put("expectedRevisionId", value.expectedRevisionId().value());
        root.put("expectedRevisionNumber", value.expectedRevisionNumber());
        root.put("expectedCheckpointVersion",
                value.expectedCheckpointVersion());
        root.put("expectedEventHeadSequence",
                value.expectedEventHeadSequence());
        root.put("activeStepId", value.activeStepId().value());
        root.set("supersessionEvent", eventNode(value.supersessionEvent()));
        root.set("supersededCheckpoint",
                checkpointNode(value.supersededCheckpoint()));
        root.set("replanEvent", eventNode(value.replanEvent()));
        root.set("replannedRevision",
                revisionNode(value.replannedRevision()));
        root.set("replannedCheckpoint",
                checkpointNode(value.replannedCheckpoint()));
        return encode(root);
    }

    ActiveStepReplanRequest decodeRequest(
            int format, String hash, String payload) {
        JsonNode root = verified(format, hash, payload);
        try {
            ActiveStepReplanRequest result = new ActiveStepReplanRequest(
                    new PlanId(text(root, "planId")),
                    text(root, "leaseToken"),
                    number(root, "fencingToken"),
                    new PlanRevisionId(text(root, "expectedRevisionId")),
                    number(root, "expectedRevisionNumber"),
                    number(root, "expectedCheckpointVersion"),
                    number(root, "expectedEventHeadSequence"),
                    new PlanStepId(text(root, "activeStepId")),
                    event(root.get("supersessionEvent")),
                    checkpoint(root.get("supersededCheckpoint")),
                    event(root.get("replanEvent")),
                    revision(root.get("replannedRevision")),
                    checkpoint(root.get("replannedCheckpoint")));
            canonical(encodeRequest(result), hash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    EncodedPayload encodeResult(PersistedActiveStepReplan value) {
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT_VERSION);
        root.put("planId", value.planId().value());
        root.put("supersededStepId", value.supersededStepId().value());
        root.put("leaseOwnerId", value.leaseOwnerId());
        root.put("fencingToken", value.fencingToken());
        root.set("supersessionEvent", eventNode(value.supersessionEvent()));
        root.set("supersededCheckpoint",
                versionedCheckpointNode(value.supersededCheckpoint()));
        root.set("replanEvent", eventNode(value.replanEvent()));
        root.set("replannedRevision",
                revisionNode(value.replannedRevision()));
        root.set("replannedCheckpoint",
                versionedCheckpointNode(value.replannedCheckpoint()));
        return encode(root);
    }

    PersistedActiveStepReplan decodeResult(
            int format, String hash, String payload) {
        JsonNode root = verified(format, hash, payload);
        try {
            PersistedActiveStepReplan result =
                    new PersistedActiveStepReplan(
                            new PlanId(text(root, "planId")),
                            new PlanStepId(text(root, "supersededStepId")),
                            text(root, "leaseOwnerId"),
                            number(root, "fencingToken"),
                            event(root.get("supersessionEvent")),
                            versionedCheckpoint(
                                    root.get("supersededCheckpoint")),
                            event(root.get("replanEvent")),
                            revision(root.get("replannedRevision")),
                            versionedCheckpoint(
                                    root.get("replannedCheckpoint")));
            canonical(encodeResult(result), hash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    private EncodedPayload encode(ObjectNode root) {
        try {
            byte[] bytes = json.writeValueAsBytes(root);
            return new EncodedPayload(
                    FORMAT_VERSION, sha256(bytes),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 active-Step replan", exception);
        }
    }

    private JsonNode verified(int format, String hash, String payload) {
        if (format != FORMAT_VERSION) {
            throw corrupt();
        }
        try {
            byte[] bytes = required(payload)
                    .getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(
                    sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                    required(hash).getBytes(StandardCharsets.US_ASCII))) {
                throw corrupt();
            }
            JsonNode root = json.readTree(bytes);
            if (root == null || !root.isObject()
                    || integer(root, "format") != FORMAT_VERSION) {
                throw corrupt();
            }
            return root;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    private static void canonical(
            EncodedPayload encoded, String hash, String payload) {
        if (!encoded.sha256().equals(hash)
                || !encoded.json().equals(payload)) {
            throw corrupt();
        }
    }

    private ObjectNode eventNode(EventEnvelope value) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("taskFrameId", value.taskFrameId().value());
        node.put("planId", value.planId().value());
        node.put("sequence", value.sequence());
        node.put("occurredAt", value.occurredAt().toString());
        node.put("type", value.type().value());
        value.causationId().ifPresentOrElse(
                id -> node.put("causationId", id.value()),
                () -> node.putNull("causationId"));
        node.put("correlationId", value.correlationId());
        node.set("payload", payloadNode(value.payload()));
        return node;
    }

    private EventEnvelope event(JsonNode node) {
        JsonNode cause = requiredNode(node, "causationId");
        return new EventEnvelope(
                new EventId(text(node, "id")),
                new TaskFrameId(text(node, "taskFrameId")),
                new PlanId(text(node, "planId")),
                number(node, "sequence"),
                Instant.parse(text(node, "occurredAt")),
                new EventType(text(node, "type")),
                cause.isNull()
                        ? Optional.empty()
                        : Optional.of(new EventId(cause.textValue())),
                text(node, "correlationId"),
                payload(requiredNode(node, "payload")));
    }

    private ObjectNode payloadNode(EventPayload value) {
        ObjectNode node = json.createObjectNode();
        if (value instanceof InlineEventPayload inline) {
            node.put("kind", "inline");
            node.set("value", contractValueNode(inline.value()));
        } else if (value instanceof EventPayloadRef reference) {
            node.put("kind", "ref");
            node.put("reference", reference.reference());
        } else {
            throw new IllegalArgumentException("unsupported event payload");
        }
        return node;
    }

    private EventPayload payload(JsonNode node) {
        return switch (text(node, "kind")) {
            case "inline" -> new InlineEventPayload(
                    (ObjectValue) contractValue(
                            requiredNode(node, "value")));
            case "ref" -> new EventPayloadRef(text(node, "reference"));
            default -> throw corrupt();
        };
    }

    private ObjectNode revisionNode(PlanRevision value) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("taskFrameId", value.taskFrameId().value());
        node.put("number", value.number());
        value.parentRevisionId().ifPresentOrElse(
                id -> node.put("parentRevisionId", id.value()),
                () -> node.putNull("parentRevisionId"));
        node.put("reason", value.reason());
        node.put("createdAt", value.createdAt().toString());
        ArrayNode steps = json.createArrayNode();
        value.steps().forEach(step -> steps.add(stepNode(step)));
        node.set("steps", steps);
        ArrayNode facts = json.createArrayNode();
        value.completedFacts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(PlanStepId::value)))
                .forEach(entry -> facts.add(factNode(entry.getValue())));
        node.set("completedFacts", facts);
        return node;
    }

    private PlanRevision revision(JsonNode node) {
        List<PlanStep> steps = new ArrayList<>();
        array(node, "steps").forEach(value -> steps.add(step(value)));
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>();
        array(node, "completedFacts").forEach(value -> {
            CompletionFact fact = fact(value);
            if (facts.put(fact.stepId(), fact) != null) {
                throw corrupt();
            }
        });
        JsonNode parent = requiredNode(node, "parentRevisionId");
        return new PlanRevision(
                new PlanRevisionId(text(node, "id")),
                new TaskFrameId(text(node, "taskFrameId")),
                number(node, "number"),
                parent.isNull()
                        ? Optional.empty()
                        : Optional.of(new PlanRevisionId(
                                parent.textValue())),
                text(node, "reason"),
                Instant.parse(text(node, "createdAt")),
                steps, facts);
    }

    private ObjectNode stepNode(PlanStep value) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("intent", value.intent());
        node.put("expectedOutcome", value.expectedOutcome());
        ArrayNode dependencies = json.createArrayNode();
        value.dependencies().stream()
                .map(PlanStepId::value).sorted()
                .forEach(dependencies::add);
        node.set("dependencies", dependencies);
        ArrayNode criteria = json.createArrayNode();
        value.completionCriteria().forEach(criteria::add);
        node.set("completionCriteria", criteria);
        ObjectNode hints = json.createObjectNode();
        hints.put("maxAttempts", value.executionHints().maxAttempts());
        hints.put("maxDuration",
                value.executionHints().maxDuration().toString());
        node.set("executionHints", hints);
        return node;
    }

    private PlanStep step(JsonNode node) {
        LinkedHashSet<PlanStepId> dependencies = new LinkedHashSet<>();
        array(node, "dependencies").forEach(value -> {
            if (!value.isTextual()
                    || !dependencies.add(
                            new PlanStepId(value.textValue()))) {
                throw corrupt();
            }
        });
        List<String> criteria = new ArrayList<>();
        array(node, "completionCriteria").forEach(value -> {
            if (!value.isTextual()) {
                throw corrupt();
            }
            criteria.add(value.textValue());
        });
        JsonNode hints = requiredNode(node, "executionHints");
        return new PlanStep(
                new PlanStepId(text(node, "id")),
                text(node, "intent"),
                text(node, "expectedOutcome"),
                dependencies,
                criteria,
                new BoundedExecutionHints(
                        integer(hints, "maxAttempts"),
                        Duration.parse(text(hints, "maxDuration"))));
    }

    private ObjectNode factNode(CompletionFact value) {
        ObjectNode node = json.createObjectNode();
        node.put("stepId", value.stepId().value());
        node.put("outcomeHash", value.outcomeHash());
        node.put("completedAt", value.completedAt().toString());
        ArrayNode receipts = json.createArrayNode();
        value.receiptReferences().forEach(
                id -> receipts.add(id.value()));
        node.set("receiptReferences", receipts);
        return node;
    }

    private CompletionFact fact(JsonNode node) {
        List<ReceiptId> receipts = new ArrayList<>();
        array(node, "receiptReferences").forEach(value -> {
            if (!value.isTextual()) {
                throw corrupt();
            }
            receipts.add(new ReceiptId(value.textValue()));
        });
        return new CompletionFact(
                new PlanStepId(text(node, "stepId")),
                text(node, "outcomeHash"),
                Instant.parse(text(node, "completedAt")),
                receipts);
    }

    private ObjectNode versionedCheckpointNode(VersionedCheckpoint value) {
        ObjectNode node = json.createObjectNode();
        node.put("version", value.version());
        node.set("checkpoint", checkpointNode(value.checkpoint()));
        return node;
    }

    private VersionedCheckpoint versionedCheckpoint(JsonNode node) {
        return new VersionedCheckpoint(
                number(node, "version"),
                checkpoint(requiredNode(node, "checkpoint")));
    }

    private ObjectNode checkpointNode(Checkpoint value) {
        ObjectNode node = json.createObjectNode();
        node.put("taskFrameId", value.taskFrameId().value());
        node.put("planId", value.planId().value());
        node.put("revisionId", value.revisionId().value());
        node.put("revisionNumber", value.revisionNumber());
        node.put("lastEventSequence", value.lastEventSequence());
        node.put("planState", value.planState().name());
        ArrayNode states = json.createArrayNode();
        value.stepStates().entrySet().stream()
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
        value.receiptReferences().forEach(
                id -> receipts.add(id.value()));
        node.set("receiptReferences", receipts);
        node.put("createdAt", value.createdAt().toString());
        return node;
    }

    private Checkpoint checkpoint(JsonNode node) {
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>();
        array(node, "stepStates").forEach(value -> {
            PlanStepId id = new PlanStepId(text(value, "stepId"));
            if (states.put(id, StepExecutionState.valueOf(
                    text(value, "state"))) != null) {
                throw corrupt();
            }
        });
        List<ReceiptId> receipts = new ArrayList<>();
        array(node, "receiptReferences").forEach(value -> {
            if (!value.isTextual()) {
                throw corrupt();
            }
            receipts.add(new ReceiptId(value.textValue()));
        });
        return new Checkpoint(
                new TaskFrameId(text(node, "taskFrameId")),
                new PlanId(text(node, "planId")),
                new PlanRevisionId(text(node, "revisionId")),
                number(node, "revisionNumber"),
                number(node, "lastEventSequence"),
                PlanExecutionState.valueOf(text(node, "planState")),
                states, receipts,
                Instant.parse(text(node, "createdAt")));
    }

    private JsonNode contractValueNode(ContractValue value) {
        ObjectNode node = json.createObjectNode();
        if (value instanceof TextValue text) {
            node.put("kind", "text");
            node.put("value", text.value());
        } else if (value instanceof NumberValue number) {
            node.put("kind", "number");
            node.put("value", number.value().toPlainString());
        } else if (value instanceof BooleanValue bool) {
            node.put("kind", "boolean");
            node.put("value", bool.value());
        } else if (value instanceof NullValue) {
            node.put("kind", "null");
        } else if (value instanceof ListValue list) {
            node.put("kind", "list");
            ArrayNode values = json.createArrayNode();
            list.values().forEach(item ->
                    values.add(contractValueNode(item)));
            node.set("value", values);
        } else if (value instanceof ObjectValue object) {
            node.put("kind", "object");
            ArrayNode values = json.createArrayNode();
            object.values().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("key", entry.getKey());
                        item.set("value",
                                contractValueNode(entry.getValue()));
                        values.add(item);
                    });
            node.set("value", values);
        } else {
            throw new IllegalArgumentException(
                    "unsupported contract value");
        }
        return node;
    }

    private ContractValue contractValue(JsonNode node) {
        return switch (text(node, "kind")) {
            case "text" -> new TextValue(text(node, "value"));
            case "number" -> new NumberValue(
                    new BigDecimal(text(node, "value")));
            case "boolean" -> new BooleanValue(
                    bool(node, "value"));
            case "null" -> NullValue.INSTANCE;
            case "list" -> {
                List<ContractValue> values = new ArrayList<>();
                array(node, "value").forEach(
                        item -> values.add(contractValue(item)));
                yield new ListValue(values);
            }
            case "object" -> {
                Map<String, ContractValue> values =
                        new LinkedHashMap<>();
                array(node, "value").forEach(item -> {
                    String key = text(item, "key");
                    if (values.put(key, contractValue(
                            requiredNode(item, "value"))) != null) {
                        throw corrupt();
                    }
                });
                yield new ObjectValue(values);
            }
            default -> throw corrupt();
        };
    }

    private static JsonNode requiredNode(JsonNode node, String name) {
        if (node == null || !node.isObject() || !node.has(name)) {
            throw corrupt();
        }
        return node.get(name);
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    private static long number(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!value.isBoolean()) {
            throw corrupt();
        }
        return value.booleanValue();
    }

    private static ArrayNode array(JsonNode node, String name) {
        JsonNode value = requiredNode(node, name);
        if (!(value instanceof ArrayNode array)) {
            throw corrupt();
        }
        return array;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw corrupt();
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException(CORRUPT);
    }

    record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
