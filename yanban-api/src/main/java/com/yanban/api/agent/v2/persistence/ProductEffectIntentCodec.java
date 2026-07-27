package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class ProductEffectIntentCodec {
    static final int FORMAT_VERSION = 1;
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "format", "toolCallId", "planId", "stepId", "kind", "arguments",
            "leaseToken", "fencingToken", "activationEventId");
    private static final Set<String> RESULT_FIELDS = Set.of(
            "format", "toolCallId", "planId", "stepId", "kind", "arguments",
            "leaseOwnerId", "fencingToken", "activationEventId");

    private final ObjectMapper mapper;

    ProductEffectIntentCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    EncodedPayload encodeRequest(EffectIntentRequest request) {
        ObjectNode root = intent("effect-intent-request", request.intent());
        root.put("leaseToken", request.leaseToken());
        root.put("fencingToken", request.fencingToken());
        root.put("activationEventId",
                request.expectedActivationEventId().value());
        return encode(root);
    }

    EncodedPayload encodeResult(PersistedEffectIntent result) {
        ObjectNode root = intent("persisted-effect-intent", result.intent());
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        root.put("activationEventId", result.activationEventId().value());
        return encode(root);
    }

    EffectIntentRequest decodeRequest(
            int formatVersion, String expectedHash, String payload) {
        try {
            ObjectNode root = root(formatVersion, expectedHash, payload,
                    REQUEST_FIELDS, "effect-intent-request");
            EffectIntentRequest result = new EffectIntentRequest(
                    decodeIntent(root), text(root, "leaseToken"),
                    positive(root, "fencingToken"),
                    new EventId(text(root, "activationEventId")));
            canonical(encodeRequest(result), expectedHash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    PersistedEffectIntent decodeResult(
            int formatVersion, String expectedHash, String payload) {
        try {
            ObjectNode root = root(formatVersion, expectedHash, payload,
                    RESULT_FIELDS, "persisted-effect-intent");
            PersistedEffectIntent result = new PersistedEffectIntent(
                    decodeIntent(root), text(root, "leaseOwnerId"),
                    positive(root, "fencingToken"),
                    new EventId(text(root, "activationEventId")));
            canonical(encodeResult(result), expectedHash, payload);
            return result;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private ObjectNode intent(String format, EffectIntent intent) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", format);
        root.put("toolCallId", intent.toolCallId().value());
        root.put("planId", intent.planId().value());
        root.put("stepId", intent.stepId().value());
        root.put("kind", intent.kind());
        root.set("arguments", value(intent.arguments()));
        return root;
    }

    private EffectIntent decodeIntent(ObjectNode root) {
        ContractValue arguments = value(required(root, "arguments"));
        if (!(arguments instanceof ObjectValue object)) {
            throw invalid();
        }
        return new EffectIntent(
                new ToolCallId(text(root, "toolCallId")),
                new PlanId(text(root, "planId")),
                new PlanStepId(text(root, "stepId")),
                text(root, "kind"), object);
    }

    private JsonNode value(ContractValue value) {
        ObjectNode node = mapper.createObjectNode();
        if (value instanceof TextValue text) {
            node.put("type", "text");
            node.put("value", text.value());
        } else if (value instanceof NumberValue number) {
            node.put("type", "number");
            node.put("value", number.value().toPlainString());
        } else if (value instanceof BooleanValue bool) {
            node.put("type", "boolean");
            node.put("value", bool.value());
        } else if (value == NullValue.INSTANCE) {
            node.put("type", "null");
        } else if (value instanceof ListValue list) {
            node.put("type", "list");
            ArrayNode values = node.putArray("values");
            list.values().forEach(item -> values.add(value(item)));
        } else if (value instanceof ObjectValue object) {
            node.put("type", "object");
            ObjectNode values = node.putObject("values");
            object.values().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> values.set(
                            entry.getKey(), value(entry.getValue())));
        } else {
            throw invalid();
        }
        return node;
    }

    private ContractValue value(JsonNode node) {
        if (!node.isObject()) {
            throw invalid();
        }
        String type = text(node, "type");
        return switch (type) {
            case "text" -> {
                fields(node, Set.of("type", "value"));
                yield new TextValue(rawText(node, "value"));
            }
            case "number" -> {
                fields(node, Set.of("type", "value"));
                yield new NumberValue(new BigDecimal(text(node, "value")));
            }
            case "boolean" -> {
                fields(node, Set.of("type", "value"));
                JsonNode raw = required(node, "value");
                if (!raw.isBoolean()) {
                    throw invalid();
                }
                yield new BooleanValue(raw.booleanValue());
            }
            case "null" -> {
                fields(node, Set.of("type"));
                yield NullValue.INSTANCE;
            }
            case "list" -> {
                fields(node, Set.of("type", "values"));
                JsonNode raw = required(node, "values");
                if (!raw.isArray()) {
                    throw invalid();
                }
                List<ContractValue> values = new ArrayList<>();
                raw.forEach(item -> values.add(value(item)));
                yield new ListValue(values);
            }
            case "object" -> {
                fields(node, Set.of("type", "values"));
                JsonNode raw = required(node, "values");
                if (!raw.isObject()) {
                    throw invalid();
                }
                List<String> names = new ArrayList<>();
                raw.fieldNames().forEachRemaining(names::add);
                names.sort(Comparator.naturalOrder());
                Map<String, ContractValue> values = new LinkedHashMap<>();
                names.forEach(name -> values.put(name, value(raw.get(name))));
                yield new ObjectValue(values);
            }
            default -> throw invalid();
        };
    }

    private ObjectNode root(
            int formatVersion, String expectedHash, String payload,
            Set<String> expectedFields, String format) {
        if (formatVersion != FORMAT_VERSION || expectedHash == null
                || payload == null || !sha256(payload).equals(expectedHash)) {
            throw invalid();
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(payload);
        } catch (Exception exception) {
            throw invalid();
        }
        if (!(parsed instanceof ObjectNode root)) {
            throw invalid();
        }
        fields(root, expectedFields);
        if (!format.equals(text(root, "format"))) {
            throw invalid();
        }
        return root;
    }

    private EncodedPayload encode(ObjectNode root) {
        try {
            String json = mapper.writeValueAsString(root);
            return new EncodedPayload(FORMAT_VERSION, sha256(json), json);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void canonical(
            EncodedPayload encoded, String expectedHash, String payload) {
        if (!encoded.sha256().equals(expectedHash)
                || !encoded.json().equals(payload)) {
            throw invalid();
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid();
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw invalid();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String rawText(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static long positive(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.canConvertToLong() || value.longValue() < 1) {
            throw invalid();
        }
        return value.longValue();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "effect intent persistence payload is invalid");
    }

    record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
