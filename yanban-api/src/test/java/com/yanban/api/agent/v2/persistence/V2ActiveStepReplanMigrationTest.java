package com.yanban.api.agent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class V2ActiveStepReplanMigrationTest {
    @Test
    void migrationKeepsEachMarkerAndOrdersPlanHistory() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/"
                        + "V54__create_agent_v2_active_step_replans.sql")) {
            String sql = new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains(
                    "PRIMARY KEY (supersession_event_id)"));
            assertTrue(sql.contains(
                    "UNIQUE (replan_event_id)"));
            assertTrue(sql.contains(
                    "(plan_id, source_event_sequence)"));
        }
    }
}
