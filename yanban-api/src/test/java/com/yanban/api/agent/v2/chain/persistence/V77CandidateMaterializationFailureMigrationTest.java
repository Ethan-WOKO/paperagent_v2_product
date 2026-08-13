package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V77CandidateMaterializationFailureMigrationTest {
    @Test
    void createsOnlyTheTypedPerActionCandidateFailureAuthority()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v77-candidate-materialization-failure")) {
            ChainMigrationTestSupport.migrateThrough(connection, 77);

            assertEquals(37,
                    ChainMigrationTestSupport.chainTables(connection).size());
            String table =
                    "AGENT_V2_CHAIN_CANDIDATE_MATERIALIZATION_FAILURES";
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .contains(table));
            assertEquals(Set.of(
                            "CANDIDATE_FAILURE_ID", "TASK_ID", "EVENT_ID",
                            "ACTION_ID", "WORKSPACE_ID", "BASE_CANDIDATE_KEY",
                            "MUTATION_AUTHORITY_TYPE",
                            "MUTATION_AUTHORITY_REF", "VERSION_FENCE_SHA256",
                            "ERROR_CODE", "CREATED_AT"),
                    ChainMigrationTestSupport.columns(connection, table));
            assertTrue(ChainMigrationTestSupport.constraints(connection, table)
                    .containsAll(Set.of(
                            "UK_CHAIN_CANDIDATE_FAILURE_TASK_ACTION",
                            "UK_CHAIN_CANDIDATE_FAILURE_EVENT",
                            "FK_CHAIN_CANDIDATE_FAILURE_TASK",
                            "FK_CHAIN_CANDIDATE_FAILURE_EVENT",
                            "FK_CHAIN_CANDIDATE_FAILURE_ACTION",
                            "CK_CHAIN_CANDIDATE_FAILURE_AUTHORITY",
                            "CK_CHAIN_CANDIDATE_FAILURE_CODE")));
        }
    }
}
