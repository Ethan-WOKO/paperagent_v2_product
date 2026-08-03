package com.yanban.api.agent.v2.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentContextSnapshot;
import com.yanban.core.agent.AgentContextSnapshotSection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class V2ContextRevisionCodec {
    private static final int MAX_SOURCE_REFS_BYTES = 65_536;
    private static final long MAX_PROJECTION_BYTES = 1_000_000L;
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "apikey", "api_key", "password", "secret", "environment",
            "env", "hostpath", "host_path", "rawproviderresponse",
            "raw_provider_response", "reasoning", "userfilebody",
            "user_file_body", "rawtooloutput", "raw_tool_output");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/]|file://|/(?:home|users?|var|etc|opt|tmp|workspace|mnt|root|data)(?:/|$))");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(?:bearer\\s+[a-z0-9._~+/-]{8,}|sk-[a-z0-9_-]{8,}|"
                    + "(?:api[_ -]?key|password|secret|token)\\s*[:=]\\s*\\S+)");

    private final ObjectMapper json;

    public V2ContextRevisionCodec(ObjectMapper objectMapper) {
        this.json = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    EncodedRevision encode(V2ContextRevisionDraft draft) {
        List<V2ContextSectionDraft> ordered = draft.sections().stream()
                .sorted(Comparator.comparingInt(value -> value.type().ordinal()))
                .toList();
        ArrayNode sectionManifest = json.createArrayNode();
        List<EncodedSection> sections = new ArrayList<>();
        int ordinal = 0;
        for (V2ContextSectionDraft section : ordered) {
            String sourceRefs = canonicalJson(
                    section.sourceRefsJson(), "sourceRefsJson",
                    MAX_SOURCE_REFS_BYTES);
            String projection = canonicalJson(
                    section.projectionJson(), "projectionJson",
                    projectionLimit(section));
            String projectionDigest = sha256(projection);
            ObjectNode node = json.createObjectNode();
            node.put("ordinal", ordinal);
            node.put("type", section.type().name());
            node.put("fixedPercentage", section.fixedPercentage());
            node.put("tokenLimit", section.tokenLimit());
            node.put("tokensBefore", section.tokensBefore());
            node.put("tokensAfter", section.tokensAfter());
            node.put("status", section.status().name());
            node.set("sourceRefs", read(sourceRefs));
            node.set("projection", read(projection));
            node.put("projectionDigest", projectionDigest);
            if (section.compactionReason() != null) {
                node.put("compactionReason", section.compactionReason());
            }
            sectionManifest.add(node);
            sections.add(new EncodedSection(
                    ordinal++, section, sourceRefs, projection,
                    projectionDigest));
        }
        ObjectNode root = json.createObjectNode();
        root.put("format", 1);
        root.put("userId", draft.userId());
        root.put("sessionId", draft.sessionId());
        root.put("turnId", draft.turnId());
        root.put("revisionNumber", draft.revisionNumber());
        if (draft.parentSnapshotId() == null) root.putNull("parentSnapshotId");
        else root.put("parentSnapshotId", draft.parentSnapshotId());
        if (draft.parentDigest() == null) root.putNull("parentDigest");
        else root.put("parentDigest", draft.parentDigest());
        root.put("stage", draft.stage().name());
        root.put("stableStageKey", draft.stableStageKey());
        root.put("status", draft.status().name());
        root.put("modelProvider", draft.modelProvider());
        root.put("model", draft.model());
        root.put("contextWindowTokens", draft.contextWindowTokens());
        root.put("maxOutputTokens", draft.maxOutputTokens());
        root.put("tokenCounterVersion", draft.tokenCounterVersion());
        root.put("profileVersion", draft.profileVersion());
        root.put("totalTokens", draft.totalTokens());
        root.put("outputReserveTokens", draft.outputReserveTokens());
        root.set("sections", sectionManifest);
        String canonical = write(root);
        return new EncodedRevision(canonical, sha256(canonical), sections);
    }

    V2ContextRevisionSnapshot decode(
            AgentContextSnapshot header,
            List<AgentContextSnapshotSection> storedSections,
            V2ContextRevisionOutcome outcome) {
        List<V2ContextSectionDraft> sections = storedSections.stream()
                .sorted(Comparator.comparingInt(AgentContextSnapshotSection::getSectionOrdinal))
                .map(value -> {
                    String canonicalProjection = canonicalJson(
                            value.getProjectionJson(), "projectionJson",
                            projectionLimit(value.getTokenLimit()));
                    if (!sha256(canonicalProjection).equals(value.getProjectionDigest())) {
                        throw new IllegalStateException("context section digest is invalid");
                    }
                    return new V2ContextSectionDraft(
                            ContextSectionType.valueOf(value.getSectionType()),
                            value.getFixedPercentage(), value.getTokenLimit(),
                            value.getTokensBefore(), value.getTokensAfter(),
                            V2ContextSectionStatus.valueOf(value.getSectionStatus()),
                            value.getSourceRefsJson(), canonicalProjection,
                            value.getCompactionReason());
                }).toList();
        V2ContextRevisionDraft draft = new V2ContextRevisionDraft(
                header.getUserId(), header.getSessionId(), header.getTurnId(),
                header.getRevisionNumber(), header.getParentSnapshotId(),
                header.getParentDigest(),
                V2ContextStage.valueOf(header.getContextStage()),
                header.getStableStageKey(),
                V2ContextRevisionStatus.valueOf(header.getRevisionStatus()),
                header.getModelProviderSnapshot(), header.getModelSnapshot(),
                header.getContextWindowTokens(), header.getMaxOutputTokens(),
                header.getTokenCounterVersion(), header.getProfileVersion(),
                header.getTotalTokens(), header.getOutputReserveTokens(), sections);
        EncodedRevision encoded = encode(draft);
        if (!encoded.digest().equals(header.getContextDigest())) {
            throw new IllegalStateException("context revision digest is invalid");
        }
        return new V2ContextRevisionSnapshot(
                header.getId(), outcome, draft, encoded.canonicalJson(),
                encoded.digest(), encoded.sections().stream()
                        .map(EncodedSection::projectionDigest).toList());
    }

    private String canonicalJson(String value, String field, long maximumBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        JsonNode node = read(value);
        inspectSafe(node);
        String canonical = write(canonical(node));
        if (canonical.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return canonical;
    }

    private static long projectionLimit(V2ContextSectionDraft section) {
        return projectionLimit(section.tokenLimit());
    }

    private static long projectionLimit(long tokenLimit) {
        long derived;
        try {
            derived = Math.multiplyExact(tokenLimit, 4L);
        } catch (ArithmeticException overflow) {
            derived = Long.MAX_VALUE;
        }
        return Math.min(MAX_PROJECTION_BYTES,
                Math.max(MAX_SOURCE_REFS_BYTES, derived));
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new IllegalArgumentException("context JSON is invalid", failure);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, canonical(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = json.createArrayNode();
            node.forEach(value -> array.add(canonical(value)));
            return array;
        }
        return node.deepCopy();
    }

    private void inspectSafe(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().replace("-", "_")
                        .toLowerCase(Locale.ROOT);
                if (FORBIDDEN_FIELDS.contains(normalized)) {
                    throw new IllegalArgumentException("unsafe context projection field");
                }
                inspectSafe(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::inspectSafe);
        } else if (node.isTextual()) {
            String value = node.textValue();
            if (ABSOLUTE_PATH.matcher(value).find()
                    || SECRET_VALUE.matcher(value).find()) {
                throw new IllegalArgumentException("unsafe context projection value");
            }
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (Exception impossible) {
            throw new IllegalStateException("context canonical encoding failed", impossible);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record EncodedRevision(
            String canonicalJson, String digest,
            List<EncodedSection> sections) { }

    record EncodedSection(
            int ordinal, V2ContextSectionDraft draft,
            String sourceRefsJson, String projectionJson,
            String projectionDigest) { }
}
