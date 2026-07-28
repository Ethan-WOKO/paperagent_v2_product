package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V57ProjectAnalysisMigrationTest {
    @Test
    void mysqlAndH2MigrationsCarryDeliveryStepAndProjectProvenance() throws Exception {
        assertMigration("db/migration/V57__create_v2_project_analysis_deliveries.sql");
        assertMigration("db/migration-h2/V57__create_v2_project_analysis_deliveries.sql");
    }

    private static void assertMigration(String resource) throws Exception {
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            String sql = new String(
                    java.util.Objects.requireNonNull(stream).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(sql.contains("source_project_version_id"));
            assertTrue(sql.contains("workspace_diff_json"));
            assertTrue(sql.contains("agent_v2_project_analysis_deliveries"));
            assertTrue(sql.contains("agent_v2_project_analysis_steps"));
            assertTrue(sql.contains("argument_sha256"));
        }
    }
}
