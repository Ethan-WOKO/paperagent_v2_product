package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V76ChainContextBuildFailureMigrationTest {
    @Test
    void createsOnlyTheNarrowContextBuildFailureAuthority() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v76-context-build-failure")) {
            ChainMigrationTestSupport.migrateThrough(connection, 76);

            assertEquals(36,
                    ChainMigrationTestSupport.chainTables(connection).size());
            String table = "AGENT_V2_CHAIN_CONTEXT_BUILD_FAILURES";
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .contains(table));
            assertEquals(Set.of(
                            "CONTEXT_BUILD_FAILURE_ID", "TASK_ID", "EVENT_ID",
                            "CONTEXT_REVISION_ID", "ROLE", "WORK_STATE",
                            "CALL_REASON", "INSTRUCTION_ID", "FAILED_MODULE",
                            "ERROR_CODE", "PROJECTOR_SET_VERSION",
                            "PAGINATION_VERSION", "RUNTIME_POLICY_VERSION",
                            "CREATED_AT"),
                    ChainMigrationTestSupport.columns(connection, table));
            assertTrue(ChainMigrationTestSupport.constraints(connection, table)
                    .containsAll(Set.of(
                            "UK_CHAIN_CONTEXT_BUILD_FAILURE_CONTEXT",
                            "UK_CHAIN_CONTEXT_BUILD_FAILURE_EVENT",
                            "FK_CHAIN_CONTEXT_BUILD_FAILURE_TASK",
                            "FK_CHAIN_CONTEXT_BUILD_FAILURE_CONTEXT",
                            "FK_CHAIN_CONTEXT_BUILD_FAILURE_EVENT",
                            "FK_CHAIN_CONTEXT_BUILD_FAILURE_INSTRUCTION",
                            "CK_CHAIN_CONTEXT_BUILD_FAILURE_ROLE",
                            "CK_CHAIN_CONTEXT_BUILD_FAILURE_WORK_STATE",
                            "CK_CHAIN_CONTEXT_BUILD_FAILURE_MODULE",
                            "CK_CHAIN_CONTEXT_BUILD_FAILURE_CODE")));
        }
    }

    @Test
    void rejectsAnotherFailureForTheSameContextAndNonBlockedCode()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v76-context-build-failure-constraints")) {
            ChainMigrationTestSupport.migrateThrough(connection, 76);
            ChainMigrationTestSupport.seedFoundation(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_v2_chain_context_revisions(
                          context_revision_id,task_id,role,work_state,
                          call_reason,instruction_id,projector_set_version,
                          pagination_version,runtime_policy_version,status,
                          module_count,created_at)
                        VALUES ('context-v76','task-1','PLANNER','PLANNING',
                          'INITIAL','instruction-1','projector-v1',
                          'pagination-v1','chain-runtime-policy-v1','BUILDING',0,
                          TIMESTAMP '2026-08-09 08:00:00.000001')
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_authority_events(
                          event_id,task_id,event_sequence,event_type,
                          source_identity_sha256,committed_at)
                        VALUES ('event-v76-1','task-1',2,
                          'CONTEXT_BUILD_FAILURE',
                          '0000000000000000000000000000000000000000000000000000000000000000',
                          TIMESTAMP '2026-08-09 08:00:01.000001')
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_context_build_failures(
                          context_build_failure_id,task_id,event_id,
                          context_revision_id,role,work_state,call_reason,
                          instruction_id,failed_module,error_code,
                          projector_set_version,pagination_version,
                          runtime_policy_version,created_at)
                        VALUES ('failure-v76-1','task-1','event-v76-1',
                          'context-v76','PLANNER','PLANNING','INITIAL',
                          'instruction-1','PROJECT_INPUTS',
                          'CONTEXT_INPUT_BLOCKED','projector-v1','pagination-v1',
                          'chain-runtime-policy-v1',
                          TIMESTAMP '2026-08-09 08:00:01.000001')
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_authority_events(
                          event_id,task_id,event_sequence,event_type,
                          source_identity_sha256,committed_at)
                        VALUES ('event-v76-2','task-1',3,
                          'CONTEXT_BUILD_FAILURE',
                          '1111111111111111111111111111111111111111111111111111111111111111',
                          TIMESTAMP '2026-08-09 08:00:02.000001')
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_context_build_failures(
                          context_build_failure_id,task_id,event_id,
                          context_revision_id,role,work_state,call_reason,
                          instruction_id,failed_module,error_code,
                          projector_set_version,pagination_version,
                          runtime_policy_version,created_at)
                        VALUES ('failure-v76-2','task-1','event-v76-2',
                          'context-v76','PLANNER','PLANNING','INITIAL',
                          'instruction-1','PROJECT_INPUTS',
                          'CONTEXT_INPUT_BLOCKED','projector-v1','pagination-v1',
                          'chain-runtime-policy-v1',
                          TIMESTAMP '2026-08-09 08:00:02.000001')
                        """));
                assertThrows(SQLException.class, () -> statement.execute("""
                        UPDATE agent_v2_chain_context_build_failures
                           SET error_code = 'MODEL_FAILED'
                         WHERE context_build_failure_id = 'failure-v76-1'
                        """));
            }
        }
    }
}
