package com.yanban.api.agent.v2.context.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class V2DefaultSectionCompactor implements V2SectionCompactor {
    private final ObjectMapper json;
    private final Utf8ByteTokenCounter tokens = new Utf8ByteTokenCounter();

    public V2DefaultSectionCompactor(ObjectMapper json) { this.json = json; }

    @Override
    public V2SectionCompactionResult compact(V2ContextSectionDraft section) {
        if (section == null) throw new IllegalArgumentException("section is required");
        long target = Math.multiplyExact(section.tokenLimit(), 70L) / 100L;
        long before = tokens.count(section.projectionJson());
        String oldDigest = sha256(section.projectionJson());
        if (section.type() == ContextSectionType.CORE_AUTHORITY) {
            return failed(section, target, before, oldDigest,
                    "CORE_COMPACTION_FORBIDDEN");
        }
        if (section.type() == ContextSectionType.CONVERSATION_SUMMARY) {
            return failed(section, target, before, oldDigest,
                    "SEMANTIC_COMPACTOR_UNAVAILABLE");
        }
        if (section.type() == ContextSectionType.STEP_STATE) {
            return failed(section, target, before, oldDigest,
                    "STEP_AUTHORITY_CANNOT_BE_REDUCED");
        }
        if (before <= target) {
            return new V2SectionCompactionResult(true, target, before, before,
                    oldDigest, oldDigest, section.sourceRefsJson(), "[]",
                    ready(section, section.sourceRefsJson(),
                            section.projectionJson(), before, before),
                    "ALREADY_WITHIN_TARGET");
        }
        JsonNode projection = read(section.projectionJson());
        if (!projection.isArray()) {
            return failed(section, target, before, oldDigest,
                    "UNSUPPORTED_PROJECTION_SHAPE");
        }
        ArrayNode keptProjection = (ArrayNode) projection.deepCopy();
        JsonNode originalRefs = read(section.sourceRefsJson());
        ArrayNode keptRefs = referenceArray(originalRefs);
        ArrayNode removedRefs = originalRefs.isObject()
                && originalRefs.path("evicted").isArray()
                ? (ArrayNode) originalRefs.path("evicted").deepCopy()
                : json.createArrayNode();
        boolean removeOldest = section.type() == ContextSectionType.RECENT_CONVERSATION
                || section.type() == ContextSectionType.TOOL_RESULTS;
        while (!keptProjection.isEmpty()
                && tokens.count(write(keptProjection)) > target) {
            int index = removeOldest ? 0 : keptProjection.size() - 1;
            keptProjection.remove(index);
            if (index < keptRefs.size()) removedRefs.add(keptRefs.remove(index));
        }
        String compactedProjection = write(keptProjection);
        long after = tokens.count(compactedProjection);
        ObjectNode sourceRefs = json.createObjectNode();
        sourceRefs.set("kept", keptRefs);
        sourceRefs.set("removed", removedRefs);
        String refsJson = write(sourceRefs);
        V2ContextSectionDraft compacted = ready(section, refsJson,
                compactedProjection, before, after);
        return new V2SectionCompactionResult(after <= target, target,
                before, after, oldDigest, sha256(compactedProjection),
                write(keptRefs), write(removedRefs), compacted,
                after <= target ? "COMPACTED" : "TARGET_NOT_REACHED");
    }

    private ArrayNode referenceArray(JsonNode refs) {
        if (refs.isArray()) return (ArrayNode) refs.deepCopy();
        if (refs.isObject() && refs.path("selected").isArray()) {
            return (ArrayNode) refs.path("selected").deepCopy();
        }
        return json.createArrayNode();
    }

    private V2SectionCompactionResult failed(V2ContextSectionDraft section,
                                              long target, long before,
                                              String digest, String code) {
        return new V2SectionCompactionResult(false, target, before, before,
                digest, digest, section.sourceRefsJson(), "[]", section, code);
    }

    private V2ContextSectionDraft ready(V2ContextSectionDraft original,
                                        String refs, String projection,
                                        long before, long after) {
        return new V2ContextSectionDraft(original.type(),
                original.fixedPercentage(), original.tokenLimit(), before, after,
                V2ContextSectionStatus.READY, refs, projection, null);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception failure) { throw new IllegalArgumentException("invalid section JSON", failure); }
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
