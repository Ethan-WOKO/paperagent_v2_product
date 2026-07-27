package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.springframework.stereotype.Component;

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

@Component
public final class ProductStepCompletionCodec {
    static final int FORMAT_VERSION = 1;
    private static final String REQUEST = "step-completion-request";
    private static final String RESULT = "step-completion-result";
    private static final String CORRUPT =
            "Stored V2 step completion payload is invalid";

    private final ObjectMapper json;
    private final ProductExecutionStartCodec executionCodec;

    public ProductStepCompletionCodec(
            ObjectMapper json, ProductExecutionStartCodec executionCodec) {
        this.json = json.copy();
        this.executionCodec = executionCodec;
    }

    EncodedPayload encodeRequest(StepCompletionRequest request) {
        ObjectNode root = carrier(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.completionEvent(), request.completedCheckpoint());
        root.put("kind", REQUEST);
        root.put("expectedRevisionId", request.expectedRevisionId().value());
        root.put("expectedRevisionNumber", request.expectedRevisionNumber());
        root.put("expectedCheckpointVersion",
                request.expectedCheckpointVersion());
        root.put("expectedEventHeadSequence",
                request.expectedEventHeadSequence());
        root.put("stepId", request.stepId().value());
        root.set("completionFact", factNode(request.completionFact()));
        root.set("completedRevision", revisionNode(request.completedRevision()));
        return encode(root);
    }

    EncodedPayload encodeResult(PersistedStepCompletion result) {
        ObjectNode root = carrier(
                result.planId(), "result-carrier", result.fencingToken(),
                result.completionEvent(),
                result.completedCheckpoint().checkpoint());
        root.put("kind", RESULT);
        root.remove("leaseToken");
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("stepId", result.stepId().value());
        root.set("completedRevision", revisionNode(result.completedRevision()));
        ((ObjectNode) root.get("startedCheckpoint"))
                .put("version", result.completedCheckpoint().version());
        return encode(root);
    }

