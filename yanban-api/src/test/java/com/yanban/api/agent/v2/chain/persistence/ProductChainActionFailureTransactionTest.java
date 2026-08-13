package com.yanban.api.agent.v2.chain.persistence;

import com.yanban.api.agent.v2.chain.context.ProductChainExecutorActionContextProjection;
import com.yanban.api.agent.v2.chain.progression.ProductChainActionFailureProgression;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.step.ChainActionProgressIdentity;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.persistence.PersistedEffectResult;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainActionFailureTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final Instant PERSISTED_AT = NOW.plusSeconds(10);
    private static final String FAILED_SIGNATURE = "b".repeat(64);
    private static final String REPAIR_SIGNATURE = sha256("{}");

    @Test
    void repeatedCandidateNoChangeBlocksOnceAndReplaysWithoutReceipt()
            throws Exception {
        try (Connection keeper = ChainMigrationTestSupport.database(
                "candidate-no-progress")) {
            ChainMigrationTestSupport.migrateThrough(keeper, 80);
            ChainMigrationTestSupport.seedFoundation(keeper);
            ChainMigrationTestSupport.seedProposal(keeper);
            seedCandidateNoProgressGraph(keeper);

            String url = keeper.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var manager = new DataSourceTransactionManager(dataSource);
            var transactions = new ProductChainTransactions(
                    jdbc, new ProductChainRecordCodec(), manager,
                    () -> PERSISTED_AT);
            var foundations = new ProductChainFoundationRepositoryAdapter(
                    transactions, () -> NOW);
            var workflow = new ProductChainWorkflowRepositoryAdapter(
                    transactions);
            var models = new ProductChainModelRepositoryAdapter(transactions);
            EffectOutcomeRepository outcomes = mock(
                    EffectOutcomeRepository.class);
            when(outcomes.findResult(any(ToolCallId.class))).thenReturn(
                    PersistenceResult.rejected(
                            PersistenceErrorCode.NOT_FOUND, "action"));
            var candidateFailures =
                    new ProductChainCandidateMaterializationFailureRepositoryAdapter(
                            transactions);
            var subject = new ProductChainActionFailureProgression(
                    foundations, workflow, outcomes, jdbc, models,
                    candidateFailures, manager);
            var task = foundations.findTask("task-1").orElseThrow();
            var plan = workflow.findPlanBindings("task-1").get(0);
            var activation = new ChainStepAuthorityPort.StepEvent(
                    new ChainStepAuthorityPort.StepEventCommand(
                            "activation-1", "task-1", "revision-1", "step-1",
                            "activation-1",
                            ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                            "decision-1", "transition-1", NOW), 1L);
            var ready = new ChainModelProtocolOutcome.ProposalReady(
                    models.findProposal("proposal-repair").orElseThrow(),
                    null, 1, false);
            var failure = new ProductChainExecutorActionContextProjection
                    .Failure("action-3", "candidate-failure-3", "proposal-1",
                    null, "CANDIDATE_NO_ACTUAL_CHANGE");

            var first = subject.decide(task, plan, activation, ready,
                    failure, null, NOW);
            assertTrue(first.blocked());
            var block = workflow.findActionReceiptStepBlocks("task-1")
                    .get(0);
            assertEquals("CANDIDATE_MATERIALIZATION_FAILURE",
                    block.failureAuthorityType());
            assertEquals("candidate-failure-3",
                    block.failureAuthorityRef());
            assertNull(block.receiptId());
            assertNull(block.receiptPayloadSha256());
            assertNull(block.receiptStatus());
            assertEquals("CANDIDATE", block.failureCategory());
            assertEquals(3, block.thresholdObservedOccurrences());
            assertEquals("NO_PROGRESS_THRESHOLD_REACHED",
                    block.blockReasonCode());
            assertEquals(PERSISTED_AT, block.createdAt());

            var replay = subject.decide(task, plan, activation, ready,
                    failure, null, NOW.plusSeconds(1));
            assertEquals(first, replay);
            assertEquals(1, count(jdbc,
                    "agent_v2_chain_action_receipt_step_blocks"));
            assertEquals(2, countWhere(jdbc,
                    "agent_v2_chain_proposal_state_events",
                    "proposal_id", "proposal-repair"));
        }
    }

    @Test
    void replacementFailureRollsBackBlockAndProposalAdmission()
            throws Exception {
        try (Connection keeper = ChainMigrationTestSupport.database(
                "action-failure-transaction")) {
            ChainMigrationTestSupport.migrateThrough(keeper, 80);
            ChainMigrationTestSupport.seedFoundation(keeper);
            ChainMigrationTestSupport.seedProposal(keeper);

            String receiptJson = "{\"receiptId\":\"receipt-1\","
                    + "\"toolCallId\":\"action-1\",\"status\":\"FAILURE\","
                    + "\"resultCode\":\"TOOL_FAILED\"}";
            String receiptDigest = sha256(receiptJson);
            ExecutionReceipt receipt = new ExecutionReceipt(
                    new ReceiptId("receipt-1"), new ToolCallId("action-1"),
                    ReceiptStatus.FAILURE, NOW, NOW.plusSeconds(1),
                    Optional.of(1), Optional.of("TOOL_FAILED"),
                    OutputCapture.empty(), OutputCapture.empty(), List.of(),
                    Optional.empty(), List.of());
            String progressIdentity = ChainActionProgressIdentity.receipt(
                    FAILED_SIGNATURE, receipt);
            String progressDigest = sha256("4:" + progressIdentity);
            String blockIdentity = sha256(String.join("\0",
                    "action-1", "RECEIPT", "receipt-1", receiptDigest,
                    "proposal-repair", REPAIR_SIGNATURE, "context-repair",
                    "5", progressDigest, "1",
                    "REPAIR_DID_NOT_CHANGE_ACTION",
                    ChainRuntimePolicy.V1.policyVersion()));
            String replacementEvent = "action-receipt-step-block-bound."
                    + blockIdentity;

            seedGraph(keeper, receiptJson, receiptDigest, replacementEvent);

            String url = keeper.getMetaData().getURL();
            var dataSource = new DriverManagerDataSource(url, "sa", "");
            var jdbc = new NamedParameterJdbcTemplate(dataSource);
            var manager = new DataSourceTransactionManager(dataSource);
            var transactions = new ProductChainTransactions(
                    jdbc, new ProductChainRecordCodec(), manager, () -> NOW);
            var foundations = new ProductChainFoundationRepositoryAdapter(
                    transactions, () -> NOW);
            var workflow = new ProductChainWorkflowRepositoryAdapter(
                    transactions);
            var models = new ProductChainModelRepositoryAdapter(transactions);
            EffectOutcomeRepository outcomes = mock(
                    EffectOutcomeRepository.class);
            when(outcomes.findResult(new ToolCallId("action-1"))).thenReturn(
                    PersistenceResult.found(new PersistedEffectResult(
                            receipt, "lease-1", 1L)));
            var subject = new ProductChainActionFailureProgression(
                    foundations, workflow, outcomes, jdbc, models,
                    mock(ProductChainCandidateMaterializationFailureRepositoryAdapter.class),
                    manager);
            var task = foundations.findTask("task-1").orElseThrow();
            var plan = workflow.findPlanBindings("task-1").get(0);
            var activation = new ChainStepAuthorityPort.StepEvent(
                    new ChainStepAuthorityPort.StepEventCommand(
                            "activation-1", "task-1", "revision-1", "step-1",
                            "activation-1",
                            ChainStepAuthorityPort.StepEventKind.ACTIVATED,
                            "decision-1", "transition-1", NOW), 1L);
            var ready = new ChainModelProtocolOutcome.ProposalReady(
                    models.findProposal("proposal-repair").orElseThrow(),
                    null, 1, false);
            var failure = new ProductChainExecutorActionContextProjection
                    .Failure("action-1", "receipt-1", "proposal-1",
                    ReceiptStatus.FAILURE, "TOOL_FAILED");

            ProductChainPersistenceException failureDuringReplacement =
                    assertThrows(ProductChainPersistenceException.class,
                            () -> subject.decide(
                    task, plan, activation, ready, failure,
                    "REPAIR_DID_NOT_CHANGE_ACTION", NOW));
            assertEquals("CHAIN_CONFLICTING_REPLAY",
                    failureDuringReplacement.code());

            assertEquals(0, count(jdbc,
                    "agent_v2_chain_action_receipt_step_blocks"));
            assertEquals(0, countWhere(jdbc,
                    "agent_v2_chain_proposal_state_events",
                    "proposal_id", "proposal-repair"));
            assertEquals(5, count(jdbc, "agent_v2_chain_authority_events"));
        }
    }

    private static void seedGraph(
            Connection connection,
            String receiptJson,
            String receiptDigest,
            String replacementEvent) throws Exception {
        try (var statement = connection.createStatement()) {
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-route-1", 2L, "ROUTE_DECISION");
            statement.execute("""
                    INSERT INTO agent_v2_chain_route_decisions(
                      route_decision_id,task_id,event_id,instruction_id,
                      proposal_id,decision_kind,decision_ordinal,route,
                      route_reason,needs_tool,needs_network,needs_project,
                      needs_persistent_progress,created_at)
                    VALUES ('route-1','task-1','event-route-1','instruction-1',
                      'proposal-1','INITIAL',0,'PERSISTENT_PLAN_EXECUTE',
                      'test',1,0,1,1,CURRENT_TIMESTAMP)
                    """);
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-plan-1", 3L, "PLAN_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_plan_bindings(
                      plan_binding_id,task_id,event_id,instruction_id,
                      route_decision_id,task_frame_id,plan_id,plan_revision_id,
                      plan_revision_number,authority_type,authority_id,
                      authority_sha256,created_at)
                    VALUES ('plan-binding-1','task-1','event-plan-1',
                      'instruction-1','route-1','frame-1','plan-1','revision-1',
                      1,'PLANNER_PROPOSAL','proposal-1',REPEAT('3',64),
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,role,work_state,call_reason,
                      instruction_id,task_frame_id,plan_id,plan_revision_id,
                      plan_revision_number,step_id,activation_event_id,
                      workspace_id,projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES ('context-repair','task-1','EXECUTOR','EXECUTING',
                      'STEP_EXECUTION','instruction-1','frame-1','plan-1',
                      'revision-1',1,'step-1','activation-1','workspace-1',
                      'projectors-v1','pagination-v1','chain-runtime-policy-v1',
                      'COMPLETE',13,1,'{}',REPEAT('4',64),'completion-repair',
                      CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_invocations(
                      invocation_id,task_id,context_revision_id,completion_token,
                      role,work_state,call_reason,provider,model,
                      invocation_ordinal,runtime_policy_version,created_at)
                    VALUES ('invocation-repair','task-1','context-repair',
                      'completion-repair','EXECUTOR','EXECUTING','STEP_EXECUTION',
                      'fake','fake',2,'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_proposals(
                      proposal_id,task_id,invocation_id,schema_version,role,
                      proposal_kind,payload_format_version,payload_sha256,
                      payload_json,source_refs_format_version,source_refs_sha256,
                      source_refs_json,created_at)
                    VALUES ('proposal-repair','task-1','invocation-repair',1,
                      'EXECUTOR','TOOL_ACTION',1,'%s','{}',1,
                      '%s','{}',CURRENT_TIMESTAMP)
                    """.formatted(REPAIR_SIGNATURE, sha256("{}")));
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-action-1", 4L, "ACTION_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_action_bindings(
                      action_id,task_id,event_id,proposal_id,attempt_no,
                      action_signature_sha256,idempotency_key,instruction_id,
                      task_frame_id,plan_id,plan_revision_id,step_id,
                      activation_event_id,workspace_id,base_candidate_key,
                      version_fence_sha256,created_at)
                    VALUES ('action-1','task-1','event-action-1','proposal-1',1,
                      REPEAT('b',64),'action-key-1','instruction-1','frame-1',
                      'plan-1','revision-1','step-1','activation-1','workspace-1',
                      'NONE',REPEAT('6',64),CURRENT_TIMESTAMP)
                    """);
            statement.execute("INSERT INTO agent_v2_receipts"
                    + "(receipt_id,tool_call_id,payload_sha256,payload_json) "
                    + "VALUES ('receipt-1','action-1','" + receiptDigest
                    + "','" + receiptJson + "')");
            statement.execute("""
                    INSERT INTO agent_v2_effect_results(tool_call_id,receipt_id)
                    VALUES ('action-1','receipt-1')
                    """);
            ChainMigrationTestSupport.authorityEvent(
                    statement, replacementEvent, 5L, "TEST_CONFLICT");
            statement.execute("UPDATE agent_v2_chain_tasks "
                    + "SET next_event_sequence = 5 "
                    + "WHERE task_id = 'task-1'");
        }
    }

    private static void seedCandidateNoProgressGraph(Connection connection)
            throws Exception {
        try (var statement = connection.createStatement()) {
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-route-1", 2L, "ROUTE_DECISION");
            statement.execute("""
                    INSERT INTO agent_v2_chain_route_decisions(
                      route_decision_id,task_id,event_id,instruction_id,
                      proposal_id,decision_kind,decision_ordinal,route,
                      route_reason,needs_tool,needs_network,needs_project,
                      needs_persistent_progress,created_at)
                    VALUES ('route-1','task-1','event-route-1','instruction-1',
                      'proposal-1','INITIAL',0,'PERSISTENT_PLAN_EXECUTE',
                      'test',1,0,1,1,CURRENT_TIMESTAMP)
                    """);
            ChainMigrationTestSupport.authorityEvent(
                    statement, "event-plan-1", 3L, "PLAN_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_plan_bindings(
                      plan_binding_id,task_id,event_id,instruction_id,
                      route_decision_id,task_frame_id,plan_id,plan_revision_id,
                      plan_revision_number,authority_type,authority_id,
                      authority_sha256,created_at)
                    VALUES ('plan-binding-1','task-1','event-plan-1',
                      'instruction-1','route-1','frame-1','plan-1','revision-1',
                      1,'PLANNER_PROPOSAL','proposal-1',REPEAT('3',64),
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,role,work_state,call_reason,
                      instruction_id,task_frame_id,plan_id,plan_revision_id,
                      plan_revision_number,step_id,activation_event_id,
                      workspace_id,projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES ('context-repair','task-1','EXECUTOR','EXECUTING',
                      'STEP_EXECUTION','instruction-1','frame-1','plan-1',
                      'revision-1',1,'step-1','activation-1','workspace-1',
                      'projectors-v1','pagination-v1','chain-runtime-policy-v1',
                      'COMPLETE',13,1,'{}',REPEAT('4',64),'completion-repair',
                      CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_invocations(
                      invocation_id,task_id,context_revision_id,completion_token,
                      role,work_state,call_reason,provider,model,
                      invocation_ordinal,runtime_policy_version,created_at)
                    VALUES ('invocation-repair','task-1','context-repair',
                      'completion-repair','EXECUTOR','EXECUTING','STEP_EXECUTION',
                      'fake','fake',2,'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_proposals(
                      proposal_id,task_id,invocation_id,schema_version,role,
                      proposal_kind,payload_format_version,payload_sha256,
                      payload_json,source_refs_format_version,source_refs_sha256,
                      source_refs_json,created_at)
                    VALUES ('proposal-repair','task-1','invocation-repair',1,
                      'EXECUTOR','WORKSPACE_CHANGE',1,'%s','{}',1,
                      '%s','{}',CURRENT_TIMESTAMP)
                    """.formatted(REPAIR_SIGNATURE, sha256("{}")));
            for (int attempt = 1; attempt <= 3; attempt++) {
                long actionSequence = 2L + attempt * 2L;
                long failureSequence = actionSequence + 1L;
                String actionId = "action-" + attempt;
                String actionEvent = "event-action-" + attempt;
                String failureId = "candidate-failure-" + attempt;
                String failureEvent = "event-candidate-failure-" + attempt;
                ChainMigrationTestSupport.authorityEvent(
                        statement, actionEvent, actionSequence, "ACTION_BOUND");
                statement.execute("""
                        INSERT INTO agent_v2_chain_action_bindings(
                          action_id,task_id,event_id,proposal_id,attempt_no,
                          action_signature_sha256,idempotency_key,instruction_id,
                          task_frame_id,plan_id,plan_revision_id,step_id,
                          activation_event_id,workspace_id,base_candidate_key,
                          version_fence_sha256,created_at)
                        VALUES ('%s','task-1','%s','proposal-1',%d,
                          REPEAT('b',64),'action-key-%d','instruction-1','frame-1',
                          'plan-1','revision-1','step-1','activation-1',
                          'workspace-1','NONE',REPEAT('6',64),CURRENT_TIMESTAMP)
                        """.formatted(actionId, actionEvent, attempt, attempt));
                ChainMigrationTestSupport.authorityEvent(statement,
                        failureEvent, failureSequence,
                        "CANDIDATE_MATERIALIZATION_FAILURE");
                statement.execute("""
                        INSERT INTO agent_v2_chain_candidate_materialization_failures(
                          candidate_failure_id,task_id,event_id,action_id,
                          workspace_id,base_candidate_key,
                          mutation_authority_type,mutation_authority_ref,
                          version_fence_sha256,error_code,created_at)
                        VALUES ('%s','task-1','%s','%s','workspace-1','NONE',
                          'WORKSPACE_CHANGE_BODY','mutation-%d',REPEAT('6',64),
                          'CANDIDATE_NO_ACTUAL_CHANGE',CURRENT_TIMESTAMP)
                        """.formatted(failureId, failureEvent, actionId,
                        attempt));
            }
            statement.execute("UPDATE agent_v2_chain_tasks "
                    + "SET next_event_sequence = 9 WHERE task_id = 'task-1'");
        }
    }

    private static int count(
            NamedParameterJdbcTemplate jdbc, String table) {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static int countWhere(
            NamedParameterJdbcTemplate jdbc,
            String table,
            String column,
            String value) {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
