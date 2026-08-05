package com.yanban.api.agent.v2.effect.project;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V69NaturalCandidateMigrationTest {
    @Test
    void migrationReplacesPlanUniquenessWithLatestPlanIndex()
            throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V69__allow_multiple_natural_candidate_steps.sql"));

        assertTrue(sql.contains(
                "DROP INDEX uk_agent_v2_natural_candidate_plan"));
        assertTrue(sql.contains(
                "idx_agent_v2_natural_candidate_plan_latest"));
    }
}
