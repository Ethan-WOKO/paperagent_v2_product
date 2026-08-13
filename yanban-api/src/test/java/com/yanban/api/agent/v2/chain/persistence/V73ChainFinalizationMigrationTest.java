package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V73ChainFinalizationMigrationTest {
    @Test
    void createsReadinessCheckOutcomeAndDeliveryAuthorities()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database("v73")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            assertEquals(33, ChainMigrationTestSupport.chainTables(connection).size());
            assertTrue(ChainMigrationTestSupport.chainTables(connection).containsAll(
                    Set.of("AGENT_V2_CHAIN_FINALIZATION_READINESS",
                            "AGENT_V2_CHAIN_FINALIZATION_CHECKS",
                            "AGENT_V2_CHAIN_TASK_OUTCOMES",
                            "AGENT_V2_CHAIN_DELIVERY_MESSAGE_RESERVATIONS",
                            "AGENT_V2_CHAIN_DELIVERIES",
                            "AGENT_V2_CHAIN_DELIVERY_EVENTS")));

            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_FINALIZATION_READINESS")
                    .containsAll(Set.of("UK_CHAIN_READINESS_TRANSITION",
                            "UK_CHAIN_READINESS_SCOPE")));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_FINALIZATION_CHECKS")
                    .containsAll(Set.of("UK_CHAIN_FINALIZATION_CHECK_ATTEMPT",
                            "CK_CHAIN_FINALIZATION_CHECK_ERROR",
                            "CK_CHAIN_FINALIZATION_CHECK_RETRY")));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_TASK_OUTCOMES")
                    .contains("UK_CHAIN_TASK_OUTCOME_TASK"));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_DELIVERIES")
                    .contains("UK_CHAIN_DELIVERY_ASSISTANT_MESSAGE"));
            assertTrue(ChainMigrationTestSupport.constraints(connection,
                            "AGENT_V2_CHAIN_DELIVERY_MESSAGE_RESERVATIONS")
                    .containsAll(Set.of(
                            "UK_CHAIN_DELIVERY_RESERVATION_MESSAGE",
                            "FK_CHAIN_DELIVERY_RESERVATION_CONTENT")));
            assertTrue(ChainMigrationTestSupport.constraints(
                    connection, "AGENT_V2_CHAIN_DELIVERY_EVENTS")
                    .contains("UK_CHAIN_DELIVERY_ATTEMPT_EVENT"));

            String production = ChainMigrationTestSupport.read(false,
                    ChainMigrationTestSupport.fileName(73));
            for (String code : Set.of(
                    "READINESS_BINDING_MISMATCH",
                    "TASK_CONTRACT_UNSATISFIED",
                    "ACCEPTED_RESULT_SET_MISMATCH",
                    "CANDIDATE_BINDING_MISMATCH",
                    "VALIDATION_MISSING",
                    "VALIDATION_NOT_SUCCESSFUL",
                    "VALIDATION_BINDING_MISMATCH",
                    "PUBLISH_REQUIREMENT_MISMATCH",
                    "STALE_VERSION_FENCE",
                    "AUTHORITY_TEMPORARILY_UNAVAILABLE")) {
                assertTrue(production.contains("'" + code + "'"), code);
            }
        }
    }

    @Test
    void deliveryRouteMustBelongToTheSameTask() throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v73-task-scope")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedSecondFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);
            ChainMigrationTestSupport.seedSecondProposal(connection);

            try (Statement statement = connection.createStatement()) {
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-2", 2, "ROUTE_DECISION");
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
                        VALUES ('route-1','task-1','event-2','instruction-1',
                          'proposal-1','INITIAL',0,'DIRECT','simple',1,
                          REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',1,
                          REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-2", 2, "ROUTE_DECISION");
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
                        VALUES ('route-2','task-2','task-2-event-2',
                          'instruction-2','proposal-2','INITIAL',0,'DIRECT',
                          'simple',1,REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                          1,REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);

                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-3", 3, "DELIVERY");
                assertThrows(SQLException.class, () -> statement.execute("""
                        INSERT INTO agent_v2_chain_deliveries(
                          delivery_id,task_id,event_id,source_command_id,
                          route_decision_id,created_at)
                        VALUES ('delivery-cross','task-2','task-2-event-3',
                          'command-2','route-1',CURRENT_TIMESTAMP)
                        """));
                statement.execute("""
                        INSERT INTO agent_v2_chain_deliveries(
                          delivery_id,task_id,event_id,source_command_id,
                          route_decision_id,created_at)
                        VALUES ('delivery-2','task-2','task-2-event-3',
                          'command-2','route-2',CURRENT_TIMESTAMP)
                        """);
            }
        }
    }
}
