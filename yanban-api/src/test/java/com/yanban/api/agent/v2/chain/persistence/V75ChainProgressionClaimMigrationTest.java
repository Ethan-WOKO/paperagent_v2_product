package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V75ChainProgressionClaimMigrationTest {
    @Test
    void createsAppendOnlyTaskClaimGenerationsAndScanIndex()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v75-progression-claim")) {
            ChainMigrationTestSupport.migrateThrough(connection, 75);

            assertEquals(35,
                    ChainMigrationTestSupport.chainTables(connection).size());
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .contains("AGENT_V2_CHAIN_PROGRESSION_CLAIMS"));
            assertTrue(ChainMigrationTestSupport.columns(connection,
                    "AGENT_V2_CHAIN_PROGRESSION_CLAIMS").containsAll(Set.of(
                    "TASK_ID", "FENCE", "OWNER_ID", "CLAIM_TOKEN",
                    "AUTHORITY_EVENT_CUT", "ACQUIRED_AT", "EXPIRES_AT",
                    "RELEASED_AT")));
            assertTrue(ChainMigrationTestSupport.constraints(connection,
                    "AGENT_V2_CHAIN_PROGRESSION_CLAIMS").containsAll(Set.of(
                    "UK_CHAIN_PROGRESSION_CLAIM_TOKEN",
                    "FK_CHAIN_PROGRESSION_CLAIM_TASK",
                    "CK_CHAIN_PROGRESSION_CLAIM_FENCE",
                    "CK_CHAIN_PROGRESSION_CLAIM_OWNER",
                    "CK_CHAIN_PROGRESSION_CLAIM_TOKEN",
                    "CK_CHAIN_PROGRESSION_CLAIM_CUT",
                    "CK_CHAIN_PROGRESSION_CLAIM_EXPIRY",
                    "CK_CHAIN_PROGRESSION_CLAIM_RELEASE")));
            assertTrue(ChainMigrationTestSupport.indexes(connection,
                    "AGENT_V2_CHAIN_PROGRESSION_CLAIMS").contains(
                    "IDX_CHAIN_PROGRESSION_CLAIM_TASK_FENCE"));
            assertTrue(ChainMigrationTestSupport.indexes(connection,
                    "AGENT_V2_CHAIN_COMMANDS").contains(
                    "IDX_CHAIN_COMMAND_PROGRESSION_SCAN"));
        }
    }

    @Test
    void rejectsInvalidGenerationAndDuplicateGlobalToken() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v75-progression-constraints")) {
            ChainMigrationTestSupport.migrateThrough(connection, 75);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedSecondFoundation(connection);

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_v2_chain_progression_claims(
                          task_id,fence,owner_id,claim_token,
                          authority_event_cut,acquired_at,expires_at)
                        VALUES ('task-1',1,'node-a','token-global',1,
                          TIMESTAMP '2026-08-08 08:00:00.000001',
                          TIMESTAMP '2026-08-08 08:01:00.000001')
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_progression_claims(
                          task_id,fence,owner_id,claim_token,
                          authority_event_cut,acquired_at,expires_at)
                        VALUES ('task-2',1,'node-b','token-global',1,
                          TIMESTAMP '2026-08-08 08:00:00.000001',
                          TIMESTAMP '2026-08-08 08:01:00.000001')
                        """));
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_progression_claims(
                          task_id,fence,owner_id,claim_token,
                          authority_event_cut,acquired_at,expires_at)
                        VALUES ('task-2',0,'node-b','token-invalid',1,
                          TIMESTAMP '2026-08-08 08:00:00.000001',
                          TIMESTAMP '2026-08-08 08:01:00.000001')
                        """));
            }
        }
    }
}
