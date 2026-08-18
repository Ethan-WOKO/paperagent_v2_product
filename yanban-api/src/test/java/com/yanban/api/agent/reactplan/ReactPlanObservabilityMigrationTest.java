package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReactPlanObservabilityMigrationTest {
    @Test
    void v92AddsBoundedModelObservationFactsWithoutPromptColumns() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V92__add_reactplan_observability.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("provider_key", "model_name", "request_bytes", "response_bytes",
                "prompt_tokens", "completion_tokens", "replay_count", "error_code");
        assertThat(sql).doesNotContain("prompt_text", "message_json", "api_key", "stdout");
    }

    @Test
    void v93AddsDurableTaskLevelUsageSettlement() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V93__settle_reactplan_usage_per_task.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("usage_settled", "settled_prompt_tokens",
                "settled_completion_tokens", "update reactplan_task_checkpoints");
    }
}
