package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V82ChainValidationBundleMigrationTest {
    @Test
    void createsBodyFreePlanValidationBundleAuthority() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v82-chain-validation-bundle")) {
            ChainMigrationTestSupport.migrateThrough(connection, 82);

            assertEquals(44,
                    ChainMigrationTestSupport.chainTables(connection).size());
            String bundles = "AGENT_V2_CHAIN_VALIDATION_BUNDLES";
            String members = "AGENT_V2_CHAIN_VALIDATION_BUNDLE_SETS";
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .containsAll(Set.of(bundles, members)));
            assertEquals(Set.of(
                            "VALIDATION_BUNDLE_ID", "TASK_ID", "EVENT_ID",
                            "TASK_FRAME_ID", "PLAN_ID", "PLAN_REVISION_ID",
                            "PLAN_REVISION_NUMBER", "INSTRUCTION_ID",
                            "FINAL_STEP_ID", "REQUEST_DIGEST",
                            "RECEIPT_SET_DIGEST", "CONCLUSION_DIGEST",
                            "CONCLUSION", "IDEMPOTENCY_KEY", "CREATED_AT"),
                    ChainMigrationTestSupport.columns(connection, bundles));
            assertEquals(Set.of(
                            "VALIDATION_BUNDLE_ID", "TASK_ID", "STEP_ID",
                            "ACTIVATION_EVENT_ID", "VALIDATION_ID",
                            "VALIDATION_REQUEST_DIGEST",
                            "VALIDATION_RECEIPT_SET_DIGEST",
                            "VALIDATION_CONCLUSION_DIGEST"),
                    ChainMigrationTestSupport.columns(connection, members));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, bundles).containsAll(Set.of(
                    "FK_CHAIN_VALIDATION_BUNDLE_TASK",
                    "FK_CHAIN_VALIDATION_BUNDLE_EVENT")));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, members).containsAll(Set.of(
                    "FK_CHAIN_VALIDATION_BUNDLE_SET_BUNDLE",
                    "FK_CHAIN_VALIDATION_BUNDLE_SET_VALIDATION")));
            for (String table : Set.of(bundles, members)) {
                Set<String> columns = ChainMigrationTestSupport.columns(
                        connection, table);
                assertFalse(columns.contains("RECEIPT_JSON"));
                assertFalse(columns.contains("STDOUT"));
                assertFalse(columns.contains("STDERR"));
            }
        }
    }
}
