package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V72ChainWorkflowMigrationTest {
    @Test
    void createsAppendOnlyWorkflowFactsWithStableTransitionStages()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database("v72")) {
            ChainMigrationTestSupport.migrateThrough(connection, 72);
            assertEquals(27, ChainMigrationTestSupport.chainTables(connection).size());
            assertTrue(ChainMigrationTestSupport.chainTables(connection).containsAll(
                    Set.of("AGENT_V2_CHAIN_TRANSITIONS",
                            "AGENT_V2_CHAIN_TRANSITION_STAGES",
                            "AGENT_V2_CHAIN_ROUTE_DECISIONS",
                            "AGENT_V2_CHAIN_INSTRUCTION_DISPOSITIONS",
                            "AGENT_V2_CHAIN_PLAN_BINDINGS",
                            "AGENT_V2_CHAIN_CANDIDATE_STEP_RESULTS",
                            "AGENT_V2_CHAIN_ACCEPTED_RESULTS",
                            "AGENT_V2_CHAIN_REVIEW_DECISIONS",
                            "AGENT_V2_CHAIN_RESULT_APPLICABILITY",
                            "AGENT_V2_CHAIN_PENDING_ITEMS",
                            "AGENT_V2_CHAIN_PENDING_ITEM_EVENTS",
                            "AGENT_V2_CHAIN_PERMISSION_DECISIONS",
                            "AGENT_V2_CHAIN_ACTION_BINDINGS",
                            "AGENT_V2_CHAIN_WORKSPACE_CANDIDATES")));

            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);
            try (Statement statement = connection.createStatement()) {
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-2", 2, "TRANSITION_OPENED");
                statement.execute("""
                        INSERT INTO agent_v2_chain_transitions(
                          transition_id,task_id,event_id,transition_type,
                          source_decision_id,target_identity_digest,created_at)
                        VALUES ('transition-1','task-1','event-2','ACCEPT_STEP',
                          'decision-1',REPEAT('0',64),CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-3", 3, "TRANSITION_STAGE");
                statement.execute("""
                        INSERT INTO agent_v2_chain_transition_stages(
                          transition_id,stage_code,task_id,event_id,
                          stage_ordinal,committed_at)
                        VALUES ('transition-1','OPEN','task-1','event-3',0,
                          CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-4", 4, "TRANSITION_STAGE");
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_transition_stages(
                          transition_id,stage_code,task_id,event_id,
                          stage_ordinal,committed_at)
                        VALUES ('transition-1','COMPLETE','task-1','event-4',0,
                          CURRENT_TIMESTAMP)
                        """));

                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-5", 5, "ROUTE_DECIDED");
                statement.execute("""
                        INSERT INTO agent_v2_chain_route_decisions(
                          route_decision_id,task_id,event_id,instruction_id,
                          proposal_id,decision_kind,decision_ordinal,route,
                          route_reason,direct_task_spec_format_version,
                          direct_task_spec_sha256,direct_task_spec_json,
                          user_constraints_format_version,
                          user_constraints_sha256,user_constraints_json,
                          answer_required_refs_format_version,
                          answer_required_refs_sha256,answer_required_refs_json,
                          needs_tool,needs_network,needs_project,
                          needs_persistent_progress,created_at)
                        VALUES ('route-1','task-1','event-5','instruction-1',
                          'proposal-1','INITIAL',0,'DIRECT','simple',1,
                          REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',1,
                          REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);
            }

            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_RESULT_APPLICABILITY")
                    .contains("UK_CHAIN_APPLICABILITY_TUPLE"));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_PENDING_ITEM_EVENTS")
                    .contains("CK_CHAIN_PENDING_ITEM_KIND"));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_ACTION_BINDINGS")
                    .contains("UK_CHAIN_ACTION_ATTEMPT"));
        }
    }

    @Test
    void workflowReferencesRejectCrossTaskAuthoritiesAndAcceptSameTaskOnes()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v72-task-scope")) {
            ChainMigrationTestSupport.migrateThrough(connection, 72);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedSecondFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);
            ChainMigrationTestSupport.seedSecondProposal(connection);

            try (Statement statement = connection.createStatement()) {
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-2", 2, "TRANSITION");
                statement.execute("""
                        INSERT INTO agent_v2_chain_transitions(
                          transition_id,task_id,event_id,transition_type,
                          source_decision_id,target_identity_digest,created_at)
                        VALUES ('transition-1','task-1','event-2','PLAN_CHANGE',
                          'decision-1',REPEAT('0',64),CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-2", 2, "TRANSITION");
                statement.execute("""
                        INSERT INTO agent_v2_chain_transitions(
                          transition_id,task_id,event_id,transition_type,
                          source_decision_id,target_identity_digest,created_at)
                        VALUES ('transition-2','task-2','task-2-event-2',
                          'PLAN_CHANGE','decision-2',REPEAT('0',64),
                          CURRENT_TIMESTAMP)
                        """);

                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-3", 3, "ROUTE_DECISION");
                statement.execute("""
                        INSERT INTO agent_v2_chain_route_decisions(
                          route_decision_id,task_id,event_id,instruction_id,
                          proposal_id,decision_kind,decision_ordinal,route,
                          route_reason,direct_task_spec_format_version,
                          direct_task_spec_sha256,direct_task_spec_json,
                          user_constraints_format_version,
                          user_constraints_sha256,user_constraints_json,
                          answer_required_refs_format_version,
                          answer_required_refs_sha256,answer_required_refs_json,
                          needs_tool,needs_network,needs_project,
                          needs_persistent_progress,created_at)
                        VALUES ('route-1','task-1','event-3','instruction-1',
                          'proposal-1','INITIAL',0,'DIRECT','simple',1,
                          REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',1,
                          REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-3", 3, "ROUTE_DECISION");
                statement.execute("""
                        INSERT INTO agent_v2_chain_route_decisions(
                          route_decision_id,task_id,event_id,instruction_id,
                          proposal_id,decision_kind,decision_ordinal,route,
                          route_reason,direct_task_spec_format_version,
                          direct_task_spec_sha256,direct_task_spec_json,
                          user_constraints_format_version,
                          user_constraints_sha256,user_constraints_json,
                          answer_required_refs_format_version,
                          answer_required_refs_sha256,answer_required_refs_json,
                          needs_tool,needs_network,needs_project,
                          needs_persistent_progress,created_at)
                        VALUES ('route-2','task-2','task-2-event-3',
                          'instruction-2','proposal-2','INITIAL',0,'DIRECT',
                          'simple',1,REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                          1,REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);

                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-4", 4, "ROUTE_DECISION");
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_route_decisions(
                          route_decision_id,task_id,event_id,instruction_id,
                          proposal_id,decision_kind,decision_ordinal,route,
                          route_reason,needs_tool,needs_network,needs_project,
                          needs_persistent_progress,parent_route_decision_id,
                          escalation_reason,transition_id,created_at)
                        VALUES ('route-cross','task-2','task-2-event-4',
                          'instruction-2','proposal-2','ESCALATION',1,
                          'PERSISTENT_PLAN_EXECUTE','escalate',1,0,0,1,
                          'route-1','needs plan','transition-1',CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_route_decisions(
                          route_decision_id,task_id,event_id,instruction_id,
                          proposal_id,decision_kind,decision_ordinal,route,
                          route_reason,needs_tool,needs_network,needs_project,
                          needs_persistent_progress,parent_route_decision_id,
                          escalation_reason,transition_id,created_at)
                        VALUES ('route-2-escalated','task-2','task-2-event-4',
                          'instruction-2','proposal-2','ESCALATION',1,
                          'PERSISTENT_PLAN_EXECUTE','escalate',1,0,0,1,
                          'route-2','needs plan','transition-2',CURRENT_TIMESTAMP)
                        """);

                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-5", 5, "PLAN_BINDING");
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_plan_bindings(
                          plan_binding_id,task_id,event_id,instruction_id,
                          route_decision_id,task_frame_id,plan_id,
                          plan_revision_id,plan_revision_number,authority_type,
                          authority_id,authority_sha256,transition_id,created_at)
                        VALUES ('binding-cross','task-2','task-2-event-5',
                          'instruction-2','route-2-escalated','task-frame-2',
                          'plan-2','revision-2',1,'PLAN_BOOTSTRAP','plan-2',
                          REPEAT('0',64),'transition-1',CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_plan_bindings(
                          plan_binding_id,task_id,event_id,instruction_id,
                          route_decision_id,task_frame_id,plan_id,
                          plan_revision_id,plan_revision_number,authority_type,
                          authority_id,authority_sha256,transition_id,created_at)
                        VALUES ('binding-2','task-2','task-2-event-5',
                          'instruction-2','route-2-escalated','task-frame-2',
                          'plan-2','revision-2',1,'PLAN_BOOTSTRAP','plan-2',
                          REPEAT('0',64),'transition-2',CURRENT_TIMESTAMP)
                        """);

                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-6", 6, "PENDING_ITEM");
                statement.execute("""
                        INSERT INTO agent_v2_chain_pending_items(
                          gap_id,task_id,event_id,source_proposal_id,pending_type,
                          gap_identity_sha256,missing_fields_format_version,
                          missing_fields_sha256,missing_fields_json,question,
                          expected_format,validation_role,resume_role,
                          resume_position_format_version,resume_position_sha256,
                          resume_position_json,version_fence_sha256,created_at)
                        VALUES ('gap-2','task-2','task-2-event-6','proposal-2',
                          'USER_INFORMATION',REPEAT('0',64),1,REPEAT('0',64),
                          '{}','question','text','PLANNER','PLANNER',1,
                          REPEAT('0',64),'{}',REPEAT('0',64),CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-7", 7, "PENDING_ITEM_RESOLVED");
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_pending_item_events(
                          gap_id,response_round,event_kind,task_id,event_id,
                          validation_invocation_id,gap_validation_outcome,
                          detail_format_version,detail_sha256,detail_json,
                          committed_at)
                        VALUES ('gap-2',0,'RESOLVED','task-2','task-2-event-7',
                          'invocation-1','RESOLVED',1,REPEAT('0',64),'{}',
                          CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_pending_item_events(
                          gap_id,response_round,event_kind,task_id,event_id,
                          validation_invocation_id,gap_validation_outcome,
                          detail_format_version,detail_sha256,detail_json,
                          committed_at)
                        VALUES ('gap-2',0,'RESOLVED','task-2','task-2-event-7',
                          'invocation-2','RESOLVED',1,REPEAT('0',64),'{}',
                          CURRENT_TIMESTAMP)
                        """);
            }
        }
    }
}
