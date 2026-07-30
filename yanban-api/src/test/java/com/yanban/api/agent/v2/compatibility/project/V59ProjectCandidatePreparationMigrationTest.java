package com.yanban.api.agent.v2.compatibility.project;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V59ProjectCandidatePreparationMigrationTest {
    @Test
    void migrationAddsOnlyDurablePreparedCandidateFacts() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V59__persist_v2_project_candidate_preparation.sql"));

        assertTrue(sql.contains("prepared_replacements_json"));
        assertTrue(sql.contains("prepared_replacements_sha256"));
        assertTrue(sql.contains("prepared_diff_fingerprint"));
        assertFalse(sql.contains("projects "));
        assertFalse(sql.contains("project_files"));
    }
}
