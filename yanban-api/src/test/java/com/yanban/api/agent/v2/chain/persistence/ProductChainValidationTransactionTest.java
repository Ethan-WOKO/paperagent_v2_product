package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationConclusion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainValidationTransactionTest {
    private static final String HASH = "1".repeat(64);
    private static final String RECEIPT_HASH_1 = "2".repeat(64);
    private static final String RECEIPT_HASH_2 = "3".repeat(64);
    private static final String CANDIDATE = "4".repeat(64);
    private static final String VERSION = "5".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-09T01:00:00Z");

    @Test
    void setItemsAndAuthorityEventAreAtomicAndExactlyReplayable()
            throws Exception {
        try (Connection keeper = ChainMigrationTestSupport.database(
                "chain-validation-transaction")) {
            ChainMigrationTestSupport.migrateThrough(keeper, 81);
            seed(keeper);
            ProductChainValidationRepositoryAdapter repository =
                    repository(keeper);
            var request = request("validation-1", "validation-event-1",
                    "validation-key-1", "receipt-2", RECEIPT_HASH_2);

            var first = repository.appendValidation(
                    request.fact(), request.candidates(), request.actions());
            var replay = repository.appendValidation(
                    request.fact(), request.candidates(), request.actions());

            assertFalse(first.replayed());
            assertTrue(replay.replayed());
            assertEquals(request.candidates(), replay.candidateItems());
            assertEquals(request.actions(), replay.actionReceiptItems());
            assertEquals(1, count(keeper,
                    "agent_v2_chain_validation_sets"));
            assertEquals(1, count(keeper,
                    "agent_v2_chain_candidate_validation_items"));
            assertEquals(1, count(keeper,
                    "agent_v2_chain_action_receipt_validation_items"));

            var drifted = request("validation-1", "validation-event-1",
                    "validation-key-1", "receipt-2", "6".repeat(64));
            assertThrows(ProductChainPersistenceException.class, () ->
                    repository.appendValidation(drifted.fact(),
                            drifted.candidates(), drifted.actions()));

            var sameKeyDifferentIdentity = request(
                    "validation-other", "validation-event-other",
                    "validation-key-1", "receipt-2", RECEIPT_HASH_2);
            assertThrows(ProductChainPersistenceException.class, () ->
                    repository.appendValidation(
                            sameKeyDifferentIdentity.fact(),
                            sameKeyDifferentIdentity.candidates(),
                            sameKeyDifferentIdentity.actions()));

            var broken = request("validation-broken",
                    "validation-event-broken", "validation-key-broken",
                    "missing-receipt", RECEIPT_HASH_2);
            assertThrows(RuntimeException.class, () ->
                    repository.appendValidation(broken.fact(),
                            broken.candidates(), broken.actions()));
            assertEquals(0, count(keeper,
                    "agent_v2_chain_validation_sets",
                    "validation_id='validation-broken'"));
            assertEquals(0, count(keeper,
                    "agent_v2_chain_authority_events",
                    "event_id='validation-event-broken'"));
        }
    }

    private static Request request(
            String validationId, String eventId, String key,
            String candidateReceipt, String candidateReceiptHash) {
        var set = new ChainPersistenceRecords.ValidationSetRecord(
                validationId, "task-1", eventId, "frame-1", "plan-1",
                "revision-1", 1, "step-1", "activation-1", HASH,
                "7".repeat(64), "8".repeat(64),
                ChainValidationConclusion.PASSED, key, NOW);
        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                eventId, "task-1", "VALIDATION", null,
                ProductChainRecordCodec.sha256(validationId + "\0" + HASH
                        + "\0" + "7".repeat(64) + "\0"
                        + "8".repeat(64)), NOW);
        var candidate = new ChainPersistenceRecords
                .CandidateValidationItemRecord(
                validationId, "requirement-candidate", "task-1", HASH,
                "action-candidate", "action-validation", candidateReceipt,
                candidateReceiptHash, HASH, "candidate-1", "workspace-1",
                101L, CANDIDATE, VERSION,
                ChainValidationConclusion.PASSED);
        var action = new ChainPersistenceRecords
                .ActionReceiptValidationItemRecord(
                validationId, "requirement-action", "task-1", HASH,
                "action-candidate", "receipt-1", RECEIPT_HASH_1, HASH,
                ChainValidationConclusion.PASSED);
        return new Request(new ChainPersistenceRecords.AuthoritativeFact<>(
                event, set), List.of(candidate), List.of(action));
    }

    private static void seed(Connection connection) throws Exception {
        ChainMigrationTestSupport.seedFoundation(connection);
        ChainMigrationTestSupport.seedProposal(connection);
        try (var statement = connection.createStatement()) {
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-action-candidate", 2,
                    "ACTION_BOUND");
            statement.execute(actionSql(
                    "action-candidate", "event-action-candidate", 1));
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-candidate", 3,
                    "WORKSPACE_CANDIDATE");
            statement.execute("""
                    INSERT INTO agent_v2_chain_workspace_candidates(
                      workspace_candidate_id,task_id,event_id,action_id,
                      workspace_id,base_project_version,artifact_id,
                      candidate_fingerprint,diff_digest,
                      version_fence_sha256,created_at)
                    VALUES ('candidate-1','task-1','event-candidate',
                      'action-candidate','workspace-1',REPEAT('5',64),101,
                      REPEAT('4',64),REPEAT('6',64),REPEAT('7',64),
                      CURRENT_TIMESTAMP)
                    """);
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-action-validation", 4,
                    "ACTION_BOUND");
            statement.execute(actionSql(
                    "action-validation", "event-action-validation", 2));
            statement.execute("""
                    INSERT INTO agent_v2_receipts(
                      receipt_id,tool_call_id,payload_sha256,payload_json)
                    VALUES ('receipt-1','action-candidate',REPEAT('2',64),'{}')
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_receipts(
                      receipt_id,tool_call_id,payload_sha256,payload_json)
                    VALUES ('receipt-2','action-validation',REPEAT('3',64),'{}')
                    """);
            statement.execute("""
                    UPDATE agent_v2_chain_tasks SET next_event_sequence=4
                     WHERE task_id='task-1'
                    """);
        }
    }

    private static String actionSql(
            String actionId, String eventId, int attempt) {
        return """
                INSERT INTO agent_v2_chain_action_bindings(
                  action_id,task_id,event_id,proposal_id,attempt_no,
                  action_signature_sha256,idempotency_key,instruction_id,
                  task_frame_id,plan_id,plan_revision_id,step_id,
                  activation_event_id,workspace_id,base_candidate_key,
                  effect_intent_id,dispatch_ref,result_authority_type,
                  result_authority_ref,version_fence_sha256,created_at)
                VALUES ('%s','task-1','%s','proposal-1',%d,REPEAT('1',64),
                  '%s-key','instruction-1','frame-1','plan-1','revision-1',
                  'step-1','activation-1','workspace-1','NONE',NULL,NULL,
                  NULL,NULL,REPEAT('7',64),CURRENT_TIMESTAMP)
                """.formatted(actionId, eventId, attempt, actionId);
    }

    private static ProductChainValidationRepositoryAdapter repository(
            Connection connection) throws Exception {
        var dataSource = new DriverManagerDataSource(
                connection.getMetaData().getURL(), "sa", "");
        var transactions = new ProductChainTransactions(
                new NamedParameterJdbcTemplate(dataSource),
                new ProductChainRecordCodec(),
                new DataSourceTransactionManager(dataSource), () -> NOW);
        return new ProductChainValidationRepositoryAdapter(transactions);
    }

    private static int count(Connection connection, String table)
            throws Exception {
        return count(connection, table, "1=1");
    }

    private static int count(
            Connection connection, String table, String predicate)
            throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private record Request(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ValidationSetRecord> fact,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidates,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actions) {
    }
}
