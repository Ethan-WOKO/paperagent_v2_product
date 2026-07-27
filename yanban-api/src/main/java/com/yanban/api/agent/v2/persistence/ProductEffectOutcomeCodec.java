package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistedEffectResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class ProductEffectOutcomeCodec {
    static final int FORMAT_VERSION = 1;
    private static final Set<String> PROGRESS_REQUEST_FIELDS = Set.of(
            "format", "progressId", "toolCallId", "sequence", "occurredAt",
            "details", "leaseToken", "fencingToken");
    private static final Set<String> PROGRESS_RESULT_FIELDS = Set.of(
            "format", "progressId", "toolCallId", "sequence", "occurredAt",
            "details", "leaseOwnerId", "fencingToken");
    private static final Set<String> RESULT_REQUEST_FIELDS = Set.of(
            "format", "receiptFormatVersion", "receiptSha256", "receiptJson",
            "leaseToken", "fencingToken");
    private static final Set<String> RESULT_RESULT_FIELDS = Set.of(
            "format", "receiptFormatVersion", "receiptSha256", "receiptJson",
            "leaseOwnerId", "fencingToken");

    private final ObjectMapper mapper;
    private final ProductReceiptCodec receiptCodec;

    ProductEffectOutcomeCodec(
            ObjectMapper mapper,
            ProductReceiptCodec receiptCodec) {
        this.mapper = mapper;
        this.receiptCodec = receiptCodec;
    }

    EncodedPayload encodeProgressRequest(EffectProgressRequest request) {
        ObjectNode root = progress(
                "effect-progress-request", request.progress());
        root.put("leaseToken", request.leaseToken());
        root.put("fencingToken", request.fencingToken());
        return encode(root);
    }

    EncodedPayload encodeProgressResult(PersistedEffectProgress result) {
        ObjectNode root = progress(
                "persisted-effect-progress", result.progress());
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        return encode(root);
    }

    EffectProgressRequest decodeProgressRequest(
            int version, String hash, String json) {
        try {
            ObjectNode root = root(
                    version, hash, json, PROGRESS_REQUEST_FIELDS,
                    "effect-progress-request");
            EffectProgressRequest request = new EffectProgressRequest(
                    decodeProgress(root), text(root, "leaseToken"),
                    positive(root, "fencingToken"));
            canonical(encodeProgressRequest(request), hash, json);
            return request;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    PersistedEffectProgress decodeProgressResult(
            int version, String hash, String json) {
        try {
            ObjectNode root = root(
                    version, hash, json, PROGRESS_RESULT_FIELDS,
                    "persisted-effect-progress");
            PersistedEffectProgress result = new PersistedEffectProgress(
                    decodeProgress(root), text(root, "leaseOwnerId"),
                    positive(root, "fencingToken"));
            canonical(encodeProgressResult(result), hash, json);
            return result;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    EncodedPayload encodeResultRequest(EffectResultRequest request) {
        ObjectNode root = receipt(
                "effect-result-request", request.receipt());
        root.put("leaseToken", request.leaseToken());
        root.put("fencingToken", request.fencingToken());
        return encode(root);
    }

    EncodedPayload encodeResultResult(PersistedEffectResult result) {
        ObjectNode root = receipt(
                "persisted-effect-result", result.receipt());
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        return encode(root);
    }

    EffectResultRequest decodeResultRequest(
            int version, String hash, String json) {
        try {
            ObjectNode root = root(
                    version, hash, json, RESULT_REQUEST_FIELDS,
                    "effect-result-request");
            EffectResultRequest request = new EffectResultRequest(
                    decodeReceipt(root), text(root, "leaseToken"),
                    positive(root, "fencingToken"));
            canonical(encodeResultRequest(request), hash, json);
            return request;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    PersistedEffectResult decodeResultResult(
            int version, String hash, String json) {
        try {
            ObjectNode root = root(
                    version, hash, json, RESULT_RESULT_FIELDS,
                    "persisted-effect-result");
            PersistedEffectResult result = new PersistedEffectResult(
                    decodeReceipt(root), text(root, "leaseOwnerId"),
                    positive(root, "fencingToken"));
            canonical(encodeResultResult(result), hash, json);
            return result;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private ObjectNode progress(String format, EffectProgress progress) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", format);
        root.put("progressId", progress.id().value());
        root.put("toolCallId", progress.toolCallId().value());
        root.put("sequence", progress.sequence());
        root.put("occurredAt", progress.occurredAt().toString());
        root.set("details", value(progress.details()));
        return root;
    }

    private EffectProgress decodeProgress(ObjectNode root) {
        ContractValue details = value(required(root, "details"));
        if (!(details instanceof ObjectValue object)) {
            throw invalid();
        }
        return new EffectProgress(
                new EffectProgressId(text(root, "progressId")),
                new ToolCallId(text(root, "toolCallId")),
                positive(root, "sequence"),
                Instant.parse(text(root, "occurredAt")),
                object);
    }

    private ObjectNode receipt(String format, ExecutionReceipt receipt) {
        ProductReceiptCodec.EncodedPayload encoded =
                receiptCodec.encode(receipt);
        ObjectNode root = mapper.createObjectNode();
        root.put("format", format);
        root.put("receiptFormatVersion", encoded.formatVersion());
        root.put("receiptSha256", encoded.sha256());
        root.put("receiptJson", encoded.json());
        return root;
    }

    private ExecutionReceipt decodeReceipt(ObjectNode root) {
        return receiptCodec.decode(
                positiveInt(root, "receiptFormatVersion"),
                text(root, "receiptSha256"),
                rawText(root, "receiptJson"));
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
            int version,
            String expectedHash,
            String json,
            Set<String> expectedFields,
            String expectedFormat) {
        if (version != FORMAT_VERSION || expectedHash == null || json == null
                || !sha256(json).equals(expectedHash)) {
            throw invalid();
        }
        try {
            JsonNode parsed = mapper.readTree(json);
            if (!(parsed instanceof ObjectNode root)) {
                throw invalid();
            }
            fields(root, expectedFields);
            if (!expectedFormat.equals(text(root, "format"))) {
                throw invalid();
            }
            return root;
        } catch (Exception exception) {
            throw invalid();
        }
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
            EncodedPayload encoded, String expectedHash, String json) {
        if (!encoded.sha256().equals(expectedHash)
                || !encoded.json().equals(json)) {
            throw invalid();
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
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
        String value = rawText(node, field);
        if (value.isBlank()) {
            throw invalid();
        }
        return value;
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

    private static int positiveInt(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.canConvertToInt() || value.intValue() < 1) {
            throw invalid();
        }
        return value.intValue();
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
                "effect outcome persistence payload is invalid");
    }

    record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
