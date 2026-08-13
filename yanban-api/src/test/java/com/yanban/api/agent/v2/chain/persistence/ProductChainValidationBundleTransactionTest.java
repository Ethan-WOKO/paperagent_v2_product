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

class ProductChainValidationBundleTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T05:00:00Z");
    private static final String REQUEST = "1".repeat(64);
    private static final String RECEIPTS = "2".repeat(64);
    private static final String CONCLUSION = "3".repeat(64);

    @Test
    void bundleEventAndMembersAreAtomicReplayableAndConflictingSafe()
            throws Exception {
        try (Connection keeper = ChainMigrationTestSupport.database(
                "chain-validation-bundle-transaction")) {
            ChainMigrationTestSupport.migrateThrough(keeper, 82);
            seed(keeper);
            ProductChainValidationBundleRepositoryAdapter repository =
                    repository(keeper);
            Request request = request("bundle-1", "bundle-event-1",
                    "bundle-key-1", "validation-1");

            var first = repository.appendBundle(
                    request.fact(), request.sets());
            var replay = repository.appendBundle(
                    request.fact(), request.sets());

            assertFalse(first.replayed());
            assertTrue(replay.replayed());
            assertEquals(request.sets(), replay.sets());
            assertEquals(1, count(keeper,
                    "agent_v2_chain_validation_bundles", "1=1"));
            assertEquals(1, count(keeper,
                    "agent_v2_chain_validation_bundle_sets", "1=1"));

            Request drifted = request("bundle-1", "bundle-event-1",
                    "bundle-key-1", "validation-2");
            assertThrows(ProductChainPersistenceException.class, () ->
                    repository.appendBundle(drifted.fact(), drifted.sets()));

            Request sameKey = request("bundle-other", "bundle-event-other",
                    "bundle-key-1", "validation-1");
            assertThrows(ProductChainPersistenceException.class, () ->
                    repository.appendBundle(sameKey.fact(), sameKey.sets()));

            Request broken = request("bundle-broken", "bundle-event-broken",
                    "bundle-key-broken", "validation-missing");
            assertThrows(RuntimeException.class, () ->
                    repository.appendBundle(broken.fact(), broken.sets()));
            assertEquals(0, count(keeper,
                    "agent_v2_chain_validation_bundles",
                    "validation_bundle_id='bundle-broken'"));
            assertEquals(0, count(keeper,
                    "agent_v2_chain_authority_events",
                    "event_id='bundle-event-broken'"));

            Request wrongRootBase = request(
                    "bundle-wrong-root", "bundle-event-wrong-root",
                    "bundle-key-wrong-root", "validation-1");
            var original = wrongRootBase.fact().fact();
            var wrongRoot = new ChainPersistenceRecords.ValidationBundleRecord(
                    original.validationBundleId(), original.taskId(),
                    original.eventId(), original.taskFrameId(),
                    original.planId(), "revision-wrong",
                    original.planRevisionNumber(), original.instructionId(),
                    original.finalStepId(), original.requestDigest(),
                    original.receiptSetDigest(), original.conclusionDigest(),
                    original.conclusion(), original.idempotencyKey(), NOW);
            var wrongRootFact = new ChainPersistenceRecords.AuthoritativeFact<>(
                    wrongRootBase.fact().event(), wrongRoot);
            assertThrows(ProductChainPersistenceException.class, () ->
                    repository.appendBundle(
                            wrongRootFact, wrongRootBase.sets()));
            assertEquals(0, count(keeper,
                    "agent_v2_chain_validation_bundles",
                    "validation_bundle_id='bundle-wrong-root'"));
            assertEquals(0, count(keeper,
                    "agent_v2_chain_authority_events",
                    "event_id='bundle-event-wrong-root'"));
        }
    }

    private static Request request(
            String bundleId, String eventId, String key,
            String validationId) {
        var bundle = new ChainPersistenceRecords.ValidationBundleRecord(
                bundleId, "task-1", eventId, "frame-1", "plan-1",
                "revision-1", 1, "instruction-1", "step-1", REQUEST,
                RECEIPTS, CONCLUSION, ChainValidationConclusion.PASSED,
                key, NOW);
        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                eventId, "task-1", "VALIDATION_BUNDLE", null,
                ProductChainRecordCodec.sha256(bundleId + "\0" + REQUEST
                        + "\0" + RECEIPTS + "\0" + CONCLUSION), NOW);
        var member = new ChainPersistenceRecords.ValidationBundleSetRecord(
                bundleId, "task-1", "step-1", "activation-1",
                validationId, REQUEST, RECEIPTS, CONCLUSION);
        return new Request(new ChainPersistenceRecords.AuthoritativeFact<>(
                event, bundle), List.of(member));
    }

    private static void seed(Connection connection) throws Exception {
        ChainMigrationTestSupport.seedFoundation(connection);
        ChainMigrationTestSupport.seedProposal(connection);
        try (var statement = connection.createStatement()) {
            ChainMigrationTestSupport.authorityEvent(
                    statement, "route-event", 2, "ROUTE_DECIDED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_route_decisions(
                      route_decision_id,task_id,event_id,instruction_id,
                      proposal_id,decision_kind,decision_ordinal,route,
                      route_reason,needs_tool,needs_network,needs_project,
                      needs_persistent_progress,transition_id,created_at)
                    VALUES ('route-1','task-1','route-event','instruction-1',
                      'proposal-1','INITIAL',0,'PERSISTENT_PLAN_EXECUTE',
                      'test',1,0,1,1,NULL,CURRENT_TIMESTAMP)
                    """);
            ChainMigrationTestSupport.authorityEvent(
                    statement, "plan-event", 3, "PLAN_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_plan_bindings(
                      plan_binding_id,task_id,event_id,instruction_id,
                      route_decision_id,task_frame_id,plan_id,plan_revision_id,
                      plan_revision_number,authority_type,authority_id,
                      authority_sha256,transition_id,created_at)
                    VALUES ('binding-1','task-1','plan-event','instruction-1',
                      'route-1','frame-1','plan-1','revision-1',1,
                      'PLAN_BOOTSTRAP','plan-1',REPEAT('0',64),NULL,
                      CURRENT_TIMESTAMP)
                    """);
            ChainMigrationTestSupport.authorityEvent(
                    statement, "validation-event-1", 4, "VALIDATION");
            statement.execute(validationSql(
                    "validation-1", "validation-event-1"));
            ChainMigrationTestSupport.authorityEvent(
                    statement, "validation-event-2", 5, "VALIDATION");
            statement.execute(validationSql(
                    "validation-2", "validation-event-2"));
            statement.execute("""
                    UPDATE agent_v2_chain_tasks SET next_event_sequence=5
                     WHERE task_id='task-1'
                    """);
        }
    }

    private static String validationSql(String id, String eventId) {
        return """
                INSERT INTO agent_v2_chain_validation_sets(
                  validation_id,task_id,event_id,task_frame_id,plan_id,
                  plan_revision_id,plan_revision_number,step_id,
                  activation_event_id,request_digest,receipt_set_digest,
                  conclusion_digest,conclusion,idempotency_key,created_at)
                VALUES ('%s','task-1','%s','frame-1','plan-1','revision-1',
                  1,'step-1','activation-1',REPEAT('1',64),REPEAT('2',64),
                  REPEAT('3',64),'PASSED','%s-key',CURRENT_TIMESTAMP)
                """.formatted(id, eventId, id);
    }

    private static ProductChainValidationBundleRepositoryAdapter repository(
            Connection connection) throws Exception {
        var dataSource = new DriverManagerDataSource(
                connection.getMetaData().getURL(), "sa", "");
        var transactions = new ProductChainTransactions(
                new NamedParameterJdbcTemplate(dataSource),
                new ProductChainRecordCodec(),
                new DataSourceTransactionManager(dataSource), () -> NOW);
        return new ProductChainValidationBundleRepositoryAdapter(transactions);
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
                    ChainPersistenceRecords.ValidationBundleRecord> fact,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> sets) {
    }
}
