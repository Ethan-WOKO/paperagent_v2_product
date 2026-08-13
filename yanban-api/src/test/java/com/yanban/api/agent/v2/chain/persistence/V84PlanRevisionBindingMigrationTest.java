package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V84PlanRevisionBindingMigrationTest {
    @Test
    void allowsDistinctRevisionsOfOnePlanButRejectsDuplicateRevision()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v84-plan-revision-binding")) {
            ChainMigrationTestSupport.migrateThrough(connection, 84);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);

            try (Statement statement = connection.createStatement()) {
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-route", 2, "ROUTE_DECISION");
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
                        VALUES ('route-1','task-1','event-route','instruction-1',
                          'proposal-1','INITIAL',0,'DIRECT','simple',1,
                          REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',1,
                          REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);
                insertBinding(statement, "binding-1", "event-binding-1",
                        3, "revision-1", 1);
                insertBinding(statement, "binding-2", "event-binding-2",
                        4, "revision-2", 2);

                ChainMigrationTestSupport.authorityEvent(statement,
                        "event-binding-duplicate", 5, "PLAN_BINDING");
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_plan_bindings(
                          plan_binding_id,task_id,event_id,instruction_id,
                          route_decision_id,task_frame_id,plan_id,
                          plan_revision_id,plan_revision_number,authority_type,
                          authority_id,authority_sha256,created_at)
                        VALUES ('binding-duplicate','task-1',
                          'event-binding-duplicate','instruction-1','route-1',
                          'frame-1','plan-1','revision-2',2,'STABLE_V2_PLAN',
                          'revision-2',REPEAT('0',64),CURRENT_TIMESTAMP)
                        """));
            }

            var constraints = ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_PLAN_BINDINGS");
            assertTrue(constraints.contains(
                    "UK_CHAIN_PLAN_BINDING_REVISION"));
            assertTrue(!constraints.contains("UK_CHAIN_PLAN_BINDING_PLAN"));
        }
    }

    private static void insertBinding(
            Statement statement, String bindingId, String eventId,
            long sequence, String revisionId, long revisionNumber)
            throws SQLException {
        ChainMigrationTestSupport.authorityEvent(
                statement, eventId, sequence, "PLAN_BINDING");
        statement.execute("""
                INSERT INTO agent_v2_chain_plan_bindings(
                  plan_binding_id,task_id,event_id,instruction_id,
                  route_decision_id,task_frame_id,plan_id,plan_revision_id,
                  plan_revision_number,authority_type,authority_id,
                  authority_sha256,created_at)
                VALUES ('%s','task-1','%s','instruction-1','route-1',
                  'frame-1','plan-1','%s',%d,'STABLE_V2_PLAN','%s',
                  REPEAT('0',64),CURRENT_TIMESTAMP)
                """.formatted(bindingId, eventId, revisionId,
                revisionNumber, revisionId));
    }
}
