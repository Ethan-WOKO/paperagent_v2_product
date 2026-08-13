package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V83ChainTaskOutcomeAuthorityMigrationTest {
    @Test
    void addsNullableExactTerminalAuthorityColumnsAndSafetyConstraints()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v83-task-outcome-authority")) {
            ChainMigrationTestSupport.migrateThrough(connection, 83);

            String table = "AGENT_V2_CHAIN_TASK_OUTCOMES";
            Set<String> columns = ChainMigrationTestSupport.columns(
                    connection, table);
            assertTrue(columns.containsAll(Set.of(
                    "FINALIZATION_READINESS_ID", "FINALIZATION_CHECK_ID",
                    "VALIDATION_REQUEST_DIGEST",
                    "VALIDATION_RECEIPT_DIGEST", "PUBLISH_REQUIREMENT",
                    "PUBLISH_REQUIREMENT_DIGEST")));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, table).containsAll(Set.of(
                    "FK_CHAIN_TASK_OUTCOME_READINESS",
                    "FK_CHAIN_TASK_OUTCOME_CHECK",
                    "CK_CHAIN_TASK_OUTCOME_TERMINAL_ROOT",
                    "CK_CHAIN_TASK_OUTCOME_TERMINAL_VALIDATION",
                    "CK_CHAIN_TASK_OUTCOME_TERMINAL_PUBLISH")));
            assertTrue(ChainMigrationTestSupport.constraints(connection,
                    "AGENT_V2_CHAIN_FINALIZATION_CHECKS").contains(
                    "UK_CHAIN_FINALIZATION_CHECK_TASK_IDENTITY"));
            for (String column : Set.of(
                    "FINALIZATION_READINESS_ID", "FINALIZATION_CHECK_ID",
                    "VALIDATION_REQUEST_DIGEST",
                    "VALIDATION_RECEIPT_DIGEST", "PUBLISH_REQUIREMENT",
                    "PUBLISH_REQUIREMENT_DIGEST")) {
                try (ResultSet result = connection.getMetaData().getColumns(
                        null, null, table, column)) {
                    assertTrue(result.next());
                    assertEquals("YES", result.getString("IS_NULLABLE"));
                }
            }
        }
    }
}
