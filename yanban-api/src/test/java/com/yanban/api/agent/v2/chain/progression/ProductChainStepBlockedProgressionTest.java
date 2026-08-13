package com.yanban.api.agent.v2.chain.progression;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ProductChainStepBlockedProgressionTest {
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void continuationMustBindAcceptedEventAndFormalError() {
        var source = source();
        var valid = new ReflectorPayload.ContinueStep(
                review(List.of("accepted-event", "error-1")),
                List.of("the formal error remains"), List.of("error-1"),
                "retry only the current active Step");

        assertDoesNotThrow(() ->
                ProductChainStepBlockedProgression.validatePayload(
                        valid, source));
        var drifted = new ReflectorPayload.ContinueStep(
                review(List.of("accepted-event", "other-error")),
                List.of("the formal error remains"),
                List.of("other-error"), "retry the current active Step");
        assertThrows(IllegalStateException.class, () ->
                ProductChainStepBlockedProgression.validatePayload(
                        drifted, source));
    }

    @Test
    void candidateAcceptanceKindsCannotReviewAStepBlock() {
        var source = source();
        var invalid = new ReflectorPayload.AcceptStep(
                review(List.of("accepted-event", "error-1")), "candidate-1",
                List.of(new ProposalFields.RequirementCoverage(
                        "condition", ProposalFields.RequirementStatus.SATISFIED,
                        List.of("error-1"))),
                List.of("error-1"), "frame-1", "revision-1", "step-1",
                "candidate-1", List.of());

        assertThrows(IllegalStateException.class, () ->
                ProductChainStepBlockedProgression.validatePayload(
                        invalid, source));
    }

    @Test
    void theSameFormalSourceValidatesIdenticallyOnReplay() {
        var source = source();
        var decision = new ReflectorPayload.ReplanRequired(
                review(List.of("accepted-event", "error-1")), "error-1",
                List.of(), List.of("repair the formal failure"));

        assertDoesNotThrow(() -> {
            ProductChainStepBlockedProgression.validatePayload(decision, source);
            ProductChainStepBlockedProgression.validatePayload(decision, source);
        });
    }

    @Test
    void handlesUsesTheExactAcceptedEventIdentity() {
        ProductChainModelRepositoryAdapter models = mock(
                ProductChainModelRepositoryAdapter.class);
        var source = source();
        when(models.findProposalStateEvent("accepted-event"))
                .thenReturn(Optional.of(source.acceptedState()));
        when(models.findProposal(source.proposal().proposalId()))
                .thenReturn(Optional.of(source.proposal()));
        ProductChainExecutorProgression executor = mock(
                ProductChainExecutorProgression.class);
        when(executor.recoverAcceptedStepBlock(
                "task-1", "accepted-event")).thenReturn(source);
        var owner = new ProductChainStepBlockedProgression(
                executor,
                mock(ProductChainContextRepositoryAdapter.class), models,
                mock(ProductChainWorkflowRepositoryAdapter.class),
                mock(ProductChainFoundationRepositoryAdapter.class),
                mock(ProductChainContextSourceFactory.class),
                mock(ProductChainModelCallIdentity.class),
                mock(UserSettingsService.class), mock(ChatModelProvider.class),
                mock(PlatformTransactionManager.class),
                mock(NamedParameterJdbcTemplate.class),
                mock(ProductChainCompletedOutcomeAdapter.class));

        org.junit.jupiter.api.Assertions.assertTrue(
                owner.handles("task-1", "accepted-event"));
        org.junit.jupiter.api.Assertions.assertFalse(
                owner.handles("another-task", "accepted-event"));
        org.junit.jupiter.api.Assertions.assertFalse(
                owner.handles("task-1", "unknown-event"));
    }

    @Test
    void invocationPrefixRejectsAMissingOrdinal() {
        var first = invocation("invocation-1", 1);
        var third = invocation("invocation-3", 3);
        assertThrows(IllegalStateException.class, () ->
                ProductChainStepBlockedProgression.validateInvocationPrefix(
                        "task-1", 3, List.of(first, third)));
    }

    private static ProposalFields.ReviewCommon review(List<String> facts) {
        return new ProposalFields.ReviewCommon(
                "current active Step block", List.of("accepted-event"),
                "review the formal Executor block", facts, List.of());
    }

    private static ProductChainExecutorProgression.AcceptedStepBlock source() {
        var payload = new ExecutorPayload.StepBlocked(
                "EXECUTION", "error-1", List.of("action-1"),
                "the action cannot progress", "reflect before retry",
                List.of(), null, null, null);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal-1", "task-1", "invocation-1", 1,
                ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_STEP_BLOCKED,
                json("{}"), json("[]"), null, null, NOW);
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1, "task-1", "accepted-event",
                ChainProposalState.ACCEPTED, null, null, NOW);
        var context = new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "STEP_EXECUTION", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L, "step-1",
                "activation-1", 3L, "version-1", "workspace-1", null,
                null, null, null, null, "projectors", "pagination", "policy",
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"),
                sha256("manifest"), sha256("input"), null,
                null, NOW, NOW);
        var binding = new ChainPersistenceRecords.PlanBindingRecord(
                "binding-1", "task-1", "binding-event", "instruction-1",
                "route-1", "frame-1", "plan-1", "revision-1", 1,
                "MODEL_PROPOSAL", "planner-proposal", sha256("authority"),
                "transition-1", NOW);
        return new ProductChainExecutorProgression.AcceptedStepBlock(
                proposal, accepted, context, binding, payload);
    }

    private static ChainPersistenceRecords.ModelInvocationRecord invocation(
            String id, int ordinal) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                id, "task-1", "context-" + ordinal,
                "completion-" + ordinal, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "STEP_EXECUTION", "provider",
                "model", ordinal, "policy", NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(value), value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
