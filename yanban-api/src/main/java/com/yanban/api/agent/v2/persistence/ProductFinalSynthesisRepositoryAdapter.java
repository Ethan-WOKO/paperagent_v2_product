package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceDiffEntry;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.FinalSynthesisRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProductFinalSynthesisRepositoryAdapter
        implements FinalSynthesisRepository {
    private final ProductFinalSynthesisJpaRepository rows;
    private final ObjectMapper mapper;

    public ProductFinalSynthesisRepositoryAdapter(
            ProductFinalSynthesisJpaRepository rows, ObjectMapper mapper) {
        this.rows = rows;
        this.mapper = mapper;
    }

    @Override
    public synchronized PersistenceResult<FinalSynthesis> append(FinalSynthesis synthesis) {
        if (synthesis == null || !validProvenance(synthesis)) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "finalSynthesis");
        }
        Optional<ProductFinalSynthesisEntity> existing =
                rows.findById(synthesis.planId().value());
        if (existing.isPresent()) {
            return classify(existing.orElseThrow(), synthesis);
        }
        String receiptJson = receiptJson(synthesis.receiptIds());
        String workspaceJson = synthesis.workspaceDiff()
                .map(this::workspaceJson).orElse(null);
        String canonical = canonical(synthesis, receiptJson, workspaceJson);
        try {
            rows.saveAndFlush(new ProductFinalSynthesisEntity(
                    synthesis.planId().value(), synthesis.id().value(),
                    synthesis.taskFrameId().value(),
                    synthesis.planRevisionId().value(),
                    synthesis.sourceProjectVersion()
                            .map(ProjectVersionRef::projectId).orElse(null),
                    synthesis.sourceProjectVersion()
                            .map(ProjectVersionRef::versionId).orElse(null),
                    workspaceJson, receiptJson,
                    synthesis.narrative(), synthesis.observedAt(),
                    sha256(canonical), Instant.now()));
            return PersistenceResult.applied(synthesis);
        } catch (DataIntegrityViolationException race) {
            return rows.findById(synthesis.planId().value())
                    .map(row -> classify(row, synthesis))
                    .orElseThrow(() -> race);
        }
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PersistenceResult<FinalSynthesis> find(PlanId planId) {
        if (planId == null) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        }
        return rows.findById(planId.value())
                .map(this::decode)
                .map(PersistenceResult::found)
                .orElseGet(() -> PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND, "planId"));
    }

    private PersistenceResult<FinalSynthesis> classify(
            ProductFinalSynthesisEntity row, FinalSynthesis candidate) {
        FinalSynthesis existing = decode(row);
        return existing.equals(candidate)
                ? PersistenceResult.replayed(existing)
                : PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "finalSynthesis.planId");
    }

    private FinalSynthesis decode(ProductFinalSynthesisEntity row) {
        List<ReceiptId> ids;
        try {
            List<String> values = mapper.readValue(
                    row.receiptIdsJson(), new TypeReference<>() {});
            ids = values.stream().map(ReceiptId::new).toList();
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException("stored final synthesis is invalid");
        }
        Optional<ProjectVersionRef> source = source(row);
        Optional<WorkspaceDiff> diff = workspace(row, source);
        FinalSynthesis value = new FinalSynthesis(
                new FinalSynthesisId(row.synthesisId()),
                new TaskFrameId(row.taskFrameId()),
                new PlanId(row.planId()),
                new PlanRevisionId(row.planRevisionId()),
                source, diff, ids,
                row.narrative(), row.observedAt());
        String canonical = canonical(
                value, row.receiptIdsJson(), row.workspaceDiffJson());
        boolean valid = sha256(canonical).equals(row.canonicalSha256());
        if (!valid && value.sourceProjectVersion().isEmpty()) {
            valid = sha256(legacyCanonical(value, row.receiptIdsJson()))
                    .equals(row.canonicalSha256());
        }
        if (!valid) {
            throw new IllegalStateException("stored final synthesis integrity failure");
        }
        return value;
    }

    private String receiptJson(List<ReceiptId> ids) {
        try {
            return mapper.writeValueAsString(
                    ids.stream().map(ReceiptId::value).toList());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("receipt ids are not encodable");
        }
    }

    private static String canonical(
            FinalSynthesis value, String receiptJson, String workspaceJson) {
        return "v2\n" + value.id().value() + "\n"
                + value.taskFrameId().value()
                + "\n" + value.planId().value() + "\n"
                + value.planRevisionId().value() + "\n"
                + value.sourceProjectVersion()
                        .map(ProjectVersionRef::projectId).orElse("")
                + "\n" + value.sourceProjectVersion()
                        .map(ProjectVersionRef::versionId).orElse("")
                + "\n" + (workspaceJson == null ? "" : workspaceJson)
                + "\n" + receiptJson
                + "\n" + value.narrative() + "\n" + value.observedAt();
    }

    private static String legacyCanonical(
            FinalSynthesis value, String receiptJson) {
        return value.id().value() + "\n" + value.taskFrameId().value()
                + "\n" + value.planId().value() + "\n"
                + value.planRevisionId().value() + "\n" + receiptJson
                + "\n" + value.narrative() + "\n" + value.observedAt();
    }

    private static boolean validProvenance(FinalSynthesis value) {
        if (value.sourceProjectVersion().isEmpty()) {
            return value.workspaceDiff().isEmpty();
        }
        return value.workspaceDiff().filter(diff ->
                diff.workspace().sourceProjectVersion().equals(
                        value.sourceProjectVersion().orElseThrow()))
                .isPresent();
    }

    private Optional<ProjectVersionRef> source(
            ProductFinalSynthesisEntity row) {
        boolean project = row.sourceProjectId() != null;
        boolean version = row.sourceProjectVersionId() != null;
        boolean diff = row.workspaceDiffJson() != null;
        if (project != version || project != diff) {
            throw new IllegalStateException(
                    "stored final synthesis integrity failure");
        }
        return project ? Optional.of(new ProjectVersionRef(
                row.sourceProjectId(), row.sourceProjectVersionId()))
                : Optional.empty();
    }

    private Optional<WorkspaceDiff> workspace(
            ProductFinalSynthesisEntity row,
            Optional<ProjectVersionRef> source) {
        if (row.workspaceDiffJson() == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(row.workspaceDiffJson());
            ProjectVersionRef version = source.orElseThrow();
            JsonNode workspace = node.path("workspace");
            ProjectVersionRef embedded = new ProjectVersionRef(
                    text(workspace.path("source"), "projectId"),
                    text(workspace.path("source"), "versionId"));
            if (!version.equals(embedded)) {
                throw new IllegalArgumentException();
            }
            List<WorkspaceDiffEntry> entries = new java.util.ArrayList<>();
            for (JsonNode entry : node.withArray("entries")) {
                Map<String, String> metadata = new LinkedHashMap<>();
                entry.path("metadata").fields().forEachRemaining(value ->
                        metadata.put(value.getKey(), value.getValue().asText()));
                entries.add(new WorkspaceDiffEntry(
                        DiffKind.valueOf(text(entry, "kind")),
                        new ProjectPath(text(entry, "path")),
                        optionalPath(entry, "targetPath"),
                        optionalHash(entry, "beforeHash"),
                        optionalHash(entry, "afterHash"),
                        metadata));
            }
            WorkspaceDiff value = new WorkspaceDiff(
                    new DiffId(text(node, "id")),
                    new WorkspaceRef(
                            new WorkspaceId(text(workspace, "id")),
                            embedded),
                    entries,
                    Instant.parse(text(node, "createdAt")));
            if (!workspaceJson(value).equals(row.workspaceDiffJson())) {
                throw new IllegalArgumentException();
            }
            return Optional.of(value);
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new IllegalStateException(
                    "stored final synthesis integrity failure");
        }
    }

    private String workspaceJson(WorkspaceDiff value) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("id", value.id().value());
            ObjectNode workspace = root.putObject("workspace");
            workspace.put("id", value.workspace().id().value());
            workspace.putObject("source")
                    .put("projectId",
                            value.workspace().sourceProjectVersion().projectId())
                    .put("versionId",
                            value.workspace().sourceProjectVersion().versionId());
            ArrayNode entries = root.putArray("entries");
            value.entries().forEach(entry -> {
                ObjectNode node = entries.addObject();
                node.put("kind", entry.kind().name());
                node.put("path", entry.path().value());
                entry.targetPath().ifPresent(path ->
                        node.put("targetPath", path.value()));
                entry.beforeHash().ifPresent(hash ->
                        hash(node.putObject("beforeHash"), hash));
                entry.afterHash().ifPresent(hash ->
                        hash(node.putObject("afterHash"), hash));
                ObjectNode metadata = node.putObject("metadata");
                entry.metadata().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(item -> metadata.put(
                                item.getKey(), item.getValue()));
            });
            root.put("createdAt", value.createdAt().toString());
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "workspace diff is not encodable");
        }
    }

    private static void hash(ObjectNode node, ContentHash value) {
        node.put("algorithm", value.algorithm());
        node.put("value", value.value());
    }

    private static Optional<ProjectPath> optionalPath(
            JsonNode node, String field) {
        return node.has(field)
                ? Optional.of(new ProjectPath(text(node, field)))
                : Optional.empty();
    }

    private static Optional<ContentHash> optionalHash(
            JsonNode node, String field) {
        if (!node.has(field)) {
            return Optional.empty();
        }
        JsonNode hash = node.path(field);
        return Optional.of(new ContentHash(
                text(hash, "algorithm"), text(hash, "value")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException();
        }
        return value.textValue();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
