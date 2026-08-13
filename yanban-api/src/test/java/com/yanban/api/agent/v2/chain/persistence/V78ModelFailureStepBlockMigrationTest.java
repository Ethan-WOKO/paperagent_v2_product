package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V78ModelFailureStepBlockMigrationTest {
    @Test
    void createsOnlyTheExecutorModelFailureStepBlockAuthority()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v78-model-failure-step-block")) {
            ChainMigrationTestSupport.migrateThrough(connection, 78);

            assertEquals(38,
                    ChainMigrationTestSupport.chainTables(connection).size());
            String table = "AGENT_V2_CHAIN_MODEL_FAILURE_STEP_BLOCKS";
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .contains(table));
            assertEquals(Set.of(
                            "STEP_BLOCK_ID", "TASK_ID", "EVENT_ID",
                            "INVOCATION_ID", "CONTEXT_REVISION_ID",
                            "INSTRUCTION_ID", "TASK_FRAME_ID", "PLAN_ID",
                            "PLAN_REVISION_ID", "PLAN_REVISION_NUMBER",
                            "STEP_ID", "ACTIVATION_EVENT_ID",
                            "LAST_PROVIDER_ATTEMPT_REF", "FAILURE_CATEGORY",
                            "FAILURE_CODE", "VERSION_FENCE_SHA256",
                            "CREATED_AT"),
                    ChainMigrationTestSupport.columns(connection, table));
            assertTrue(ChainMigrationTestSupport.constraints(connection, table)
                    .containsAll(Set.of(
                            "UK_CHAIN_MODEL_FAILURE_STEP_BLOCK_INVOCATION",
                            "UK_CHAIN_MODEL_FAILURE_STEP_BLOCK_EVENT",
                            "FK_CHAIN_MODEL_FAILURE_STEP_BLOCK_TASK",
                            "FK_CHAIN_MODEL_FAILURE_STEP_BLOCK_INVOCATION",
                            "FK_CHAIN_MODEL_FAILURE_STEP_BLOCK_CONTEXT",
                            "FK_CHAIN_MODEL_FAILURE_STEP_BLOCK_EVENT",
                            "FK_CHAIN_MODEL_FAILURE_STEP_BLOCK_INSTRUCTION",
                            "CK_CHAIN_MODEL_FAILURE_STEP_BLOCK_PLAN_REVISION",
                            "CK_CHAIN_MODEL_FAILURE_STEP_BLOCK_KIND")));
        }
    }
}
