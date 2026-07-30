package com.yanban.api.agent.v2.effect;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves only the frozen #95 Step-to-tool binding. */
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
        try {
            var root = json.readTree(documents.get(0));
            if (!root.isArray() || root.size() > 8) {
                return false;
            }
            int matches = 0;
            for (var item : root) {
                if (!item.isObject() || item.size() != 3
                        || !item.path("stepId").isTextual()
                        || !item.path("internalToolId").isTextual()) {
                    return false;
                }
                if (stepId.equals(item.path("stepId").textValue())
                        && toolId.equals(
                                item.path("internalToolId").textValue())) {
                    matches++;
                }
            }
            return matches == 1;
        } catch (Exception invalid) {
            return false;
        }
    }
}
