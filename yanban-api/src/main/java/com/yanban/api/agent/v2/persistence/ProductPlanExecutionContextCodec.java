package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

@Component
public final class ProductPlanExecutionContextCodec {
    static final int FORMAT_VERSION = 1;
    private static final String CORRUPT =
            "Stored V2 Plan execution context payload is invalid";

    private final ObjectMapper json;

    public ProductPlanExecutionContextCodec(ObjectMapper json) {
        this.json = json.copy();
    }

    EncodedPayload encodeReservationRequest(
            PlanExecutionContextReservationRequest request) {
        ObjectNode root = root("reservation-request");
        root.put("planId", request.planId().value());
        root.put("leaseToken", request.leaseToken());
        root.put("fencingToken", request.fencingToken());
        root.put("expectedRevisionId", request.expectedRevisionId().value());
        root.put("expectedRevisionNumber", request.expectedRevisionNumber());
        root.put("expectedCheckpointVersion", request.expectedCheckpointVersion());
        root.put("expectedEventHeadSequence", request.expectedEventHeadSequence());
        root.set("materializationSpec", spec(request.materializationSpec()));
        return encode(root);
    }

    EncodedPayload encodeReservationResult(
            PersistedPlanExecutionContextReserved result) {
        ObjectNode root = root("reservation-result");
        root.put("planId", result.planId().value());
        root.set("materializationSpec", spec(result.materializationSpec()));
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        return encode(root);
    }

    EncodedPayload encodeConfirmationRequest(
            PlanExecutionContextConfirmationRequest request) {
        ObjectNode root = root("confirmation-request");
        root.put("planId", request.planId().value());
        root.put("leaseToken", request.leaseToken());
        root.put("fencingToken", request.fencingToken());
        root.set("materializationSpec", spec(request.materializationSpec()));
        root.set("sourceManifestFingerprint",
                hash(request.sourceManifestFingerprint()));
        return encode(root);
    }

    EncodedPayload encodeConfirmationResult(
            PersistedPlanExecutionContextConfirmed result) {
        ObjectNode root = root("confirmation-result");
        root.set("reservation", reservation(result.reservation()));
        root.put("leaseOwnerId", result.leaseOwnerId());
        root.put("fencingToken", result.fencingToken());
        root.set("sourceManifestFingerprint",
                hash(result.sourceManifestFingerprint()));
        return encode(root);
    }

