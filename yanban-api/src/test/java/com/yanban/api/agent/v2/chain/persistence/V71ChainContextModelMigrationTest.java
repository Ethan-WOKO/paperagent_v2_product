package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V71ChainContextModelMigrationTest {
    @Test
    void enforcesContextTerminalCutsAndCompleteRevisionConsumption()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database("v71")) {
            ChainMigrationTestSupport.migrateThrough(connection, 71);
            assertEquals(13, ChainMigrationTestSupport.chainTables(connection).size());
            assertTrue(ChainMigrationTestSupport.chainTables(connection).containsAll(
                    Set.of("AGENT_V2_CHAIN_CONTEXT_REVISIONS",
                            "AGENT_V2_CHAIN_CONTEXT_MODULES",
                            "AGENT_V2_CHAIN_MODEL_INVOCATIONS",
                            "AGENT_V2_CHAIN_PROVIDER_ATTEMPTS",
                            "AGENT_V2_CHAIN_CONTENTS",
                            "AGENT_V2_CHAIN_MODEL_PROPOSALS",
                            "AGENT_V2_CHAIN_PROPOSAL_STATE_EVENTS")));

            ChainMigrationTestSupport.seedFoundation(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_v2_chain_context_revisions(
                          context_revision_id,task_id,role,work_state,call_reason,
                          instruction_id,projector_set_version,pagination_version,
                          runtime_policy_version,status,module_count,created_at)
                        VALUES ('building','task-1','PLANNER','PLANNING','INITIAL',
                          'instruction-1','projectors-v1','pagination-v1',
                          'chain-runtime-policy-v1','BUILDING',0,CURRENT_TIMESTAMP)
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_context_revisions(
                          context_revision_id,task_id,role,work_state,call_reason,
                          instruction_id,projector_set_version,pagination_version,
                          runtime_policy_version,status,module_count,
                          completion_token,created_at,completed_at)
                        VALUES ('bad-complete','task-1','PLANNER','PLANNING',
                          'INITIAL','instruction-1','projectors-v1',
                          'pagination-v1','chain-runtime-policy-v1','COMPLETE',13,
                          'token',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """));
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_context_modules(
                          context_revision_id,task_id,module_ordinal,module_kind,
                          presence_kind,source_version_format_version,
                          source_version_sha256,source_version_json,
                          read_boundary_format_version,read_boundary_sha256,
                          read_boundary_json,projection_version,pagination_version,
                          projection_parameters_format_version,
                          projection_parameters_sha256,projection_parameters_json,
                          projection_format_version,projection_digest,
                          projection_json,created_at)
                        VALUES ('building','task-1',14,'MODEL_HISTORY','EMPTY',1,
                          REPEAT('0',64),'{}',1,REPEAT('0',64),'{}','v1','v1',1,
                          REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                          CURRENT_TIMESTAMP)
                        """));
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_model_invocations(
                          invocation_id,task_id,context_revision_id,
                          completion_token,role,work_state,call_reason,provider,
                          model,invocation_ordinal,runtime_policy_version,created_at)
                        VALUES ('bad-invocation','task-1','building','not-complete',
                          'PLANNER','PLANNING','INITIAL','fake','fake',1,
                          'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                        """));
            }

            ChainMigrationTestSupport.seedProposal(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_v2_chain_authority_events(
                          event_id,task_id,event_sequence,event_type,
                          source_identity_sha256,committed_at)
                        VALUES
                          ('event-state-1','task-1',2,'PROPOSAL_ACCEPTED',
                           REPEAT('0',64),CURRENT_TIMESTAMP),
                          ('event-state-2','task-1',3,'PROPOSAL_ACCEPTED',
                           REPEAT('0',64),CURRENT_TIMESTAMP),
                          ('event-state-3','task-1',4,
                           'PROPOSAL_REPLACED_BY_OFFICIAL_RESULT',
                           REPEAT('0',64),CURRENT_TIMESTAMP),
                          ('event-state-4','task-1',5,
                           'PROPOSAL_REPLACED_BY_OFFICIAL_RESULT',
                           REPEAT('0',64),CURRENT_TIMESTAMP)
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_proposal_state_events(
                          proposal_id,state_sequence,task_id,event_id,
                          state_kind,official_authority_type,
                          official_authority_ref,committed_at)
                        VALUES ('proposal-1',1,'task-1','event-state-1',
                          'ACCEPTED','ROUTE_DECISION','route-1',
                          CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_proposal_state_events(
                          proposal_id,state_sequence,task_id,event_id,
                          state_kind,committed_at)
                        VALUES ('proposal-1',1,'task-1','event-state-2',
                          'ACCEPTED',CURRENT_TIMESTAMP)
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_proposal_state_events(
                          proposal_id,state_sequence,task_id,event_id,
                          state_kind,committed_at)
                        VALUES ('proposal-1',2,'task-1','event-state-3',
                          'REPLACED_BY_OFFICIAL_RESULT',CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_proposal_state_events(
                          proposal_id,state_sequence,task_id,event_id,
                          state_kind,official_authority_type,
                          official_authority_ref,committed_at)
                        VALUES ('proposal-1',2,'task-1','event-state-4',
                          'REPLACED_BY_OFFICIAL_RESULT','ROUTE_DECISION',
                          'route-1',CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_contents(
                          content_id,task_id,invocation_id,content_kind,body,
                          body_sha256,media_type,created_at)
                        VALUES ('content-1','task-1','invocation-1','ANSWER_BODY',
                          'answer',REPEAT('0',64),'text/plain',CURRENT_TIMESTAMP)
                        """);
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_contents(
                          content_id,task_id,invocation_id,content_kind,body,
                          body_sha256,media_type,created_at)
                        VALUES ('content-bad','task-1','invocation-1','RAW_RESPONSE',
                          'raw',REPEAT('0',64),'text/plain',CURRENT_TIMESTAMP)
                        """));
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_model_proposals(
                          proposal_id,task_id,invocation_id,schema_version,role,
                          proposal_kind,payload_format_version,payload_sha256,
                          payload_json,source_refs_format_version,
                          source_refs_sha256,source_refs_json,created_at)
                        VALUES ('proposal-duplicate','task-1','invocation-1',1,
                          'PLANNER','DIRECT_ROUTE',1,REPEAT('0',64),'{}',1,
                          REPEAT('0',64),'{}',CURRENT_TIMESTAMP)
                        """));
            }
        }
    }

    @Test
    void contextAndInvocationReferencesCannotCrossTaskBoundaries()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v71-task-scope")) {
            ChainMigrationTestSupport.migrateThrough(connection, 71);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedSecondFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);
            ChainMigrationTestSupport.seedSecondProposal(connection);

            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_context_revisions(
                          context_revision_id,task_id,parent_context_revision_id,
                          role,work_state,call_reason,instruction_id,
                          projector_set_version,pagination_version,
                          runtime_policy_version,status,module_count,created_at)
                        VALUES ('cross-parent','task-2','context-1','PLANNER',
                          'PLANNING','RETRY','instruction-2','projectors-v1',
                          'pagination-v1','chain-runtime-policy-v1','BUILDING',0,
                          CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_context_revisions(
                          context_revision_id,task_id,parent_context_revision_id,
                          role,work_state,call_reason,instruction_id,
                          projector_set_version,pagination_version,
                          runtime_policy_version,status,module_count,created_at)
                        VALUES ('same-parent','task-2','context-2','PLANNER',
                          'PLANNING','RETRY','instruction-2','projectors-v1',
                          'pagination-v1','chain-runtime-policy-v1','BUILDING',0,
                          CURRENT_TIMESTAMP)
                        """);

                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_model_invocations(
                          invocation_id,task_id,context_revision_id,
                          completion_token,role,work_state,call_reason,provider,
                          model,invocation_ordinal,runtime_policy_version,
                          created_at)
                        VALUES ('cross-invocation','task-2','context-1',
                          'completion-1','PLANNER','PLANNING','RETRY','fake',
                          'fake',2,'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_model_invocations(
                          invocation_id,task_id,context_revision_id,
                          completion_token,role,work_state,call_reason,provider,
                          model,invocation_ordinal,runtime_policy_version,
                          created_at)
                        VALUES ('same-invocation','task-2','context-2',
                          'completion-2','PLANNER','PLANNING','RETRY','fake',
                          'fake',2,'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                        """);
            }
        }
    }
}
