package com.yanban.api.agent.v2.chain.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductChainSessionDeletionTest {

    @Test
    void deletesOnlyTheOwnedChainClosureAndItsExactlyBoundPlan()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "chain-session-delete")) {
            replaceStableStubs(connection);
            ChainMigrationTestSupport.migrateThrough(connection, 86);
            seedOwnedAndUnrelatedRows(connection);
            seedDeepContextHistory(connection, 22);

            var jdbc = new NamedParameterJdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            var planDeletion = new ProductChainPlanDeletionTransactions(jdbc);
            var sessionLock = new ProductChainSessionMutationLock(jdbc);
            var service = new ProductChainSessionDeletionService(
                    planDeletion, sessionLock, jdbc);

            assertEquals(1, service.deleteOwnedSessionData(7, 8));

            assertCount(connection, "agent_v2_chain_tasks", 1);
            assertCount(connection, "agent_v2_chain_context_revisions", 0);
            assertCount(connection, "agent_v2_chain_commands", 1);
            assertCount(connection, "agent_v2_chain_instructions", 1);
            assertCount(connection,
                    "agent_v2_chain_candidate_validation_items", 0);
            assertCount(connection,
                    "agent_v2_chain_action_receipt_validation_items", 0);
            assertCount(connection,
                    "agent_v2_chain_validation_bundle_sets", 0);
            assertCount(connection,
                    "agent_v2_chain_validation_bundles", 0);
            assertCount(connection, "agent_v2_chain_validation_sets", 0);
            assertCount(connection, "agent_v2_chain_task_outcomes", 0);
            assertCount(connection,
                    "agent_v2_chain_finalization_checks", 0);
            assertCount(connection,
                    "agent_v2_chain_finalization_readiness", 0);
            assertCount(connection, "agent_v2_chain_plan_bindings", 0);
            assertCount(connection, "agent_v2_plan_bootstraps", 1);
            assertCount(connection, "agent_v2_plan_replans", 0);
            assertCount(connection, "agent_v2_effect_intents", 1);
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*) FROM agent_v2_plan_bootstraps
                     WHERE plan_id = 'literature-plan'
                    """));
            assertEquals(1, scalar(connection, """
                    SELECT COUNT(*) FROM agent_v2_chain_tasks
                     WHERE task_id = 'other-task' AND user_id = 99
                    """));

            ProductChainPersistenceException unknown = assertThrows(
                    ProductChainPersistenceException.class,
                    () -> service.deleteOwnedSessionData(99, 8));
            assertEquals("CHAIN_SESSION_NOT_FOUND", unknown.code());
        }
    }

    @Test
    void locksTheOwnedSessionAndTasksBeforeFreezingThePlanSnapshot() {
        ProductChainSessionMutationLock sessionLock =
                mock(ProductChainSessionMutationLock.class);
        ProductChainPlanDeletionTransactions planDeletion =
                mock(ProductChainPlanDeletionTransactions.class);
        NamedParameterJdbcTemplate jdbc =
                mock(NamedParameterJdbcTemplate.class);
        var service = new ProductChainSessionDeletionService(
                planDeletion, sessionLock, jdbc);

        service.deleteOwnedSessionData(7, 8);

        var ordered = inOrder(sessionLock, jdbc, planDeletion);
        ordered.verify(sessionLock).lockOwnedSession(7, 8);
        ordered.verify(sessionLock).lockOwnedTasks(7, 8);
        ordered.verify(jdbc).update(argThat(sql -> normalize(sql)
                        .startsWith("UPDATE AGENT_V2_CHAIN_CONTEXT_REVISIONS")
                        && normalize(sql).contains(
                        "SET PARENT_CONTEXT_REVISION_ID = NULL")),
                any(SqlParameterSource.class));
        ordered.verify(jdbc).update(argThat(sql -> normalize(sql)
                        .startsWith("DELETE FROM AGENT_V2_CHAIN_VALIDATION_BUNDLE_SETS")),
                any(SqlParameterSource.class));
        ordered.verify(jdbc).update(argThat(sql -> normalize(sql)
                        .startsWith("DELETE FROM AGENT_V2_CHAIN_CANDIDATE_VALIDATION_ITEMS")),
                any(SqlParameterSource.class));
        ordered.verify(jdbc).update(argThat(sql -> normalize(sql)
                        .startsWith("DELETE FROM AGENT_V2_CHAIN_ACTION_RECEIPT_VALIDATION_ITEMS")),
                any(SqlParameterSource.class));
        ordered.verify(jdbc).update(argThat(sql -> normalize(sql)
                        .startsWith("DELETE FROM AGENT_V2_CHAIN_TASK_OUTCOMES")),
                any(SqlParameterSource.class));
        ordered.verify(planDeletion).deleteBoundPlans(7, 8);
    }

    @Test
    void usesDeterministicTaskThenPlanLockOrderBeforeStableDeletes() {
        NamedParameterJdbcTemplate taskJdbc =
                mock(NamedParameterJdbcTemplate.class);
        when(taskJdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "ORDER BY TASK_ID")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("task-a", "task-b"));
        when(taskJdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "WHERE TASK_ID = :TASKID")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("task-a"), List.of("task-b"));
        new ProductChainSessionMutationLock(taskJdbc)
                .lockOwnedTasks(7, 8);

        var taskOrder = inOrder(taskJdbc);
        taskOrder.verify(taskJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("ORDER BY TASK_ID")),
                any(SqlParameterSource.class), eq(String.class));
        taskOrder.verify(taskJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("WHERE TASK_ID = :TASKID")),
                argThat((SqlParameterSource parameters) -> "task-a".equals(
                        parameters.getValue("taskId"))), eq(String.class));
        taskOrder.verify(taskJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("WHERE TASK_ID = :TASKID")),
                argThat((SqlParameterSource parameters) -> "task-b".equals(
                        parameters.getValue("taskId"))), eq(String.class));

        NamedParameterJdbcTemplate planJdbc =
                mock(NamedParameterJdbcTemplate.class);
        when(planJdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "FROM AGENT_V2_CHAIN_PLAN_BINDINGS")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("plan-a", "plan-b"));
        when(planJdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "FROM AGENT_V2_PLAN_BOOTSTRAPS")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("plan-a"), List.of("plan-b"));
        when(planJdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "FROM AGENT_V2_EFFECT_INTENTS")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of());

        new ProductChainPlanDeletionTransactions(planJdbc)
                .deleteBoundPlans(7, 8);

        var ordered = inOrder(planJdbc);
        ordered.verify(planJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("ORDER BY BINDING.PLAN_ID")),
                any(SqlParameterSource.class), eq(String.class));
        ordered.verify(planJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("FROM AGENT_V2_PLAN_BOOTSTRAPS")),
                argThat((SqlParameterSource parameters) -> "plan-a".equals(
                        parameters.getValue("planId"))), eq(String.class));
        ordered.verify(planJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("FROM AGENT_V2_PLAN_BOOTSTRAPS")),
                argThat((SqlParameterSource parameters) -> "plan-b".equals(
                        parameters.getValue("planId"))), eq(String.class));
        ordered.verify(planJdbc).queryForList(argThat(sql -> normalize(sql)
                        .contains("FROM AGENT_V2_EFFECT_INTENTS")),
                any(SqlParameterSource.class), eq(String.class));
        ordered.verify(planJdbc).update(argThat(sql -> normalize(sql)
                        .startsWith("DELETE FROM AGENT_V2_STEP_COMPLETION_EVIDENCE")),
                any(SqlParameterSource.class));
    }

    @Test
    void rejectsAnIncompleteLockedPlanSnapshotBeforeDeletingStableRows() {
        NamedParameterJdbcTemplate jdbc =
                mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "FROM AGENT_V2_CHAIN_PLAN_BINDINGS")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("plan-a", "plan-b"));
        when(jdbc.queryForList(argThat(sql -> normalize(sql).contains(
                        "FROM AGENT_V2_PLAN_BOOTSTRAPS")),
                any(SqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("plan-a"), List.of());

        ProductChainPersistenceException failure = assertThrows(
                ProductChainPersistenceException.class,
                () -> new ProductChainPlanDeletionTransactions(jdbc)
                        .deleteBoundPlans(7, 8));

        assertEquals("CHAIN_PLAN_DELETION_SNAPSHOT_MISMATCH",
                failure.code());
        verify(jdbc, never()).update(any(String.class),
                any(SqlParameterSource.class));
    }

    private static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim()
                .toUpperCase(java.util.Locale.ROOT);
    }

    private static void replaceStableStubs(Connection connection)
            throws Exception {
        execute(connection, """
                CREATE TABLE agent_sessions(
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL)
                """);
        execute(connection, "DROP TABLE agent_v2_effect_results");
        execute(connection, "DROP TABLE agent_v2_receipts");
        execute(connection, "DROP TABLE agent_v2_effect_intents");
        execute(connection, "DROP TABLE agent_v2_plan_bootstraps");
        execute(connection, """
                CREATE TABLE agent_v2_plan_bootstraps(
                  plan_id VARCHAR(128) PRIMARY KEY)
                """);
        execute(connection, """
                CREATE TABLE agent_v2_plan_leases(
                  plan_id VARCHAR(128) PRIMARY KEY,
                  FOREIGN KEY(plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id))
                """);
        execute(connection, stablePlanTable("agent_v2_execution_starts"));
        execute(connection, stablePlanTable("agent_v2_plan_execution_contexts"));
        execute(connection, """
                CREATE TABLE agent_v2_step_activations(
                  activation_event_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  FOREIGN KEY(plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_step_interruptions(
                  interruption_event_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  FOREIGN KEY(plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_effect_intents(
                  tool_call_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  FOREIGN KEY(plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_tool_call_claims(
                  tool_call_id VARCHAR(128) PRIMARY KEY)
                """);
        execute(connection, """
                CREATE TABLE agent_v2_receipts(
                  receipt_id VARCHAR(128) PRIMARY KEY,
                  tool_call_id VARCHAR(128) NOT NULL,
                  payload_sha256 CHAR(64) NOT NULL,
                  payload_json CLOB NOT NULL,
                  UNIQUE(receipt_id,tool_call_id),
                  FOREIGN KEY(tool_call_id) REFERENCES agent_v2_tool_call_claims(tool_call_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_effect_progress(
                  tool_call_id VARCHAR(128) PRIMARY KEY,
                  FOREIGN KEY(tool_call_id) REFERENCES agent_v2_effect_intents(tool_call_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_effect_results(
                  tool_call_id VARCHAR(128) PRIMARY KEY,
                  receipt_id VARCHAR(128) NOT NULL,
                  UNIQUE(tool_call_id,receipt_id),
                  FOREIGN KEY(tool_call_id) REFERENCES agent_v2_effect_intents(tool_call_id),
                  FOREIGN KEY(receipt_id) REFERENCES agent_v2_receipts(receipt_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_step_completions(
                  completion_event_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  activation_event_id VARCHAR(128) NOT NULL,
                  request_format_version INT NOT NULL,
                  result_format_version INT NOT NULL,
                  CONSTRAINT ck_agent_v2_step_completion_formats CHECK (
                    request_format_version=1 AND result_format_version=1),
                  FOREIGN KEY(plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id),
                  FOREIGN KEY(activation_event_id) REFERENCES agent_v2_step_activations(activation_event_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_step_completion_evidence(
                  evidence_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  completion_event_id VARCHAR(128) NOT NULL,
                  tool_call_id VARCHAR(128) NOT NULL,
                  FOREIGN KEY(completion_event_id) REFERENCES agent_v2_step_completions(completion_event_id),
                  FOREIGN KEY(tool_call_id) REFERENCES agent_v2_effect_results(tool_call_id))
                """);
        execute(connection, """
                CREATE TABLE agent_v2_effect_execution_claims(
                  claim_id VARCHAR(128) PRIMARY KEY,
                  plan_id VARCHAR(128) NOT NULL,
                  tool_call_id VARCHAR(128) NOT NULL,
                  FOREIGN KEY(tool_call_id) REFERENCES agent_v2_effect_intents(tool_call_id))
                """);
        execute(connection, stablePlanTable("agent_v2_active_step_replans"));
    }

    private static void seedOwnedAndUnrelatedRows(Connection connection)
            throws Exception {
        execute(connection, """
                INSERT INTO agent_sessions(id,user_id) VALUES (8,7)
                """);
        execute(connection, """
                INSERT INTO agent_v2_plan_bootstraps(plan_id)
                VALUES ('literature-plan')
                """);
        ChainMigrationTestSupport.seedCompleteTaskGraph(connection);
        for (String table : new String[]{
                "agent_v2_plan_leases", "agent_v2_execution_starts",
                "agent_v2_plan_execution_contexts",
                "agent_v2_active_step_replans"}) {
            execute(connection, "INSERT INTO " + table
                    + "(plan_id) VALUES ('plan-1')");
        }
        execute(connection, """
                INSERT INTO agent_v2_step_activations(activation_event_id,plan_id)
                VALUES ('activation-owned','plan-1')
                """);
        execute(connection, """
                INSERT INTO agent_v2_step_interruptions(interruption_event_id,plan_id)
                VALUES ('interruption-owned','plan-1')
                """);
        execute(connection, """
                INSERT INTO agent_v2_effect_intents(tool_call_id,plan_id)
                VALUES ('action-1','plan-1'),('call-literature','literature-plan')
                """);
        execute(connection, """
                INSERT INTO agent_v2_tool_call_claims(tool_call_id)
                VALUES ('action-1'),('call-literature')
                """);
        execute(connection, """
                INSERT INTO agent_v2_receipts(
                  receipt_id,tool_call_id,payload_sha256,payload_json)
                VALUES ('receipt-owned','action-1',REPEAT('0',64),'{}')
                """);
        execute(connection, """
                INSERT INTO agent_v2_effect_progress(tool_call_id)
                VALUES ('action-1')
                """);
        execute(connection, """
                INSERT INTO agent_v2_effect_results(tool_call_id,receipt_id)
                VALUES ('action-1','receipt-owned')
                """);
        execute(connection, """
                INSERT INTO agent_v2_step_completions(
                  completion_event_id,plan_id,activation_event_id,
                  request_format_version,result_format_version)
                VALUES ('completion-owned','plan-1','activation-owned',1,1)
                """);
        execute(connection, """
                INSERT INTO agent_v2_step_completion_evidence(
                  evidence_id,plan_id,completion_event_id,tool_call_id)
                VALUES ('evidence-owned','plan-1','completion-owned','action-1')
                """);
        execute(connection, """
                INSERT INTO agent_v2_effect_execution_claims(claim_id,plan_id,tool_call_id)
                VALUES ('claim-owned','plan-1','action-1')
                """);
        ChainMigrationTestSupport.authorityEvent(
                connection.createStatement(), "event-validation", 21,
                "VALIDATION");
        execute(connection, """
                INSERT INTO agent_v2_chain_validation_sets(
                  validation_id,task_id,event_id,task_frame_id,plan_id,
                  plan_revision_id,plan_revision_number,step_id,
                  activation_event_id,request_digest,receipt_set_digest,
                  conclusion_digest,conclusion,idempotency_key,created_at)
                VALUES ('validation-1','task-1','event-validation',
                  'task-frame-1','plan-1','revision-2',2,'step-1',
                  'activation-1',REPEAT('0',64),REPEAT('0',64),
                  REPEAT('0',64),'PASSED','validation-key',CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO agent_v2_chain_candidate_validation_items(
                  validation_id,requirement_id,task_id,requirement_digest,
                  candidate_action_id,validation_action_id,receipt_id,
                  receipt_payload_sha256,action_signature_sha256,
                  workspace_candidate_id,workspace_id,artifact_id,
                  candidate_fingerprint,base_project_version,conclusion)
                VALUES ('validation-1','candidate-requirement','task-1',
                  REPEAT('0',64),'action-1','action-1','receipt-owned',
                  REPEAT('0',64),REPEAT('0',64),'workspace-candidate-1',
                  'workspace-1',501,REPEAT('0',64),'project-v1','PASSED')
                """);
        execute(connection, """
                INSERT INTO agent_v2_chain_action_receipt_validation_items(
                  validation_id,requirement_id,task_id,requirement_digest,
                  action_id,receipt_id,receipt_payload_sha256,
                  action_signature_sha256,conclusion)
                VALUES ('validation-1','action-requirement','task-1',
                  REPEAT('0',64),'action-1','receipt-owned',REPEAT('0',64),
                  REPEAT('0',64),'PASSED')
                """);
        ChainMigrationTestSupport.authorityEvent(
                connection.createStatement(), "event-bundle", 22,
                "VALIDATION_BUNDLE");
        execute(connection, """
                INSERT INTO agent_v2_chain_validation_bundles(
                  validation_bundle_id,task_id,event_id,task_frame_id,plan_id,
                  plan_revision_id,plan_revision_number,instruction_id,
                  final_step_id,request_digest,receipt_set_digest,
                  conclusion_digest,conclusion,idempotency_key,created_at)
                VALUES ('bundle-1','task-1','event-bundle','task-frame-1',
                  'plan-1','revision-2',2,'instruction-1','step-1',
                  REPEAT('0',64),REPEAT('0',64),REPEAT('0',64),'PASSED',
                  'bundle-key',CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO agent_v2_chain_validation_bundle_sets(
                  validation_bundle_id,task_id,step_id,activation_event_id,
                  validation_id,validation_request_digest,
                  validation_receipt_set_digest,validation_conclusion_digest)
                VALUES ('bundle-1','task-1','step-1','activation-1',
                  'validation-1',REPEAT('0',64),REPEAT('0',64),REPEAT('0',64))
                """);
        execute(connection, """
                UPDATE agent_v2_chain_finalization_readiness
                   SET publish_requirement='NOT_REQUIRED'
                 WHERE readiness_id='readiness-1'
                """);
        execute(connection, """
                UPDATE agent_v2_chain_task_outcomes
                   SET finalization_readiness_id='readiness-1',
                       finalization_check_id='check-1',
                       validation_request_digest=REPEAT('0',64),
                       validation_receipt_digest=REPEAT('0',64),
                       publish_requirement='NOT_REQUIRED',
                       publish_requirement_digest=REPEAT('0',64)
                 WHERE outcome_id='outcome-1'
                """);

        execute(connection, """
                INSERT INTO agent_v2_chain_commands(
                  command_id,user_id,session_id,client_request_id,command_kind,
                  request_sha256,status,created_at)
                VALUES ('other-command',99,8,'other-request','INITIAL',
                  REPEAT('0',64),'RECEIVED',CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO agent_v2_chain_tasks(
                  task_id,created_by_command_id,source_instruction_id,user_id,
                  session_id,turn_id,root_client_request_id,root_request_sha256,
                  next_event_sequence,created_at)
                VALUES ('other-task','other-command','other-instruction',99,8,90,
                  'other-request',REPEAT('0',64),0,CURRENT_TIMESTAMP)
                """);
        execute(connection, """
                INSERT INTO agent_v2_chain_instructions(
                  instruction_id,command_id,session_id,origin_task_id,message_id,
                  body_sha256,message_identity_key,relation_kind,
                  effective_boundary_digest,created_at)
                VALUES ('other-instruction','other-command',8,'other-task',91,
                  REPEAT('0',64),'MESSAGE:91','INITIAL',REPEAT('0',64),
                  CURRENT_TIMESTAMP)
                """);
    }

    private static void seedDeepContextHistory(
            Connection connection, int depth) throws Exception {
        String parent = "context-1";
        for (int index = 2; index <= depth; index++) {
            String context = "context-" + index;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,parent_context_revision_id,
                      role,work_state,call_reason,instruction_id,
                      projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES (?,'task-1',?,'EXECUTOR','EXECUTING','STEP_EXECUTION',
                      'instruction-1','projectors-v1','pagination-v1',
                      'chain-runtime-policy-v1','COMPLETE',13,1,'{}',
                      REPEAT('0',64),?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """)) {
                statement.setString(1, context);
                statement.setString(2, parent);
                statement.setString(3, "completion-" + index);
                statement.executeUpdate();
            }
            parent = context;
        }
    }

    private static String stablePlanTable(String name) {
        return "CREATE TABLE " + name + "(plan_id VARCHAR(128) PRIMARY KEY,"
                + " FOREIGN KEY(plan_id) REFERENCES agent_v2_plan_bootstraps(plan_id))";
    }

    private static void execute(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static void assertCount(
            Connection connection, String table, long expected)
            throws Exception {
        assertEquals(expected,
                scalar(connection, "SELECT COUNT(*) FROM " + table), table);
    }
}