    StepCompletionRequest decodeRequest(
            int version, String expectedHash, String payload) {
        ObjectNode root = verified(version, expectedHash, payload, REQUEST);
        try {
            ExecutionStartRequest carrier = decodeCarrier(root);
            StepCompletionRequest decoded = new StepCompletionRequest(
                    carrier.planId(), carrier.leaseToken(),
                    carrier.fencingToken(),
                    new PlanRevisionId(text(root, "expectedRevisionId")),
                    number(root, "expectedRevisionNumber"),
                    number(root, "expectedCheckpointVersion"),
                    number(root, "expectedEventHeadSequence"),
                    new PlanStepId(text(root, "stepId")),
                    fact(requiredNode(root, "completionFact")),
                    carrier.startEvent(),
                    revision(requiredNode(root, "completedRevision")),
                    carrier.startedCheckpoint());
            requireCanonical(
                    encodeRequest(decoded), expectedHash, payload);
            return decoded;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    PersistedStepCompletion decodeResult(
            int version, String expectedHash, String payload) {
        ObjectNode root = verified(version, expectedHash, payload, RESULT);
        try {
            ObjectNode withToken = root.deepCopy();
            withToken.put("leaseToken", "result-carrier");
            ExecutionStartRequest carrier = decodeCarrier(withToken);
            JsonNode checkpointNode = requiredNode(root, "startedCheckpoint");
            PersistedStepCompletion decoded = new PersistedStepCompletion(
                    carrier.planId(), new PlanStepId(text(root, "stepId")),
                    text(root, "leaseOwnerId"), carrier.fencingToken(),
                    carrier.startEvent(),
                    revision(requiredNode(root, "completedRevision")),
                    new VersionedCheckpoint(
                            number(checkpointNode, "version"),
                            carrier.startedCheckpoint()));
            requireCanonical(
                    encodeResult(decoded), expectedHash, payload);
            return decoded;
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    private ObjectNode carrier(
            PlanId planId, String leaseToken, long fencingToken,
            EventEnvelope event, Checkpoint checkpoint) {
        try {
            return (ObjectNode) json.readTree(executionCodec.encodeRequest(
                    new ExecutionStartRequest(planId, leaseToken, fencingToken,
                            event, checkpoint)).json());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 step completion", exception);
        }
    }

    private ExecutionStartRequest decodeCarrier(ObjectNode root) {
        ObjectNode carrier = json.createObjectNode();
        carrier.put("format", ProductExecutionStartCodec.FORMAT_VERSION);
        carrier.put("kind", "request");
        carrier.set("planId", requiredNode(root, "planId").deepCopy());
        carrier.set("leaseToken", requiredNode(root, "leaseToken").deepCopy());
        carrier.set("fencingToken",
                requiredNode(root, "fencingToken").deepCopy());
        carrier.set("startEvent",
                requiredNode(root, "startEvent").deepCopy());
        ObjectNode checkpoint =
                (ObjectNode) requiredNode(root, "startedCheckpoint").deepCopy();
        checkpoint.remove("version");
        carrier.set("startedCheckpoint", checkpoint);
        EncodedPayload encoded = encode(carrier);
        return executionCodec.decodeRequest(
                ProductExecutionStartCodec.FORMAT_VERSION,
                encoded.sha256(), encoded.json());
    }

    private ObjectNode revisionNode(PlanRevision value) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("taskFrameId", value.taskFrameId().value());
        node.put("number", value.number());
        value.parentRevisionId().ifPresentOrElse(
                parent -> node.put("parentRevisionId", parent.value()),
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

    private ObjectNode stepNode(PlanStep value) {
        ObjectNode node = json.createObjectNode();
        node.put("id", value.id().value());
        node.put("intent", value.intent());
        node.put("expectedOutcome", value.expectedOutcome());
        node.set("dependencies", strings(value.dependencies().stream()
                .map(PlanStepId::value).sorted().toList()));
        node.set("completionCriteria", strings(value.completionCriteria()));
        ObjectNode hints = json.createObjectNode();
        hints.put("maxAttempts", value.executionHints().maxAttempts());
        hints.put("maxDuration", value.executionHints().maxDuration().toString());
        node.set("executionHints", hints);
        return node;
    }

    private ObjectNode factNode(CompletionFact value) {
        ObjectNode node = json.createObjectNode();
        node.put("stepId", value.stepId().value());
        node.put("outcomeHash", value.outcomeHash());
        node.put("completedAt", value.completedAt().toString());
        node.set("receiptReferences", strings(value.receiptReferences().stream()
                .map(ReceiptId::value).toList()));
        return node;
    }

    private PlanRevision revision(JsonNode node) {
        List<PlanStep> steps = new ArrayList<>();
        requiredArray(node, "steps").forEach(value -> steps.add(step(value)));
        Map<PlanStepId, CompletionFact> facts = new LinkedHashMap<>();
        requiredArray(node, "completedFacts").forEach(value -> {
            CompletionFact fact = fact(value);
            facts.put(fact.stepId(), fact);
        });
        JsonNode parent = requiredNode(node, "parentRevisionId");
        return new PlanRevision(
                new PlanRevisionId(text(node, "id")),
                new TaskFrameId(text(node, "taskFrameId")),
                number(node, "number"),
                parent.isNull()
                        ? Optional.empty()
                        : Optional.of(new PlanRevisionId(parent.textValue())),
                text(node, "reason"),
                Instant.parse(text(node, "createdAt")),
                steps,
                facts);
    }

    private PlanStep step(JsonNode node) {
        return new PlanStep(
                new PlanStepId(text(node, "id")),
                text(node, "intent"),
                text(node, "expectedOutcome"),
                new LinkedHashSet<>(stringList(node, "dependencies").stream()
                        .map(PlanStepId::new).toList()),
                stringList(node, "completionCriteria"),
                new BoundedExecutionHints(
                        integer(requiredNode(node, "executionHints"), "maxAttempts"),
                        Duration.parse(text(requiredNode(node, "executionHints"), "maxDuration"))));
    }

    private CompletionFact fact(JsonNode node) {
        return new CompletionFact(
                new PlanStepId(text(node, "stepId")),
                text(node, "outcomeHash"),
                Instant.parse(text(node, "completedAt")),
                stringList(node, "receiptReferences").stream()
                        .map(ReceiptId::new).toList());
    }

    private ObjectNode verified(
            int version, String expectedHash, String payload, String kind) {
        if (version != FORMAT_VERSION
                || expectedHash == null || payload == null) {
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

    private EncodedPayload encode(ObjectNode root) {
        try {
            byte[] bytes = json.writeValueAsBytes(root);
            return new EncodedPayload(
                    FORMAT_VERSION, sha256(bytes),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 step completion", exception);
        }
    }

    private static void requireCanonical(
            EncodedPayload canonical, String hash, String payload) {
        if (!canonical.sha256().equals(hash)
                || !canonical.json().equals(payload)) {
            throw corrupt();
        }
    }

    private ArrayNode strings(List<String> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        requiredArray(node, field).forEach(value -> {
            if (!value.isTextual()) {
                throw corrupt();
            }
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }

    private static JsonNode requiredNode(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field)) {
            throw corrupt();
        }
        return node.get(field);
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isArray()) {
            throw corrupt();
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = requiredNode(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
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

    public record EncodedPayload(int formatVersion, String sha256, String json) {
    }
}