    PlanExecutionContextReservationRequest decodeReservationRequest(
            int version, String digest, String payload) {
        JsonNode root = verified(version, digest, payload,
                "reservation-request",
                Set.of("format", "kind", "planId", "leaseToken",
                        "fencingToken", "expectedRevisionId",
                        "expectedRevisionNumber", "expectedCheckpointVersion",
                        "expectedEventHeadSequence", "materializationSpec"));
        try {
            var result = new PlanExecutionContextReservationRequest(
                    new PlanId(text(root, "planId")),
                    text(root, "leaseToken"),
                    number(root, "fencingToken"),
                    new PlanRevisionId(text(root, "expectedRevisionId")),
                    number(root, "expectedRevisionNumber"),
                    number(root, "expectedCheckpointVersion"),
                    number(root, "expectedEventHeadSequence"),
                    spec(required(root, "materializationSpec")));
            canonical(encodeReservationRequest(result), digest, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    PersistedPlanExecutionContextReserved decodeReservationResult(
            int version, String digest, String payload) {
        JsonNode root = verified(version, digest, payload,
                "reservation-result",
                Set.of("format", "kind", "planId", "materializationSpec",
                        "leaseOwnerId", "fencingToken"));
        try {
            var result = new PersistedPlanExecutionContextReserved(
                    new PlanId(text(root, "planId")),
                    spec(required(root, "materializationSpec")),
                    text(root, "leaseOwnerId"),
                    number(root, "fencingToken"));
            canonical(encodeReservationResult(result), digest, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    PlanExecutionContextConfirmationRequest decodeConfirmationRequest(
            int version, String digest, String payload) {
        JsonNode root = verified(version, digest, payload,
                "confirmation-request",
                Set.of("format", "kind", "planId", "leaseToken",
                        "fencingToken", "materializationSpec",
                        "sourceManifestFingerprint"));
        try {
            var result = new PlanExecutionContextConfirmationRequest(
                    new PlanId(text(root, "planId")),
                    text(root, "leaseToken"),
                    number(root, "fencingToken"),
                    spec(required(root, "materializationSpec")),
                    hash(required(root, "sourceManifestFingerprint")));
            canonical(encodeConfirmationRequest(result), digest, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    PersistedPlanExecutionContextConfirmed decodeConfirmationResult(
            int version, String digest, String payload) {
        JsonNode root = verified(version, digest, payload,
                "confirmation-result",
                Set.of("format", "kind", "reservation", "leaseOwnerId",
                        "fencingToken", "sourceManifestFingerprint"));
        try {
            var result = new PersistedPlanExecutionContextConfirmed(
                    reservation(required(root, "reservation")),
                    text(root, "leaseOwnerId"),
                    number(root, "fencingToken"),
                    hash(required(root, "sourceManifestFingerprint")));
            canonical(encodeConfirmationResult(result), digest, payload);
            return result;
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    private ObjectNode root(String kind) {
        ObjectNode root = json.createObjectNode();
        root.put("format", FORMAT_VERSION);
        root.put("kind", kind);
        return root;
    }

    private ObjectNode spec(WorkspaceMaterializationSpec value) {
        ObjectNode node = json.createObjectNode();
        node.put("workspaceId", value.workspaceId().value());
        node.put("sourceProjectId", value.sourceProjectVersion().projectId());
        node.put("sourceVersionId", value.sourceProjectVersion().versionId());
        node.put("maxFileBytes", value.limits().maxFileBytes());
        node.put("maxAggregateBytes", value.limits().maxAggregateBytes());
        node.put("maxFiles", value.limits().maxFiles());
        return node;
    }

    private WorkspaceMaterializationSpec spec(JsonNode node) {
        fields(node, Set.of("workspaceId", "sourceProjectId",
                "sourceVersionId", "maxFileBytes", "maxAggregateBytes",
                "maxFiles"));
        return new WorkspaceMaterializationSpec(
                new WorkspaceId(text(node, "workspaceId")),
                new ProjectVersionRef(
                        text(node, "sourceProjectId"),
                        text(node, "sourceVersionId")),
                new WorkspaceMaterializationLimits(
                        number(node, "maxFileBytes"),
                        number(node, "maxAggregateBytes"),
                        integer(node, "maxFiles")));
    }

    private ObjectNode hash(ContentHash value) {
        ObjectNode node = json.createObjectNode();
        node.put("algorithm", value.algorithm());
        node.put("value", value.value());
        return node;
    }

    private ContentHash hash(JsonNode node) {
        fields(node, Set.of("algorithm", "value"));
        return new ContentHash(text(node, "algorithm"), text(node, "value"));
    }

    private ObjectNode reservation(PersistedPlanExecutionContextReserved value) {
        ObjectNode node = json.createObjectNode();
        node.put("planId", value.planId().value());
        node.set("materializationSpec", spec(value.materializationSpec()));
        node.put("leaseOwnerId", value.leaseOwnerId());
        node.put("fencingToken", value.fencingToken());
        return node;
    }

    private PersistedPlanExecutionContextReserved reservation(JsonNode node) {
        fields(node, Set.of("planId", "materializationSpec",
                "leaseOwnerId", "fencingToken"));
        return new PersistedPlanExecutionContextReserved(
                new PlanId(text(node, "planId")),
                spec(required(node, "materializationSpec")),
                text(node, "leaseOwnerId"),
                number(node, "fencingToken"));
    }

    private EncodedPayload encode(ObjectNode root) {
        try {
            byte[] bytes = json.writeValueAsBytes(root);
            return new EncodedPayload(FORMAT_VERSION, sha256(bytes),
                    new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to encode V2 Plan execution context", exception);
        }
    }

    private JsonNode verified(
            int version, String digest, String payload, String kind,
            Set<String> expectedFields) {
        try {
            if (version != FORMAT_VERSION || digest == null || payload == null) {
                throw corrupt();
            }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(
                    sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                    digest.getBytes(StandardCharsets.US_ASCII))) {
                throw corrupt();
            }
            JsonNode root = json.readTree(bytes);
            fields(root, expectedFields);
            if (integer(root, "format") != FORMAT_VERSION
                    || !kind.equals(text(root, "kind"))) {
                throw corrupt();
            }
            return root;
        } catch (RuntimeException exception) {
            throw corrupt();
        } catch (Exception exception) {
            throw corrupt();
        }
    }

    private static void canonical(
            EncodedPayload canonical, String digest, String payload) {
        if (!canonical.sha256().equals(digest)
                || !canonical.json().equals(payload)) {
            throw corrupt();
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw corrupt();
        }
        var actual = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw corrupt();
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
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

    private static long number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
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
