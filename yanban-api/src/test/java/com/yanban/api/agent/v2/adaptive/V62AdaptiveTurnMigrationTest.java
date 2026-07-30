package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V62AdaptiveTurnMigrationTest {
    @Test
    void migrationFreezesStableStatusesAndRequestAuthority() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V62__create_agent_v2_adaptive_turns.sql"));
        assertTrue(sql.contains(
                "UNIQUE KEY uk_agent_v2_adaptive_request"));
        assertTrue(sql.contains(
                "'PLANNING','RUNNING','WAITING_CONFIRMATION','SUCCEEDED','FAILED'"));
        assertTrue(sql.contains(
                "REFERENCES agent_v2_turn_intakes(id)"));
    }
}
