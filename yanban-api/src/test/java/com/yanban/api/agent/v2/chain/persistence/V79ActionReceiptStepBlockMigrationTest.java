package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class V79ActionReceiptStepBlockMigrationTest {
    @Test
    void createsIndependentActionReceiptStepBlockAuthority()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v79-action-receipt-step-block")) {
            ChainMigrationTestSupport.migrateThrough(connection, 79);

            assertEquals(39,
                    ChainMigrationTestSupport.chainTables(connection).size());
            String table = "AGENT_V2_CHAIN_ACTION_RECEIPT_STEP_BLOCKS";
            assertTrue(ChainMigrationTestSupport.chainTables(connection)
                    .contains(table));
            assertEquals(Set.of(
                            "STEP_BLOCK_ID", "TASK_ID", "EVENT_ID",
                            "ACTION_ID", "RECEIPT_ID",
                            "RECEIPT_PAYLOAD_SHA256", "INSTRUCTION_ID",
                            "TASK_FRAME_ID", "PLAN_ID", "PLAN_REVISION_ID",
                            "PLAN_REVISION_NUMBER", "STEP_ID",
                            "ACTIVATION_EVENT_ID", "REPAIR_PROPOSAL_ID",
                            "REPAIR_CONTEXT_REVISION_ID",
                            "REPAIR_PROPOSAL_SIGNATURE_SHA256",
                            "PROGRESS_AUTHORITY_EVENT_CUT",
                            "PROGRESS_SNAPSHOT_DIGEST_SHA256",
                            "THRESHOLD_OBSERVED_OCCURRENCES",
                            "RECEIPT_STATUS", "FAILURE_CATEGORY",
                            "FAILURE_CODE", "BLOCK_REASON_CODE",
                            "RUNTIME_POLICY_VERSION", "VERSION_FENCE_SHA256",
                            "BLOCK_IDENTITY_DIGEST_SHA256", "CREATED_AT"),
                    ChainMigrationTestSupport.columns(connection, table));
            assertTrue(ChainMigrationTestSupport.constraints(connection, table)
                    .containsAll(Set.of(
                            "UK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_ACTION",
                            "UK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_EVENT",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_TASK",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_ACTION",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_RECEIPT",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_REPAIR_PROPOSAL",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_REPAIR_CONTEXT",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_EVENT",
                            "FK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_INSTRUCTION",
                            "CK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_PLAN_REVISION",
                            "CK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_PROGRESS",
                            "CK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_STATUS",
                            "CK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_CATEGORY",
                            "CK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_HASHES",
                            "CK_CHAIN_ACTION_RECEIPT_STEP_BLOCK_REASON")));
        }
    }

    @Test
    void completedEffectWithoutFormalSuccessorRemainsRecoverable()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "v79-action-receipt-recovery-gap")) {
            ChainMigrationTestSupport.migrateThrough(connection, 79);
            ChainMigrationTestSupport.seedFoundation(connection);
            ChainMigrationTestSupport.seedProposal(connection);
            try (var statement = connection.createStatement()) {
                ChainMigrationTestSupport.authorityEvent(
                        statement, "event-action-1", 2L, "ACTION_BOUND");
                statement.execute("""
                        INSERT INTO agent_v2_chain_action_bindings(
                          action_id,task_id,event_id,proposal_id,attempt_no,
                          action_signature_sha256,idempotency_key,instruction_id,
                          task_frame_id,plan_id,plan_revision_id,step_id,
                          activation_event_id,workspace_id,base_candidate_key,
                          effect_intent_id,dispatch_ref,result_authority_type,
                          result_authority_ref,version_fence_sha256,created_at)
                        VALUES ('action-1','task-1','event-action-1','proposal-1',1,
                          REPEAT('1',64),'action-key-1','instruction-1','frame-1',
                          'plan-1','revision-1','step-1','activation-1',
                          'workspace-1','NONE',NULL,NULL,NULL,NULL,
                          REPEAT('2',64),CURRENT_TIMESTAMP)
                        """);
                statement.execute("""
                        INSERT INTO agent_v2_effect_results(tool_call_id,receipt_id)
                        VALUES ('action-1','receipt-1')
                        """);
            }
            String url = connection.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var transactions = new ProductChainTransactions(
                    new NamedParameterJdbcTemplate(dataSource),
                    new ProductChainRecordCodec(),
                    new DataSourceTransactionManager(dataSource),
                    () -> java.time.Instant.parse("2026-08-09T00:00:00Z"));
            var workflow = new ProductChainWorkflowRepositoryAdapter(
                    transactions);

            assertEquals(List.of("action-1"), workflow
                    .findInFlightActions("task-1").stream()
                    .map(value -> value.actionId()).toList());
        }
    }
}
