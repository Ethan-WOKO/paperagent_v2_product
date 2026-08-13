package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;
import io.paperagent.v2.chain.ChainProposalState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductChainPlanProposalAuthorityTest {
    private static final String HASH = "0".repeat(64);
    private static final String EMPTY_JSON_HASH =
            ProductChainRecordCodec.sha256("{}");
    private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

    @Test
    void revisedPlanBindingIsOwnedByTheProposalEmbeddedInItsIdentity()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "revised-plan-proposal-authority")) {
            ChainMigrationTestSupport.migrateThrough(connection, 84);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);

            String proposalId = "proposal-revision";
            String transitionId = "NONE";
            String bindingId = "plan-binding."
                    + ProductChainRecordCodec.sha256(
                    "task-1\0instruction-1\0" + proposalId
                            + "\0frame-1\0plan-1\0revision-2\0"
                            + transitionId);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO agent_v2_chain_context_revisions(
                          context_revision_id,task_id,role,work_state,
                          call_reason,instruction_id,projector_set_version,
                          pagination_version,runtime_policy_version,status,
                          module_count,request_manifest_format_version,
                          request_manifest_json,request_digest,
                          completion_token,created_at,completed_at)
                        VALUES ('context-revision','task-1','PLANNER',
                          'PLANNING','STEP_RECOVERY','instruction-1',
                          'projectors-v1','pagination-v1',
                          'chain-runtime-policy-v1','COMPLETE',13,1,'{}',
                          REPEAT('0',64),'completion-revision',
                          CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_model_invocations(
                          invocation_id,task_id,context_revision_id,
                          completion_token,role,work_state,call_reason,
                          provider,model,invocation_ordinal,
                          runtime_policy_version,created_at)
                        VALUES ('invocation-revision','task-1',
                          'context-revision','completion-revision','PLANNER',
                          'PLANNING','STEP_RECOVERY','fake','fake',2,
                          'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_chain_model_proposals(
                          proposal_id,task_id,invocation_id,schema_version,
                          role,proposal_kind,payload_format_version,
                          payload_sha256,payload_json,
                          source_refs_format_version,source_refs_sha256,
                          source_refs_json,created_at)
                        VALUES ('proposal-revision','task-1',
                          'invocation-revision',1,'PLANNER','PLAN_REVISION',1,
                          '%s','{}',1,'%s','{}',
                          CURRENT_TIMESTAMP)
                        """.formatted(EMPTY_JSON_HASH, EMPTY_JSON_HASH));
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
                          answer_required_refs_sha256,
                          answer_required_refs_json,needs_tool,needs_network,
                          needs_project,needs_persistent_progress,created_at)
                        VALUES ('route-1','task-1','event-route',
                          'instruction-1','proposal-1','INITIAL',0,
                          'PERSISTENT_PLAN_EXECUTE','work',1,REPEAT('0',64),
                          '{}',1,REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                          1,0,1,1,CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-binding", 3, "PLAN_BINDING");
                statement.execute("""
                        INSERT INTO agent_v2_chain_plan_bindings(
                          plan_binding_id,task_id,event_id,instruction_id,
                          route_decision_id,task_frame_id,plan_id,
                          plan_revision_id,plan_revision_number,
                          authority_type,authority_id,authority_sha256,
                          created_at)
                        VALUES ('%s','task-1','event-binding','instruction-1',
                          'route-1','frame-1','plan-1','revision-2',2,
                          'STABLE_V2_PLAN','revision-2',REPEAT('0',64),
                          CURRENT_TIMESTAMP)
                        """.formatted(bindingId));
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-accepted", 4,
                        "PROPOSAL_ACCEPTED");
                statement.execute("""
                        INSERT INTO agent_v2_chain_proposal_state_events(
                          proposal_id,state_sequence,task_id,event_id,
                          state_kind,committed_at)
                        VALUES ('proposal-revision',1,'task-1',
                          'event-accepted','ACCEPTED',CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        UPDATE agent_v2_chain_tasks
                           SET next_event_sequence = 4
                         WHERE task_id = 'task-1'
                        """);
            }

            String url = connection.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var transactions = new ProductChainTransactions(
                    new NamedParameterJdbcTemplate(dataSource),
                    new ProductChainRecordCodec(),
                    new DataSourceTransactionManager(dataSource),
                    () -> NOW);
            var model = new ProductChainModelRepositoryAdapter(transactions);

            ProposalStateEventRecord forged = replacement(
                    "event-forged", "plan-binding.forged");
            ProductChainPersistenceException rejected = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> model.appendProposalState(new AuthoritativeFact<>(
                            proposalEvent(forged), forged)));
            assertEquals("CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID",
                    rejected.code());

            ProposalStateEventRecord replacement = replacement(
                    "event-replaced", bindingId);
            assertFalse(model.appendProposalState(new AuthoritativeFact<>(
                    proposalEvent(replacement), replacement)).replayed());
        }
    }

    private static ProposalStateEventRecord replacement(
            String eventId, String bindingId) {
        return new ProposalStateEventRecord(
                "proposal-revision", 2, "task-1", eventId,
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "PLAN_BINDING", bindingId, NOW);
    }

    private static AuthorityEventRequest proposalEvent(
            ProposalStateEventRecord state) {
        return new AuthorityEventRequest(
                state.eventId(), state.taskId(),
                "PROPOSAL_" + state.stateKind().name(), null,
                HASH, state.committedAt());
    }
}
