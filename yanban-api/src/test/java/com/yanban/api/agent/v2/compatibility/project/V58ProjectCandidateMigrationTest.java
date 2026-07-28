package com.yanban.api.agent.v2.compatibility.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V58ProjectCandidateMigrationTest {
    @Test
    void migrationOwnsIndependentDeliveryAndStepAuthorityTables() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V58__create_v2_project_candidate_deliveries.sql"));
        assertTrue(sql.contains("CREATE TABLE agent_v2_project_candidate_deliveries"));
        assertTrue(sql.contains("CREATE TABLE agent_v2_project_candidate_steps"));
        assertTrue(sql.contains("UNIQUE (artifact_id)"));
        assertTrue(sql.contains("FOREIGN KEY (plan_id)"));
        assertFalse(sql.contains("agent_plans"));
        assertFalse(sql.contains("candidate_sandbox_validations"));
    }
}
