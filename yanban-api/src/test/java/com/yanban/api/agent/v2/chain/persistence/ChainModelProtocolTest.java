package com.yanban.api.agent.v2.chain.persistence;

import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalCurrentFence;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContentRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelInvocationRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProviderAttemptRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ValidationStatus;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainProposalStateWriter;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService.AdmissionRequest;
import io.paperagent.v2.chain.model.ChainProposalCurrentFence;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real H2 transaction checks for the product stage-3 model boundary. */
class ChainModelProtocolTest {
    private static final Instant NOW =
            Instant.parse("2026-08-07T03:00:00Z");
    private static final String HASH = "0".repeat(64);

    @Test
    void failedProposalWriteRollsBackAttemptAndContentAndWinnerReplays()
            throws Exception {
        try (Harness harness = Harness.create("model-materialization")) {
            harness.seedInvocation("context-failed", "invocation-failed", 1);
            ModelProposalRecord existing = proposal(
                    "proposal-conflict", "invocation-failed", null, null,
                    "{\"route\":\"existing\"}");
            harness.model().appendProposal(existing);
            ProviderAttemptRecord attempt = attempt("invocation-failed");
            ContentRecord content = content(
                    "content-failed", "invocation-failed");
            ModelProposalRecord conflicting = proposal(
                    "proposal-conflict", "invocation-failed",
                    ChainContentKind.ANSWER_BODY.name(),
                    content.contentId(),
                    "{\"answerBodyRef\":\"content-failed\"}");
            ProductChainModelMaterializationAdapter materialization =
                    harness.materialization();

            assertThatThrownBy(() -> materialization.persistSuccessfulAttempt(
                    attempt, content, conflicting))
                    .isInstanceOf(ProductChainPersistenceException.class);
            assertThat(harness.model().findProviderAttempts(
                    "invocation-failed")).isEmpty();
            assertThat(harness.model().findContent(
                    "content-failed")).isEmpty();

            harness.seedInvocation("context-winner", "invocation-winner", 2);
            ProviderAttemptRecord winnerAttempt = attempt("invocation-winner");
            ContentRecord winnerContent = content(
                    "content-winner", "invocation-winner");
            ModelProposalRecord winnerProposal = proposal(
                    "proposal-winner", "invocation-winner",
                    ChainContentKind.ANSWER_BODY.name(),
                    winnerContent.contentId(),
                    "{\"answerBodyRef\":\"content-winner\"}");

            assertThat(materialization.persistSuccessfulAttempt(
                    winnerAttempt, winnerContent, winnerProposal).replayed())
                    .isFalse();
            assertThat(materialization.persistSuccessfulAttempt(
                    winnerAttempt, winnerContent, winnerProposal).replayed())
                    .isTrue();
            assertThat(harness.model().findProviderAttempts(
                    "invocation-winner")).hasSize(1);
            assertThat(harness.model().findContents(
                    "invocation-winner")).hasSize(1);
            assertThat(harness.model().findProposalByInvocation(
                    "invocation-winner")).contains(winnerProposal);
        }
    }

