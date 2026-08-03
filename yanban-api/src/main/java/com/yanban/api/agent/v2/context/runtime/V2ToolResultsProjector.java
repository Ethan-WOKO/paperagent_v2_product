package com.yanban.api.agent.v2.context.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.Utf8ByteTokenCounter;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import com.yanban.api.agent.v2.context.V2ContextSectionStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class V2ToolResultsProjector {
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private final ObjectMapper json;
    private final Utf8ByteTokenCounter tokens = new Utf8ByteTokenCounter();

    public V2ToolResultsProjector(ObjectMapper json) { this.json = json; }

    public V2ContextSectionDraft project(List<Item> items, long tokenLimit) {
        if (tokenLimit < 0 || items == null) {
            throw new IllegalArgumentException("tool projection input is invalid");
        }
        ArrayNode projection = json.createArrayNode();
        ArrayNode refs = json.createArrayNode();
        items.stream().sorted(Comparator.comparing(Item::toolRunId,
                Comparator.nullsFirst(Comparator.naturalOrder()))).forEach(item -> {
            if (!STATUSES.contains(item.status())) {
                throw new IllegalArgumentException("tool result status is invalid");
            }
            String runId = safe(item.toolRunId(), "toolRunId", 128);
            String stepId = safe(item.stepId(), "stepId", 128);
            String toolName = safe(item.toolName(), "toolName", 128);
            String receiptId = optional(item.receiptId(), "receiptId", 128);
            String effectId = optional(item.effectIntentId(), "effectIntentId", 128);
            ArrayNode artifacts = json.createArrayNode();
            (item.artifactRefs() == null ? List.<String>of() : item.artifactRefs())
                    .forEach(value -> artifacts.add(safe(value, "artifactRef", 256)));
            ObjectNode value = projection.addObject();
            value.put("toolRunId", runId);
            value.put("stepId", stepId);
            value.put("toolName", toolName);
            value.put("status", item.status());
            if (receiptId == null) value.putNull("receiptId");
            else value.put("receiptId", receiptId);
            if (effectId == null) value.putNull("effectIntentId");
            else value.put("effectIntentId", effectId);
            value.set("artifactRefs", artifacts);
            ObjectNode ref = refs.addObject();
            ref.put("toolRunId", runId);
            ref.put("stepId", stepId);
            ref.put("status", item.status());
            if (receiptId != null) ref.put("receiptId", receiptId);
            if (effectId != null) ref.put("effectIntentId", effectId);
            ref.set("artifactRefs", artifacts.deepCopy());
        });
        String encoded = write(projection);
        long count = tokens.count(encoded);
        ContextSectionType type = ContextSectionType.TOOL_RESULTS;
        return new V2ContextSectionDraft(type, type.percentage(), tokenLimit,
                count, count, count > tokenLimit ? V2ContextSectionStatus.COMPACTION_REQUIRED
                : V2ContextSectionStatus.READY, write(refs), encoded,
                count > tokenLimit ? "TOOL_RESULTS_LIMIT_EXCEEDED" : null);
    }

    private String safe(String value, String field, int maximum) {
        return V2RuntimeProjectionSafety.required(value, field, maximum);
    }
    private String optional(String value, String field, int maximum) {
        return V2RuntimeProjectionSafety.optional(value, field, maximum);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public record Item(String toolRunId, String stepId, String toolName,
                       String status, String receiptId, String effectIntentId,
                       List<String> artifactRefs) { }
}
