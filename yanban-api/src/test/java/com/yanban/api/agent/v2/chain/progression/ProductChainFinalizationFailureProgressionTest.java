package com.yanban.api.agent.v2.chain.progression;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainFinalizationRecoverySource;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ProductChainFinalizationFailureProgressionTest {

    @Test
    void acceptsTaskFailureOnlyWhenItNamesTheExactFormalFailure() {
        var source = source("FINALIZATION_CHECK", "check-1",
                "FINALIZATION", "VALIDATION_MISSING");
        var review = review("check-1");
        var payload = new ReflectorPayload.TaskFailed(
                review, mock(ProposalFields.FinalizationAssessment.class),
                List.of("check-1"), List.of("validation incomplete"),
                "FINALIZATION");

        assertThatNoException().isThrownBy(() ->
                ProductChainFinalizationFailureProgression.validatePayload(
                        payload, source));
    }

    @Test
    void rejectsTaskFailureThatSubstitutesAnotherFactOrCategory() {
        var source = source("PUBLISH_FAILURE", "publish-failure-1",
                "PUBLISH", "PUBLISH_RECEIPT_MISSING");
        var payload = new ReflectorPayload.TaskFailed(
                review("another-fact"),
                mock(ProposalFields.FinalizationAssessment.class),
                List.of("another-fact"), List.of("publish incomplete"),
                "FINALIZATION");

        assertThatThrownBy(() ->
                ProductChainFinalizationFailureProgression.validatePayload(
                        payload, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_FINALIZATION_FAILURE_FACT_REF_MISSING");
    }

    @Test
    void rejectsOrdinaryStepReviewKindsAtTheFailureBoundary() {
        var source = source("FINALIZATION_CHECK", "check-1",
                "FINALIZATION", "VALIDATION_MISSING");
        var payload = new ReflectorPayload.ContinueStep(
                review("check-1"), List.of("still incomplete"),
                List.of("check-1"), "continue current work");

        assertThatThrownBy(() ->
                ProductChainFinalizationFailureProgression.validatePayload(
                        payload, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_FINALIZATION_FAILURE_REFLECTOR_KIND_INVALID");
    }

    @Test
    void taskFailedCreatesAFormalFailedOutcomeFromTheExactFailureSource() {
        assertTaskFailedCreatesFormalFailedOutcome(
                "PUBLISH_FAILURE", "publish-failure-1", "PUBLISH",
                "PUBLISH_RECEIPT_MISSING", "publish was not completed");
    }

    @Test
    void failedValidationTaskDecisionCreatesFailedOutcomeFromExactCheck() {
        assertTaskFailedCreatesFormalFailedOutcome(
                "FINALIZATION_CHECK", "finalization-check-1",
                "FINALIZATION", "VALIDATION_NOT_SUCCESSFUL",
                "validation was not successful");
    }

    @Test
    void failedValidationPermissionDecisionCreatesFormalPermissionPendingItem() {
        var models = mock(ProductChainModelRepositoryAdapter.class);
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var foundations = mock(ProductChainFoundationRepositoryAdapter.class);
        var progression = progression(
                mock(ProductChainCompletedOutcomeAdapter.class), models,
                workflow, foundations);
        Instant now = Instant.parse("2026-08-09T06:30:00Z");
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                "proposal-permission", 1L, "task-1", "event-accepted",
                ChainProposalState.ACCEPTED, null, null, now);
        var bound = new ChainPersistenceRecords.ProposalStateEventRecord(
                "proposal-permission", 2L, "task-1", "event-bound",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "REVIEW_DECISION", "review-permission", now);
        when(models.findProposalStateEvents("proposal-permission"))
                .thenReturn(List.of(accepted, bound));
        when(workflow.appendPendingItem(any())).thenAnswer(invocation -> {
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.PendingItemRecord> requested =
                    invocation.getArgument(0);
            var event = requested.event();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    new ChainPersistenceRecords.AuthorityEventRecord(
                            event.eventId(), event.taskId(), 9L,
                            event.eventType(), event.transitionId(),
                            event.sourceIdentitySha256(), event.committedAt()),
                    requested.fact(), false);
        });
        var task = mock(ChainPersistenceRecords.TaskRecord.class);
        when(task.taskId()).thenReturn("task-1");
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal-permission", "task-1", "invocation-permission", 1,
                ChainRole.REFLECTOR,
                ChainProposalKind.REFLECTOR_NEED_PERMISSION,
                emptyJson(), emptyJson(), null, null, now);
        var readiness = mock(
                ChainPersistenceRecords.FinalizationReadinessRecord.class);
        when(readiness.readinessScopeKey()).thenReturn("a".repeat(64));
        var source = new ProductChainFinalizationFailureProgression
                .FailureSource(
                "FINALIZATION_CHECK", "finalization-check-1",
                "FINALIZATION", "VALIDATION_NOT_SUCCESSFUL", readiness,
                mock(ChainPersistenceRecords.FinalizationCheckRecord.class),
                mock(ChainPersistenceRecords.TransitionRecord.class));
        var decision = mock(
                ChainPersistenceRecords.ReviewDecisionRecord.class);
        when(decision.reviewDecisionId()).thenReturn("review-permission");
        var payload = new ReflectorPayload.NeedPermission(
                review("finalization-check-1"), "NETWORK", "registry:read",
                "obtain required validation dependency",
                "fail without expanded permission", ChainRole.PLANNER,
                "replan-after-permission");

        var pending = progression.openPermissionPending(
                task, proposal, source, decision, payload, now);

        assertThat(pending.pendingType())
                .isEqualTo(ChainPendingItemType.PERMISSION);
        assertThat(pending.permissionScope()).isEqualTo("registry:read");
        assertThat(pending.sourceProposalId())
                .isEqualTo("proposal-permission");
        assertThat(pending.resumeRole()).isEqualTo(ChainRole.PLANNER);
    }

    private static void assertTaskFailedCreatesFormalFailedOutcome(
            String sourceType, String sourceRef, String failureCategory,
            String failureCode, String reason) {
        var outcomes = mock(ProductChainCompletedOutcomeAdapter.class);
        var progression = progression(outcomes);
        var task = mock(ChainPersistenceRecords.TaskRecord.class);
        when(task.taskId()).thenReturn("task-1");
        var instruction = mock(ChainPersistenceRecords.InstructionRecord.class);
        when(instruction.commandId()).thenReturn("command-1");
        when(instruction.instructionId()).thenReturn("instruction-1");
        var readiness = mock(
                ChainPersistenceRecords.FinalizationReadinessRecord.class);
        when(readiness.taskFrameId()).thenReturn("frame-1");
        when(readiness.finalPlanId()).thenReturn("plan-1");
        when(readiness.finalPlanRevisionId()).thenReturn("revision-1");
        when(readiness.coverage()).thenReturn(emptyJson());
        when(readiness.acceptedSet()).thenReturn(emptyJson());
        when(readiness.candidateKey()).thenReturn("candidate-1");
        when(readiness.validationId()).thenReturn("validation-1");
        var source = new ProductChainFinalizationFailureProgression
                .FailureSource(
                sourceType, sourceRef, failureCategory,
                failureCode, readiness,
                mock(ChainPersistenceRecords.FinalizationCheckRecord.class),
                mock(ChainPersistenceRecords.TransitionRecord.class));
        var decision = mock(
                ChainPersistenceRecords.ReviewDecisionRecord.class);
        var payload = new ReflectorPayload.TaskFailed(
                review(sourceRef),
                mock(ProposalFields.FinalizationAssessment.class),
                List.of(sourceRef), List.of(reason), failureCategory);

        progression.commitFailedOutcome(
                task, instruction, source, decision, payload,
                java.time.Instant.parse("2026-08-09T06:00:00Z"));

        var command = ArgumentCaptor.forClass(
                ChainTaskOutcomeRuntime.OutcomeCommand.class);
        verify(outcomes).commit(command.capture(),
                org.mockito.ArgumentMatchers.any());
        assertThat(command.getValue())
                .isInstanceOf(ChainTaskOutcomeRuntime.Failed.class);
        var failed = (ChainTaskOutcomeRuntime.Failed) command.getValue();
        assertThat(failed.formalFailureSourceId())
                .isEqualTo(sourceRef);
        assertThat(failed.failureCategory()).isEqualTo(failureCategory);
        assertThat(failed.failureCode()).isEqualTo(failureCode);
        assertThat(failed.draft().sourceCommandId()).isEqualTo("command-1");
        assertThat(failed.draft().instructionId())
                .isEqualTo("instruction-1");
    }

    private static ProposalFields.ReviewCommon review(String ref) {
        return new ProposalFields.ReviewCommon(
                "formal finalization failure", List.of(ref),
                "review the named failure", List.of(ref), List.of());
    }

    private static ProductChainFinalizationFailureProgression.FailureSource
            source(String type, String ref, String category, String code) {
        return new ProductChainFinalizationFailureProgression.FailureSource(
                type, ref, category, code,
                mock(ChainPersistenceRecords.FinalizationReadinessRecord.class),
                mock(ChainPersistenceRecords.FinalizationCheckRecord.class),
                mock(ChainPersistenceRecords.TransitionRecord.class));
    }

    private static ProductChainFinalizationFailureProgression progression(
            ProductChainCompletedOutcomeAdapter outcomes) {
        return progression(outcomes,
                mock(ProductChainModelRepositoryAdapter.class),
                mock(ProductChainWorkflowRepositoryAdapter.class),
                mock(ProductChainFoundationRepositoryAdapter.class));
    }

    private static ProductChainFinalizationFailureProgression progression(
            ProductChainCompletedOutcomeAdapter outcomes,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations) {
        return new ProductChainFinalizationFailureProgression(
                mock(ProductChainContextRepositoryAdapter.class),
                models, workflow, foundations,
                mock(ProductChainFinalizationRepositoryAdapter.class),
                mock(ProductChainFinalizationRecoverySource.class),
                mock(ProductChainContextSourceFactory.class),
                mock(ProductChainModelCallIdentity.class), outcomes,
                mock(UserSettingsService.class),
                mock(ChatModelProvider.class),
                mock(PlatformTransactionManager.class),
                mock(NamedParameterJdbcTemplate.class));
    }

    private static ChainPersistenceRecords.CanonicalJson emptyJson() {
        return new ChainPersistenceRecords.CanonicalJson(
                1, "0".repeat(64), "[]");
    }
}
