package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V74ChainTaskSkillSnapshotMigrationTest {
    @Test
    void createsOneSourceInstructionBoundSnapshotPerTask() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database("v74")) {
            ChainMigrationTestSupport.migrateThrough(connection, 74);

            assertEquals(34, ChainMigrationTestSupport.chainTables(connection).size());
            assertTrue(ChainMigrationTestSupport.chainTables(connection).contains(
                    "AGENT_V2_CHAIN_TASK_SKILL_SNAPSHOTS"));
            assertTrue(ChainMigrationTestSupport.columns(connection,
                    "AGENT_V2_CHAIN_TASK_SKILL_SNAPSHOTS").containsAll(Set.of(
                    "TASK_ID", "SOURCE_INSTRUCTION_ID", "SELECTION_KIND",
                    "SKILL_ID", "PROMPT_SHA256", "PROMPT_BODY",
                    "ALLOWED_TOOLS_FORMAT_VERSION", "ALLOWED_TOOLS_SHA256",
                    "ALLOWED_TOOLS_JSON", "SNAPSHOT_SHA256", "CREATED_AT")));
            assertTrue(ChainMigrationTestSupport.constraints(connection,
                    "AGENT_V2_CHAIN_TASK_SKILL_SNAPSHOTS").containsAll(Set.of(
                    "FK_CHAIN_SKILL_SNAPSHOT_TASK",
                    "FK_CHAIN_SKILL_SNAPSHOT_SOURCE",
                    "CK_CHAIN_SKILL_SNAPSHOT_KIND",
                    "CK_CHAIN_SKILL_SNAPSHOT_SELECTION",
                    "CK_CHAIN_SKILL_SNAPSHOT_FORMAT",
                    "CK_CHAIN_SKILL_SNAPSHOT_HASHES")));
        }
    }

    @Test
    void rejectsSnapshotNotBoundToTheTasksSourceInstruction() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v74-source-binding")) {
            ChainMigrationTestSupport.migrateThrough(connection, 74);
            ChainMigrationTestSupport.seedFoundation(connection);

            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_task_skill_snapshots(
                          task_id,source_instruction_id,selection_kind,skill_id,
                          prompt_sha256,prompt_body,allowed_tools_format_version,
                          allowed_tools_sha256,allowed_tools_json,snapshot_sha256,
                          created_at)
                        VALUES ('task-1','instruction-missing','NONE',NULL,NULL,
                          NULL,1,REPEAT('0',64),'[]',REPEAT('0',64),
                          CURRENT_TIMESTAMP)
                        """));
            }
        }
    }
}
