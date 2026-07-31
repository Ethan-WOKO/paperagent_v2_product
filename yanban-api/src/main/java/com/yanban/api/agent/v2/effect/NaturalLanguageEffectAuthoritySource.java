package com.yanban.api.agent.v2.effect;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves request ownership and the bounded V2 tool catalog.
 *
 * <p>The model selects a tool at runtime. This source does not turn the
 * planner's capability hint into a per-Step allow rule; active Step, Plan,
 * lease, ProjectVersion and ToolCall authority remain mechanically checked by
 * the effect composers.
 */
@Component
public class NaturalLanguageEffectAuthoritySource {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public NaturalLanguageEffectAuthoritySource(
            JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public boolean authorizes(
            Long userId, Long turnId, String planId,
            String stepId, String toolId) {
        List<String> documents = jdbc.queryForList("""
                select capability_bindings_json
                from agent_v2_turn_intakes
                where user_id = ? and turn_id = ? and plan_id = ?
                  and status = 'PERSISTENT'
                """, String.class, userId, turnId, planId);
        if (documents.size() != 1 || documents.get(0) == null) {
            return false;
        }
        if (stepId == null || stepId.isBlank()
                || !java.util.Set.of(
                        "literature.search", "project.read",
                        "project.search", "project.candidate.compose",
                        "sandbox.execute").contains(toolId)) {
            return false;
        }
        try {
            var root = json.readTree(documents.get(0));
            if (!root.isArray() || root.size() > 8) {
                return false;
            }
            for (var item : root) {
                if (!item.isObject() || item.size() != 3
                        || !item.path("stepId").isTextual()
                        || !item.path("internalToolId").isTextual()) {
                    return false;
                }
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }
}