    @Test
    void admissionFenceAndAuthorityAppendRollbackTogether()
            throws Exception {
        try (Harness harness = Harness.create("proposal-admission")) {
            harness.seedInvocation("context-admission", "invocation-admission", 1);
            ModelProposalRecord proposal = proposal(
                    "proposal-admission", "invocation-admission",
                    null, null, "{\"route\":\"direct\"}");
            harness.model().appendProposal(proposal);
            ChainProposalStateWriter failAfterAppend = event -> {
                harness.model().appendProposalState(event);
                throw new IllegalStateException("fail after authority append");
            };
            ProductChainProposalAdmissionAdapter failing =
                    new ProductChainProposalAdmissionAdapter(
                            harness.jdbc(), harness.transactions(),
                            harness.model(), failAfterAppend);
            AdmissionRequest request = new AdmissionRequest(
                    proposal.proposalId(), "task-1", "event-admission",
                    true, null, HASH, NOW);

            assertThatThrownBy(() -> failing.admit(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fail after authority append");
            assertThat(harness.model().findProposalStateEvents(
                    proposal.proposalId())).isEmpty();
            assertThat(harness.count(
                    "agent_v2_chain_authority_events",
                    "event_id", "event-admission")).isZero();
            assertThat(harness.taskEventSequence()).isEqualTo(1L);

            ProductChainProposalAdmissionAdapter admission =
                    new ProductChainProposalAdmissionAdapter(
                            harness.jdbc(), harness.transactions(),
                            harness.model(), harness.model());
            var accepted = admission.admit(request);

            assertThat(accepted.executable()).isTrue();
            assertThat(accepted.state().stateKind())
                    .isEqualTo(ChainProposalState.ACCEPTED);
            assertThat(harness.taskEventSequence()).isEqualTo(2L);
        }
    }

    @Test
    void currentFenceAllowsAnswerDeliveryAfterTaskOutcomeButRejectsPlanner()
            throws Exception {
        try (Harness answer = Harness.create("answer-after-outcome")) {
            answer.seedTaskOutcome();
            answer.seedInvocation(
                    "context-answer", "invocation-answer", 1,
                    ChainRole.ANSWER, ChainWorkState.DELIVERING,
                    "FINAL_DELIVERY");
            ProductChainProposalCurrentFence fence =
                    new ProductChainProposalCurrentFence(answer.jdbc());

            assertThat(fence.isCurrent(new ChainProposalCurrentFence.Check(
                    "task-1", "invocation-answer", "context-answer",
                    ChainRole.ANSWER, ChainWorkState.DELIVERING))).isTrue();
        }
        try (Harness planner = Harness.create("planner-after-outcome")) {
            planner.seedTaskOutcome();
            planner.seedInvocation(
                    "context-planner", "invocation-planner", 1,
                    ChainRole.PLANNER, ChainWorkState.PLANNING,
                    "INITIAL");
            ProductChainProposalCurrentFence fence =
                    new ProductChainProposalCurrentFence(planner.jdbc());

            assertThat(fence.isCurrent(new ChainProposalCurrentFence.Check(
                    "task-1", "invocation-planner", "context-planner",
                    ChainRole.PLANNER, ChainWorkState.PLANNING))).isFalse();
        }
    }

    private static ProviderAttemptRecord attempt(String invocationId) {
        return new ProviderAttemptRecord(
                invocationId, 1, "task-1", 12L, "stop",
                ValidationStatus.PASSED, ValidationStatus.PASSED,
                null, NOW);
    }

    private static ContentRecord content(
            String contentId, String invocationId) {
        String body = "authoritative body for " + contentId;
        return new ContentRecord(
                contentId, "task-1", invocationId,
                ChainContentKind.ANSWER_BODY, body,
                ProductChainRecordCodec.sha256(body),
                "text/plain", NOW);
    }

    private static ModelProposalRecord proposal(
            String proposalId,
            String invocationId,
            String bodyAuthorityType,
            String bodyAuthorityRef,
            String payload) {
        return new ModelProposalRecord(
                proposalId, "task-1", invocationId, 1,
                ChainRole.PLANNER,
                ChainProposalKind.PLANNER_DIRECT_ROUTE,
                canonical(payload), canonical("{\"refs\":[]}"),
                bodyAuthorityType, bodyAuthorityRef, NOW);
    }

    private static CanonicalJson canonical(String json) {
        return new CanonicalJson(
                1, ProductChainRecordCodec.sha256(json), json);
    }

    private record Harness(
            Connection keeper,
            NamedParameterJdbcTemplate jdbc,
            DataSourceTransactionManager transactions,
            ProductChainModelRepositoryAdapter model)
            implements AutoCloseable {
        static Harness create(String label) throws Exception {
            Connection keeper = ChainMigrationTestSupport.database(label);
            ChainMigrationTestSupport.migrateThrough(keeper, 73);
            ChainMigrationTestSupport.seedFoundation(keeper);
            String url = keeper.getMetaData().getURL();
            DriverManagerDataSource dataSource =
                    new DriverManagerDataSource(url, "sa", "");
            NamedParameterJdbcTemplate jdbc =
                    new NamedParameterJdbcTemplate(dataSource);
            DataSourceTransactionManager transactions =
                    new DataSourceTransactionManager(dataSource);
            jdbc.update("""
                    UPDATE agent_v2_chain_tasks
                       SET next_event_sequence = 1
                     WHERE task_id = 'task-1'
                    """, new MapSqlParameterSource());
            ProductChainTransactions writes = new ProductChainTransactions(
                    jdbc, new ProductChainRecordCodec(), transactions,
                    () -> NOW);
            return new Harness(keeper, jdbc, transactions,
                    new ProductChainModelRepositoryAdapter(writes));
        }

        void seedInvocation(
                String contextId,
                String invocationId,
                int ordinal) {
            seedInvocation(contextId, invocationId, ordinal,
                    ChainRole.PLANNER, ChainWorkState.PLANNING, "INITIAL");
        }

        void seedInvocation(
                String contextId,
                String invocationId,
                int ordinal,
                ChainRole role,
                ChainWorkState workState,
                String callReason) {
            jdbc.update("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,role,work_state,call_reason,
                      instruction_id,projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES (:contextId,'task-1',:role,:workState,:callReason,
                      'instruction-1','projectors-v1','pagination-v1',
                      'chain-runtime-policy-v1','COMPLETE',13,1,'{}',
                      :digest,:completionToken,:createdAt,:createdAt)
                    """, new MapSqlParameterSource()
                    .addValue("contextId", contextId)
                    .addValue("role", role.name())
                    .addValue("workState", workState.name())
                    .addValue("callReason", callReason)
                    .addValue("digest", HASH)
                    .addValue("completionToken", "completion-" + contextId)
                    .addValue("createdAt", java.sql.Timestamp.from(
                            NOW.plusSeconds(ordinal))));
            model.appendInvocation(new ModelInvocationRecord(
                    invocationId, "task-1", contextId,
                    "completion-" + contextId,
                    role, workState,
                    callReason, "provider", "model", ordinal,
                    "chain-runtime-policy-v1", NOW.plusSeconds(ordinal)));
        }

        void seedTaskOutcome() {
            jdbc.update("""
                    INSERT INTO agent_v2_chain_authority_events(
                      event_id,task_id,event_sequence,event_type,
                      source_identity_sha256,committed_at)
                    VALUES ('event-outcome','task-1',2,'TASK_OUTCOME',
                      :digest,:createdAt)
                    """, new MapSqlParameterSource()
                    .addValue("digest", HASH)
                    .addValue("createdAt", java.sql.Timestamp.from(NOW)));
            jdbc.update("""
                    INSERT INTO agent_v2_chain_task_outcomes(
                      outcome_id,task_id,event_id,source_command_id,
                      outcome_type,instruction_id,coverage_format_version,
                      coverage_sha256,coverage_json,accepted_set_format_version,
                      accepted_set_sha256,accepted_set_json,candidate_key,
                      validation_id,incomplete_items_format_version,
                      incomplete_items_sha256,incomplete_items_json,
                      limitations_format_version,limitations_sha256,
                      limitations_json,risks_format_version,risks_sha256,
                      risks_json,source_decision_id,created_at)
                    VALUES ('outcome-1','task-1','event-outcome','command-1',
                      'COMPLETED','instruction-1',1,:digest,'{}',1,:digest,'{}',
                      'NONE','NONE',1,:digest,'{}',1,:digest,'{}',1,:digest,'{}',
                      'decision-outcome',:createdAt)
                    """, new MapSqlParameterSource()
                    .addValue("digest", HASH)
                    .addValue("createdAt", java.sql.Timestamp.from(NOW)));
        }

        ProductChainModelMaterializationAdapter materialization() {
            return new ProductChainModelMaterializationAdapter(
                    model, model, model, transactions);
        }

        long count(String table, String column, String value) {
            Long result = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table
                            + " WHERE " + column + " = :value",
                    new MapSqlParameterSource("value", value), Long.class);
            return result == null ? 0 : result;
        }

        long taskEventSequence() {
            Long result = jdbc.queryForObject("""
                    SELECT next_event_sequence
                      FROM agent_v2_chain_tasks
                     WHERE task_id = 'task-1'
                    """, new MapSqlParameterSource(), Long.class);
            return result == null ? -1 : result;
        }

        @Override
        public void close() throws Exception {
            keeper.close();
        }
    }
}
