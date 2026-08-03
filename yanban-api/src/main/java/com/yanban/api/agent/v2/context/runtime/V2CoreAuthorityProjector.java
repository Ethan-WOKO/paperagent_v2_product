package com.yanban.api.agent.v2.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class V2CoreAuthorityProjector {
    private final ObjectMapper json;
    private final Utf8ByteTokenCounter tokens = new Utf8ByteTokenCounter();

    public V2CoreAuthorityProjector(ObjectMapper json) {
        this.json = json;
    }

    public V2ContextSectionDraft project(Input input, long tokenLimit) {
        if (input == null || tokenLimit < 0) {
            throw new IllegalArgumentException("core projection input is invalid");
        }
        String taskFrameId = V2RuntimeProjectionSafety.required(
                input.taskFrameId(), "taskFrameId", 128);
        String planId = V2RuntimeProjectionSafety.optional(
                input.planId(), "planId", 128);
        String projectVersion = V2RuntimeProjectionSafety.optional(
                input.projectVersion(), "projectVersion", 128);
        String permissionTier = V2RuntimeProjectionSafety.required(
                input.permissionTier(), "permissionTier", 64);
        ArrayNode constraints = json.createArrayNode();
        (input.constraints() == null ? List.<String>of() : input.constraints())
                .forEach(value -> constraints.add(V2RuntimeProjectionSafety.required(
                        value, "constraint", 1_000)));
        ObjectNode projection = json.createObjectNode();
        projection.put("taskFrameId", taskFrameId);
        if (planId == null) projection.putNull("planId");
        else projection.put("planId", planId);
        if (projectVersion == null) projection.putNull("projectVersion");
        else projection.put("projectVersion", projectVersion);
        projection.put("permissionTier", permissionTier);
        projection.set("constraints", constraints);
        ObjectNode refs = json.createObjectNode();
        refs.put("taskFrameId", taskFrameId);
        if (planId != null) refs.put("planId", planId);
        if (projectVersion != null) refs.put("projectVersion", projectVersion);
        ArrayNode authorityTuple = json.createArrayNode();
        (input.canonicalAuthorityTuple() == null ? List.<String>of()
                : input.canonicalAuthorityTuple()).forEach(value ->
                authorityTuple.add(V2RuntimeProjectionSafety.required(
                        value, "authorityTuple", 96)));
        if (authorityTuple.isEmpty()) {
            throw new IllegalArgumentException("authorityTuple is required");
        }
        refs.set("authorityTuple", authorityTuple);
        return section(ContextSectionType.CORE_AUTHORITY, tokenLimit,
                write(refs), write(projection));
    }

    private V2ContextSectionDraft section(ContextSectionType type, long limit,
                                           String refs, String projection) {
        long count = tokens.count(projection);
        return new V2ContextSectionDraft(type, type.percentage(), limit,
                count, count, count > limit
                ? V2ContextSectionStatus.COMPACTION_REQUIRED
                : V2ContextSectionStatus.READY,
                refs, projection, count > limit ? "CORE_LIMIT_EXCEEDED" : null);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public record Input(String taskFrameId, String planId,
                        String projectVersion, String permissionTier,
                        List<String> constraints,
                        List<String> canonicalAuthorityTuple) { }
}
