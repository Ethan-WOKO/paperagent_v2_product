package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V70ChainFoundationMigrationTest {
    @Test
    void createsFoundationAndOrdinaryPlanReplanAuthority()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database("v70")) {
            ChainMigrationTestSupport.migrateThrough(connection, 70);

            assertEquals(Set.of(
                    "AGENT_V2_CHAIN_COMMANDS",
                    "AGENT_V2_CHAIN_TASKS",
                    "AGENT_V2_CHAIN_AUTHORITY_EVENTS",
                    "AGENT_V2_CHAIN_INSTRUCTIONS",
                    "AGENT_V2_CHAIN_TASK_INSTRUCTION_BINDINGS",
                    "AGENT_V2_PLAN_REPLANS"),
                    ChainMigrationTestSupport.chainTables(connection));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_COMMANDS")
                    .contains("UK_CHAIN_COMMAND_REQUEST"));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_TASKS")
                    .contains("FK_CHAIN_TASK_COMMAND"));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_PLAN_REPLANS")
                    .contains("UK_PLAN_REPLAN_SOURCE"));

            ChainMigrationTestSupport.seedFoundation(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_v2_chain_commands(
                          command_id,user_id,session_id,client_request_id,
                          command_kind,request_sha256,status,created_at)
                        VALUES ('command-cancel',7,8,'request-cancel','CANCEL',
                          REPEAT('0',64),'RECEIVED',CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_instructions(
                          instruction_id,command_id,session_id,origin_task_id,
                          message_identity_key,relation_kind,
                          effective_boundary_digest,created_at)
                        VALUES ('instruction-cancel','command-cancel',8,
                          'opaque-missing-task','COMMAND:command-cancel','CANCEL',
                          REPEAT('0',64),CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_commands(
                          command_id,user_id,session_id,client_request_id,
                          command_kind,request_sha256,status,created_at)
                        VALUES ('command-bad-cancel',7,8,
                          'request-bad-cancel','CANCEL',REPEAT('0',64),
                          'RECEIVED',CURRENT_TIMESTAMP)
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_instructions(
                          instruction_id,command_id,session_id,origin_task_id,
                          message_id,body_sha256,message_identity_key,
                          relation_kind,effective_boundary_digest,created_at)
                        VALUES ('bad-cancel','command-bad-cancel',8,'task-1',
                          11,REPEAT('0',64),'COMMAND:bad','CANCEL',
                          REPEAT('0',64),CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_plan_replans(
                          replan_event_id,task_id,plan_id,source_event_sequence,
                          source_revision_id,source_revision_number,
                          result_revision_id,result_revision_number,
                          source_checkpoint_version,result_checkpoint_version,
                          result_event_sequence,lease_owner,fence_token,
                          request_format_version,request_sha256,request_json,
                          result_format_version,result_sha256,result_json,
                          committed_at)
                        VALUES ('replan-1','task-1','plan-1',1,'revision-1',1,
                          'revision-2',2,2,3,2,'owner',1,1,REPEAT('0',64),'{}',
                          1,REPEAT('0',64),'{}',CURRENT_TIMESTAMP)
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_plan_replans(
                          replan_event_id,task_id,plan_id,source_event_sequence,
                          source_revision_id,source_revision_number,
                          result_revision_id,result_revision_number,
                          source_checkpoint_version,result_checkpoint_version,
                          result_event_sequence,lease_owner,fence_token,
                          request_format_version,request_sha256,request_json,
                          result_format_version,result_sha256,result_json,
                          committed_at)
                        VALUES ('replan-2','task-1','plan-1',1,'revision-1',1,
                          'revision-2b',2,2,3,2,'owner',1,1,REPEAT('0',64),'{}',
                          1,REPEAT('0',64),'{}',CURRENT_TIMESTAMP)
                        """));
            }
        }
    }
}
