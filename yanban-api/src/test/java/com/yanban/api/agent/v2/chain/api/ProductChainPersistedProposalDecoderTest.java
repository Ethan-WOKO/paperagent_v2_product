package com.yanban.api.agent.v2.chain.api;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductChainPersistedProposalDecoderTest {
    private static final String CONTENT_ID = "content.body";
    private static final String TASK_ID = "task.1";
    private static final String INVOCATION_ID = "invocation.1";

    @Test
    void restoresCandidateStepResultBodyFromItsExactAuthority() {
        String payload = "{\"artifactRefs\":[],\"candidateRef\":null,"
                + "\"candidateResultBodyRef\":\"" + CONTENT_ID + "\","
                + "\"completionConditionStatus\":[{\"factRefs\":[\"fact.1\"],"
                + "\"requirement\":\"done\",\"status\":\"SATISFIED\"}],"
                + "\"evidenceRefs\":[],\"gapValidation\":null,"
                + "\"receiptRefs\":[],\"unmetConditions\":[],"
                + "\"validationRefs\":[],\"validationSources\":[]}";

        var decoded = ProductChainPersistedProposalDecoder.decode(
                ready(ChainProposalKind.EXECUTOR_STEP_RESULT,
                        ChainRole.EXECUTOR,
                        ChainContentKind.CANDIDATE_STEP_RESULT,
                        payload, "completed"),
                ChainWorkState.EXECUTING, null);

        assertThat(decoded.payload()).isInstanceOf(ExecutorPayload.StepResult.class);
        assertThat(((ExecutorPayload.StepResult) decoded.payload())
                .inlineCandidateResultBody()).isEqualTo("completed");
    }

    @Test
    void restoresWorkspaceChangeBodyWithoutScenarioSpecificRules() {
        String payload = "{\"baseCandidateRef\":\"candidate.1\","
                + "\"completionConditions\":[\"updated\"],"
                + "\"gapValidation\":null,\"manifestChanges\":[],"
                + "\"reason\":\"apply requested change\","
                + "\"targetFiles\":[\"src/A.java\"],"
                + "\"workspaceChangeBodyRef\":\"" + CONTENT_ID + "\"}";

        var decoded = ProductChainPersistedProposalDecoder.decode(
                ready(ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE,
                        ChainRole.EXECUTOR,
                        ChainContentKind.WORKSPACE_CHANGE_BODY,
                        payload, "{\"changes\":[{"
                                + "\"expectedBaselineSha256\":\"NONE\","
                                + "\"path\":\"src/A.java\","
                                + "\"text\":\"class A {}\","
                                + "\"type\":\"ADD\"}]}"),
                ChainWorkState.EXECUTING, null);

        assertThat(decoded.payload()).isInstanceOf(ExecutorPayload.WorkspaceChange.class);
        assertThat(((ExecutorPayload.WorkspaceChange) decoded.payload())
                .inlineCanonicalChangeBody()).contains("class A {}");
    }

    @Test
    void restoresPlannerDirectAnswerBodyFromItsExactAuthority() {
        String payload = "{\"answerBodyRef\":\"" + CONTENT_ID + "\","
                + "\"answerRequiredRefs\":[],"
                + "\"directTaskSpecification\":\"解释 LaTeX 交叉引用\","
                + "\"gapValidation\":null,\"needsNetwork\":false,"
                + "\"needsPersistentProgress\":false,"
                + "\"needsProject\":false,\"needsTool\":false,"
                + "\"routeReason\":\"普通知识问题\","
                + "\"userConstraints\":[]}";

        var decoded = ProductChainPersistedProposalDecoder.decode(
                ready(ChainProposalKind.PLANNER_DIRECT_ROUTE,
                        ChainRole.PLANNER, ChainContentKind.ANSWER_BODY,
                        payload, "LaTeX 使用 label 和 ref 完成交叉引用。"),
                ChainWorkState.PLANNING, null);

        assertThat(decoded.payload())
                .isInstanceOf(PlannerPayload.DirectRoute.class);
        assertThat(((PlannerPayload.DirectRoute) decoded.payload())
                .inlineAnswerBody())
                .isEqualTo("LaTeX 使用 label 和 ref 完成交叉引用。");
    }

    @Test
    void restoresAnswerBodyFromItsExactAuthority() {
        String payload = "{\"answerBodyRef\":\"" + CONTENT_ID + "\","
                + "\"artifactAndCandidateRefs\":[\"candidate.1\"],"
                + "\"publishRef\":\"publish.1\","
                + "\"taskOutcomeRef\":\"outcome.1\","
                + "\"validationRef\":\"validation.1\"}";

        var decoded = ProductChainPersistedProposalDecoder.decode(
                ready(ChainProposalKind.ANSWER_FINAL_DELIVERY,
                        ChainRole.ANSWER, ChainContentKind.ANSWER_BODY,
                        payload, "finished"),
                ChainWorkState.DELIVERING, null);

        assertThat(decoded.payload()).isInstanceOf(AnswerPayload.FinalDelivery.class);
        assertThat(((AnswerPayload.FinalDelivery) decoded.payload())
                .inlineAnswerBody()).isEqualTo("finished");
    }

    @Test
    void rejectsAContentAuthorityFromAnotherInvocation() {
        var ready = ready(ChainProposalKind.EXECUTOR_STEP_RESULT,
                ChainRole.EXECUTOR, ChainContentKind.CANDIDATE_STEP_RESULT,
                "{\"candidateResultBodyRef\":\"" + CONTENT_ID + "\"}",
                "completed");
        var foreign = new ChainPersistenceRecords.ContentRecord(
                CONTENT_ID, TASK_ID, "invocation.other",
                ChainContentKind.CANDIDATE_STEP_RESULT, "completed",
                sha256("completed"), "text/plain", Instant.EPOCH);

        assertThatThrownBy(() -> ProductChainPersistedProposalDecoder.decode(
                new ChainModelProtocolOutcome.ProposalReady(
                        ready.proposal(), foreign, 1, true),
                ChainWorkState.EXECUTING, null))
                .hasMessage("CHAIN_PROPOSAL_BODY_AUTHORITY_INVALID");
    }

    private static ChainModelProtocolOutcome.ProposalReady ready(
            ChainProposalKind kind,
            ChainRole role,
            ChainContentKind contentKind,
            String payload,
            String body) {
        var content = new ChainPersistenceRecords.ContentRecord(
                CONTENT_ID, TASK_ID, INVOCATION_ID, contentKind, body,
                sha256(body), "text/plain", Instant.EPOCH);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal.1", TASK_ID, INVOCATION_ID, 1, role, kind,
                new ChainPersistenceRecords.CanonicalJson(
                        1, sha256(payload), payload),
                new ChainPersistenceRecords.CanonicalJson(
                        1, sha256("[]"), "[]"),
                contentKind.name(), CONTENT_ID, Instant.EPOCH);
        return new ChainModelProtocolOutcome.ProposalReady(
                proposal, content, 1, true);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte element : digest) {
                result.append(String.format("%02x", element & 0xff));
            }
            return result.toString();
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
