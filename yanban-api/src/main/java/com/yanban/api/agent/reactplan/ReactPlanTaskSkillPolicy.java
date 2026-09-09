package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks the frozen task Skill at the product boundary, not a mutable installation. */
@Service
public class ReactPlanTaskSkillPolicy {
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ObjectMapper json;

    public ReactPlanTaskSkillPolicy(ReactPlanTaskCheckpointRepository checkpoints, ObjectMapper json) {
        this.checkpoints = checkpoints;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Set<String> allowedTools(String taskId, long userId, String requestDigest) {
        var checkpoint = checkpoints.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("Task Skill authority unavailable"));
        if (checkpoint.userId() != userId || !checkpoint.requestDigest().equals(requestDigest)) {
            throw new IllegalStateException("Task Skill authority mismatch");
        }
        try {
            JsonNode authority = json.readTree(checkpoint.checkpointJson()).path("authority");
            if (!authority.isObject() || !ReactPlanCanonicalJson.digest(json, authority).equals(requestDigest)) {
                throw new IllegalStateException("Task Skill authority mismatch");
            }
            JsonNode skill = authority.get("skill");
            if (skill == null || skill.isNull()) return null;
            if (!skill.isObject() || !skill.path("allowedTools").isArray()) {
                throw new IllegalStateException("Task Skill authority invalid");
            }
            Set<String> result = new LinkedHashSet<>();
            for (JsonNode tool : skill.path("allowedTools")) {
                if (!tool.isTextual()) throw new IllegalStateException("Task Skill tool invalid");
                result.add(tool.asText());
            }
            return Set.copyOf(result);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Task Skill authority invalid");
        }
    }
}
