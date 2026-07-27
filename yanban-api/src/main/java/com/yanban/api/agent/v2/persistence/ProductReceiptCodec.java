package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ArtifactRef;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
class ProductReceiptCodec {
    static final int FORMAT_VERSION = 1;
    private static final Set<String> FIELDS = Set.of(
            "format", "receiptId", "toolCallId", "status", "startedAt",
            "endedAt", "exitCode", "resultCode", "standardOutput",
            "standardError", "artifactReferences", "resultingDiff",
            "eventReferences");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "inlineText", "artifactRef", "truncated");

    private final ObjectMapper mapper;

    ProductReceiptCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    EncodedPayload encode(ExecutionReceipt receipt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("format", "execution-receipt");
        root.put("receiptId", receipt.id().value());
        root.put("toolCallId", receipt.toolCallId().value());
        root.put("status", receipt.status().name());
        root.put("startedAt", receipt.startedAt().toString());
        root.put("endedAt", receipt.endedAt().toString());
        optionalInteger(root, "exitCode", receipt.exitCode());
        optionalText(root, "resultCode", receipt.resultCode());
        root.set("standardOutput", output(receipt.standardOutput()));
        root.set("standardError", output(receipt.standardError()));
        ArrayNode artifacts = root.putArray("artifactReferences");
        receipt.artifactReferences().forEach(
                artifact -> artifacts.add(artifact.value()));
        optionalText(root, "resultingDiff",
                receipt.resultingDiff().map(DiffId::value));
        ArrayNode events = root.putArray("eventReferences");
        receipt.eventReferences().forEach(event -> events.add(event.value()));
        return encode(root);
    }

    ExecutionReceipt decode(
            int formatVersion, String expectedHash, String payload) {
        try {
            ObjectNode root = root(formatVersion, expectedHash, payload);
            ExecutionReceipt receipt = new ExecutionReceipt(
                    new ReceiptId(text(root, "receiptId")),
                    new ToolCallId(text(root, "toolCallId")),
                    ReceiptStatus.valueOf(text(root, "status")),
                    Instant.parse(text(root, "startedAt")),
                    Instant.parse(text(root, "endedAt")),
                    optionalInteger(root, "exitCode"),
                    optionalText(root, "resultCode"),
                    output(required(root, "standardOutput")),
                    output(required(root, "standardError")),
                    artifacts(required(root, "artifactReferences")),
                    optionalText(root, "resultingDiff").map(DiffId::new),
                    events(required(root, "eventReferences")));
            canonical(encode(receipt), expectedHash, payload);
            return receipt;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private ObjectNode output(OutputCapture capture) {
        ObjectNode node = mapper.createObjectNode();
        optionalText(node, "inlineText", capture.inlineText());
        optionalText(node, "artifactRef",
                capture.artifactRef().map(ArtifactRef::value));
        node.put("truncated", capture.truncated());
        return node;
    }

    private OutputCapture output(JsonNode raw) {
        if (!(raw instanceof ObjectNode node)) {
            throw invalid();
        }
        fields(node, OUTPUT_FIELDS);
        JsonNode truncated = required(node, "truncated");
        if (!truncated.isBoolean()) {
            throw invalid();
        }
        return new OutputCapture(
                optionalText(node, "inlineText"),
                optionalText(node, "artifactRef").map(ArtifactRef::new),
                truncated.booleanValue());
    }

    private static List<ArtifactRef> artifacts(JsonNode raw) {
        if (!raw.isArray()) {
            throw invalid();
        }
        List<ArtifactRef> values = new ArrayList<>();
        raw.forEach(value -> values.add(new ArtifactRef(rawText(value))));
        return values;
    }

    private static List<EventId> events(JsonNode raw) {
        if (!raw.isArray()) {
            throw invalid();
        }
        List<EventId> values = new ArrayList<>();
        raw.forEach(value -> values.add(new EventId(rawText(value))));
        return values;
    }

    private ObjectNode root(
            int formatVersion, String expectedHash, String payload) {
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
        fields(root, FIELDS);
        if (!"execution-receipt".equals(text(root, "format"))) {
            throw invalid();
        }
        return root;
    }

    private EncodedPayload encode(ObjectNode root) {
        try {
            String json = mapper.writeValueAsString(root);
            return new EncodedPayload(
                    FORMAT_VERSION, sha256(json), json);
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static void optionalText(
            ObjectNode root, String name, Optional<String> value) {
        value.ifPresentOrElse(
                text -> root.put(name, text),
                () -> root.putNull(name));
    }

    private static void optionalInteger(
            ObjectNode root, String name, Optional<Integer> value) {
        value.ifPresentOrElse(
                number -> root.put(name, number),
                () -> root.putNull(name));
    }

    private static Optional<String> optionalText(
            JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return Optional.of(value.textValue());
    }

    private static Optional<Integer> optionalInteger(
            JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.canConvertToInt()) {
            throw invalid();
        }
        return Optional.of(value.intValue());
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String rawText(JsonNode value) {
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
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
                "receipt persistence payload is invalid");
    }

    record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
