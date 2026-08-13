package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V81ChainTypedValidationMigrationTest {
    @Test
    void createsBodyFreeTypedValidationAuthority() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v81-chain-typed-validation")) {
            ChainMigrationTestSupport.migrateThrough(connection, 81);

            assertEquals(42,
                    ChainMigrationTestSupport.chainTables(connection).size());
            String sets = "AGENT_V2_CHAIN_VALIDATION_SETS";
            String candidates =
                    "AGENT_V2_CHAIN_CANDIDATE_VALIDATION_ITEMS";
            String actions =
                    "AGENT_V2_CHAIN_ACTION_RECEIPT_VALIDATION_ITEMS";
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .containsAll(Set.of(sets, candidates, actions)));
            assertEquals(Set.of(
                            "VALIDATION_ID", "TASK_ID", "EVENT_ID",
                            "TASK_FRAME_ID", "PLAN_ID", "PLAN_REVISION_ID",
                            "PLAN_REVISION_NUMBER", "STEP_ID",
                            "ACTIVATION_EVENT_ID", "REQUEST_DIGEST",
                            "RECEIPT_SET_DIGEST", "CONCLUSION_DIGEST",
                            "CONCLUSION", "IDEMPOTENCY_KEY", "CREATED_AT"),
                    ChainMigrationTestSupport.columns(connection, sets));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, candidates).containsAll(Set.of(
                    "FK_CHAIN_CANDIDATE_VALIDATION_SET",
                    "FK_CHAIN_CANDIDATE_VALIDATION_CANDIDATE",
                    "FK_CHAIN_CANDIDATE_VALIDATION_CANDIDATE_ACTION",
                    "FK_CHAIN_CANDIDATE_VALIDATION_ACTION",
                    "FK_CHAIN_CANDIDATE_VALIDATION_RECEIPT")));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, actions).containsAll(Set.of(
                    "FK_CHAIN_ACTION_VALIDATION_SET",
                    "FK_CHAIN_ACTION_VALIDATION_ACTION",
                    "FK_CHAIN_ACTION_VALIDATION_RECEIPT")));
            assertFalse(ChainMigrationTestSupport.columns(connection,
                    candidates).contains("RECEIPT_JSON"));
            assertFalse(ChainMigrationTestSupport.columns(connection,
                    actions).contains("RECEIPT_JSON"));
        }
    }
}
