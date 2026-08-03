package com.yanban.api.agent.v2.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class V2StepStateProjector {
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "ACTIVE", "WAITING_CONFIRMATION",
            "COMPLETED", "FAILED", "CANCELLED");
    private final ObjectMapper json;
    private final Utf8ByteTokenCounter tokens = new Utf8ByteTokenCounter();

    public V2StepStateProjector(ObjectMapper json) { this.json = json; }

    public V2ContextSectionDraft project(Input input, long tokenLimit) {
        if (input == null || tokenLimit < 0 || !STATUSES.contains(input.status())) {
            throw new IllegalArgumentException("step projection input is invalid");
        }
        String planId = V2RuntimeProjectionSafety.required(input.planId(), "planId", 128);
        String stepId = V2RuntimeProjectionSafety.required(input.stepId(), "stepId", 128);
        String stepKey = V2RuntimeProjectionSafety.required(input.stepKey(), "stepKey", 128);
        ArrayNode dependencies = safeRefs(input.dependencyStepIds(), "dependencyStepId");
        ArrayNode results = safeRefs(input.acceptedResultRefs(), "acceptedResultRef");
        ArrayNode artifacts = safeRefs(input.candidateArtifactRefs(), "candidateArtifactRef");
        ObjectNode projection = json.createObjectNode();
        projection.put("planId", planId);
        projection.put("stepId", stepId);
        projection.put("stepKey", stepKey);
        projection.put("status", input.status());
        projection.set("dependencyStepIds", dependencies);
        projection.set("acceptedResultRefs", results);
        projection.set("candidateArtifactRefs", artifacts);
        ObjectNode refs = json.createObjectNode();
        refs.put("planId", planId);
        refs.put("stepId", stepId);
        refs.put("stepKey", stepKey);
        refs.set("acceptedResultRefs", results.deepCopy());
        refs.set("candidateArtifactRefs", artifacts.deepCopy());
        return section(tokenLimit, write(refs), write(projection));
    }

    private ArrayNode safeRefs(List<String> values, String field) {
        ArrayNode result = json.createArrayNode();
        (values == null ? List.<String>of() : values).forEach(value ->
                result.add(V2RuntimeProjectionSafety.required(value, field, 256)));
        return result;
    }

    private V2ContextSectionDraft section(long limit, String refs, String projection) {
        long count = tokens.count(projection);
        ContextSectionType type = ContextSectionType.STEP_STATE;
        return new V2ContextSectionDraft(type, type.percentage(), limit,
                count, count, count > limit ? V2ContextSectionStatus.COMPACTION_REQUIRED
                : V2ContextSectionStatus.READY, refs, projection,
                count > limit ? "STEP_STATE_LIMIT_EXCEEDED" : null);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public record Input(String planId, String stepId, String stepKey,
                        String status, List<String> dependencyStepIds,
                        List<String> acceptedResultRefs,
                        List<String> candidateArtifactRefs) { }
}
