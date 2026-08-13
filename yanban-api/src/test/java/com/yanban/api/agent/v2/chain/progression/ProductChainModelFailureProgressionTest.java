package com.yanban.api.agent.v2.chain.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.model.ChainModelAuthorityBindingRepairException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ProductChainModelFailureProgressionTest {
    @Test
    void formalFailureReviewRepairNamesEveryRequiredAuthorityRef() {
        var invalid = replanReview(List.of("different-block"),
                List.of("action-1"));

        var failure = assertThrows(
                ChainModelAuthorityBindingRepairException.class,
                () -> ProductChainModelFailureProgression
                        .requireFormalFailureReviewBinding(
                                invalid, "block-1", List.of(
                                        "block-1", "action-1", "receipt-1",
                                        "repair-proposal-1")));

        org.assertj.core.api.Assertions.assertThat(failure.safeFeedback())
                .contains("review.reviewedObjectRefs", "\"block-1\"",
                        "review.directFactRefs",
                        "[\"block-1\",\"action-1\",\"receipt-1\",\"repair-proposal-1\"]");
    }

    @Test
    void formalFailureReviewAcceptsExactRequiredAuthorityRefs() {
        var valid = replanReview(List.of("block-1"), List.of(
                "repair-proposal-1", "receipt-1", "block-1", "action-1"));

        assertDoesNotThrow(() -> ProductChainModelFailureProgression
                .requireFormalFailureReviewBinding(valid, "block-1",
                        List.of("block-1", "action-1", "receipt-1",
                                "repair-proposal-1")));
    }

    @Test
    void rejectedThenStaleModelFailureReviewKeepsExactStepBlockSource() {
        var fixture = fixture();
        var predecessor = context();
        var failedFirst = invocation("invocation-1", 1);
        var failedLast = invocation("invocation-2", 2);
        String attemptRef = "invocation-2#3";
        String blockId = "block-1";
        String fence = sha256("invocation-2\0context-1\0instruction-1\0"
                + "frame-1\0plan-1\0revision-1\0" + "1\0step-1\0"
                + "activation-1\0" + attemptRef);
        var block = new ChainPersistenceRecords.ModelFailureStepBlockRecord(
                blockId, "task-1", "block-event", "invocation-2",
                "context-1", "instruction-1", "frame-1", "plan-1",
                "revision-1", 1L, "step-1", "activation-1", attemptRef,
                "MODEL", "MODEL_CALL_FAILED", fence, Instant.EPOCH);
        String rootId = id("context-model-failure-review", blockId);
        var root = reviewContext(rootId, "context-1",
                "MODEL_CALL_FAILED_REVIEW", "root-completion");
        var rootInvocation = reviewInvocation("root-invocation", root);
        var rootProposal = proposal("root-proposal", rootInvocation);
        var rejected = state(rootProposal, "root-rejected",
                ChainProposalState.REJECTED);
        String childId = retryId(rootId, rejected.eventId());
        var child = reviewContext(childId, rootId,
                "MODEL_CALL_FAILED_REVIEW", "child-completion");
        var childInvocation = reviewInvocation("child-invocation", child);
        var childProposal = proposal("child-proposal", childInvocation);
        var stale = state(childProposal, "child-stale",
                ChainProposalState.STALE);

        when(fixture.models.findProposalStateEvent(stale.eventId()))
                .thenReturn(Optional.of(stale));
        when(fixture.models.findProposal(childProposal.proposalId()))
                .thenReturn(Optional.of(childProposal));
        when(fixture.models.findInvocation(childInvocation.invocationId()))
                .thenReturn(Optional.of(childInvocation));
        when(fixture.contexts.findContextRevision(childId))
                .thenReturn(Optional.of(child));
        when(fixture.contexts.findContextRevision(rootId))
                .thenReturn(Optional.of(root));
        when(fixture.contexts.findContextRevision("context-1"))
                .thenReturn(Optional.of(predecessor));
        when(fixture.models.findProposalStateEvents(
                childProposal.proposalId())).thenReturn(List.of(stale));
        when(fixture.models.findInvocationsByContextRevisionId(
                "task-1", rootId)).thenReturn(List.of(rootInvocation));
        when(fixture.models.findProposalByInvocation(
                rootInvocation.invocationId()))
                .thenReturn(Optional.of(rootProposal));
        when(fixture.models.findProposalStateEvents(rootProposal.proposalId()))
                .thenReturn(List.of(rejected));
        when(fixture.workflow.findModelFailureStepBlocks("task-1"))
                .thenReturn(List.of(block));
        when(fixture.models.findInvocation("invocation-2"))
                .thenReturn(Optional.of(failedLast));
        when(fixture.models.findInvocationsByContextRevisionId(
                "task-1", "context-1"))
                .thenReturn(List.of(failedFirst, failedLast));
        when(fixture.models.findProposalByInvocation("invocation-1"))
                .thenReturn(Optional.empty());
        when(fixture.models.findProposalByInvocation("invocation-2"))
                .thenReturn(Optional.empty());
        when(fixture.models.findProviderAttempts("invocation-1"))
                .thenReturn(attempts("invocation-1"));
        when(fixture.models.findProviderAttempts("invocation-2"))
                .thenReturn(attempts("invocation-2"));

        var directive = fixture.progression.formalRetryDirective(
                "task-1", stale.eventId()).orElseThrow();
        assertEquals("MODEL_FAILURE_STEP_BLOCK",
                directive.sourceAuthorityType());
        assertEquals(blockId, directive.sourceAuthorityRef());
    }

    @Test
    void staleThenRejectedContextFailureReviewKeepsExactFailureSource() {
        var fixture = fixture();
        String failureId = "context-failure-1";
        var predecessor = buildingContext("blocked-context");
        var failure = new ChainPersistenceRecords.ContextBuildFailureRecord(
                failureId, "task-1", "failure-event", "blocked-context",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_EXECUTION", "instruction-1",
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "CONTEXT_INPUT_BLOCKED", "projector-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(), Instant.EPOCH);
        var source = new ProductChainContextBuildFailureAuthority.Source(
                predecessor, failure, 1L, true);
        String rootId = id("context-build-failure-review", failureId);
        var root = reviewContext(rootId, "blocked-context",
                "CONTEXT_BUILD_FAILURE_REVIEW", "root-completion");
        var rootInvocation = reviewInvocation("context-root-invocation", root);
        var rootProposal = proposal("context-root-proposal", rootInvocation);
        var stale = state(rootProposal, "root-stale",
                ChainProposalState.STALE);
        String childId = retryId(rootId, stale.eventId());
        var child = reviewContext(childId, rootId,
                "CONTEXT_BUILD_FAILURE_REVIEW", "child-completion");
        var childInvocation = reviewInvocation(
                "context-child-invocation", child);
        var childProposal = proposal(
                "context-child-proposal", childInvocation);
        var rejected = state(childProposal, "child-rejected",
                ChainProposalState.REJECTED);

        when(fixture.models.findProposalStateEvent(rejected.eventId()))
                .thenReturn(Optional.of(rejected));
        when(fixture.models.findProposal(childProposal.proposalId()))
                .thenReturn(Optional.of(childProposal));
        when(fixture.models.findInvocation(childInvocation.invocationId()))
                .thenReturn(Optional.of(childInvocation));
        when(fixture.contexts.findContextRevision(childId))
                .thenReturn(Optional.of(child));
        when(fixture.contexts.findContextRevision(rootId))
                .thenReturn(Optional.of(root));
        when(fixture.models.findProposalStateEvents(
                childProposal.proposalId())).thenReturn(List.of(rejected));
        when(fixture.models.findInvocationsByContextRevisionId(
                "task-1", rootId)).thenReturn(List.of(rootInvocation));
        when(fixture.models.findProposalByInvocation(
                rootInvocation.invocationId()))
                .thenReturn(Optional.of(rootProposal));
        when(fixture.models.findProposalStateEvents(rootProposal.proposalId()))
                .thenReturn(List.of(stale));
        when(fixture.contexts.findContextBuildFailure("blocked-context"))
                .thenReturn(Optional.of(failure));
        when(fixture.contextAuthority.read("task-1", failureId))
                .thenReturn(source);

        var directive = fixture.progression.formalRetryDirective(
                "task-1", rejected.eventId()).orElseThrow();
        assertEquals("CONTEXT_BUILD_FAILURE",
                directive.sourceAuthorityType());
        assertEquals(failureId, directive.sourceAuthorityRef());
    }

    @Test
    void executorExhaustionWritesOneReplayableStepBlockAtMicroPrecision() {
        var foundations = mock(ProductChainFoundationRepositoryAdapter.class);
        var contexts = mock(ProductChainContextRepositoryAdapter.class);
        var models = mock(ProductChainModelRepositoryAdapter.class);
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var contextSources = mock(ProductChainContextSourceFactory.class);
        var contextAuthority = mock(
                ProductChainContextBuildFailureAuthority.class);
        var progression = new ProductChainModelFailureProgression(
                foundations, contexts, models, workflow,
                mock(ProductChainFinalizationRepositoryAdapter.class),
                mock(ProductChainCompletedOutcomeAdapter.class),
                mock(PlatformTransactionManager.class), contextSources,
                mock(ProductChainModelCallIdentity.class),
                mock(UserSettingsService.class),
                mock(ChatModelProvider.class),
                mock(NamedParameterJdbcTemplate.class), contextAuthority);
        var task = mock(ChainPersistenceRecords.TaskRecord.class);
        when(task.taskId()).thenReturn("task-1");
        var instruction = mock(ChainPersistenceRecords.InstructionRecord.class);
        when(instruction.instructionId()).thenReturn("instruction-1");
        var context = context();
        var first = invocation("invocation-1", 1);
        var exhausted = invocation("invocation-2", 2);
        when(models.findInvocation("invocation-2"))
                .thenReturn(Optional.of(exhausted));
        when(contexts.findContextRevision("context-1"))
                .thenReturn(Optional.of(context));
        when(models.findInvocationsByContextRevisionId(
                "task-1", "context-1"))
                .thenReturn(List.of(first, exhausted));
        when(models.findProposalByInvocation(any())).thenReturn(Optional.empty());
        when(models.findProviderAttempts("invocation-1"))
                .thenReturn(attempts("invocation-1"));
        when(models.findProviderAttempts("invocation-2"))
                .thenReturn(attempts("invocation-2"));
        when(workflow.findModelFailureStepBlocks("task-1"))
                .thenReturn(List.of());
        when(workflow.appendModelFailureStepBlock(any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            var fact = (ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ModelFailureStepBlockRecord>)
                    call.getArgument(0);
            var event = mock(ChainPersistenceRecords.AuthorityEventRecord.class);
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event, fact.fact(), false);
        });

        Instant nanos = Instant.parse("2026-08-09T01:02:03.123456789Z");
        progression.advance(task, instruction,
                new ProductChainNextRoleSelector.MechanicalModelFailure(
                        "invocation-2", ChainRole.EXECUTOR,
                        "MODEL_CALL_FAILED", "invocation-2"), nanos);

        var blocks = org.mockito.ArgumentCaptor.forClass(
                ChainPersistenceRecords.AuthoritativeFact.class);
        org.mockito.Mockito.verify(workflow)
                .appendModelFailureStepBlock(blocks.capture());
        var block = (ChainPersistenceRecords.ModelFailureStepBlockRecord)
                blocks.getValue().fact();
        assertEquals(nanos.truncatedTo(ChronoUnit.MICROS), block.createdAt());
        assertEquals("invocation-2#3", block.lastProviderAttemptRef());
    }

    private static ChainPersistenceRecords.ContextRevisionRecord context() {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "STEP_EXECUTION", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L, "step-1",
                "activation-1", 3L, "project-v1", "workspace-1", null,
                null, null, null, null, "projector-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"),
                "0".repeat(64), "completion-1",
                null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static ReflectorPayload.ReplanRequired replanReview(
            List<String> reviewedObjectRefs, List<String> directFactRefs) {
        return new ReflectorPayload.ReplanRequired(
                new ProposalFields.ReviewCommon("formal failure",
                        reviewedObjectRefs, "replan", directFactRefs,
                        List.of()),
                "block-1", List.of(), List.of("repair the plan"));
    }

    private static ChainPersistenceRecords.ModelInvocationRecord invocation(
            String id, int ordinal) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                id, "task-1", "context-1", "completion-1",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "STEP_EXECUTION", "provider", "model", ordinal,
                ChainRuntimePolicy.V1.policyVersion(), Instant.EPOCH);
    }

    private static List<ChainPersistenceRecords.ProviderAttemptRecord>
            attempts(String invocationId) {
        return java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(value ->
                new ChainPersistenceRecords.ProviderAttemptRecord(
                        invocationId, value, "task-1", 1L, null,
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        "PROVIDER_ERROR", Instant.EPOCH)).toList();
    }

    private static Fixture fixture() {
        var foundations = mock(ProductChainFoundationRepositoryAdapter.class);
        var contexts = mock(ProductChainContextRepositoryAdapter.class);
        var models = mock(ProductChainModelRepositoryAdapter.class);
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var authority = mock(ProductChainContextBuildFailureAuthority.class);
        var progression = new ProductChainModelFailureProgression(
                foundations, contexts, models, workflow,
                mock(ProductChainFinalizationRepositoryAdapter.class),
                mock(ProductChainCompletedOutcomeAdapter.class),
                mock(PlatformTransactionManager.class),
                mock(ProductChainContextSourceFactory.class),
                mock(ProductChainModelCallIdentity.class),
                mock(UserSettingsService.class), mock(ChatModelProvider.class),
                mock(NamedParameterJdbcTemplate.class), authority);
        return new Fixture(progression, contexts, models, workflow, authority);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord reviewContext(
            String id, String parent, String reason, String token) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                id, "task-1", parent, ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, reason, "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L, "step-1",
                "activation-1", 3L, "project-v1", "workspace-1", null,
                null, null, null, null, "projector-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"),
                "0".repeat(64), token, null, null,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            buildingContext(String id) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                id, "task-1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "STEP_EXECUTION", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L, "step-1",
                "activation-1", 3L, "project-v1", "workspace-1", null,
                null, null, null, null, "projector-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 2, null, null, null,
                null, null, Instant.EPOCH, null);
    }

    private static ChainPersistenceRecords.ModelInvocationRecord
            reviewInvocation(String id,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                id, "task-1", context.contextRevisionId(),
                context.completionToken(), ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, context.callReason(),
                "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), Instant.EPOCH);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            String id,
            ChainPersistenceRecords.ModelInvocationRecord invocation) {
        return new ChainPersistenceRecords.ModelProposalRecord(
                id, "task-1", invocation.invocationId(), 1,
                ChainRole.REFLECTOR, ChainProposalKind.REFLECTOR_TASK_FAILED,
                canonical("{}"), canonical("[]"), null, null, Instant.EPOCH);
    }

    private static ChainPersistenceRecords.ProposalStateEventRecord state(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            String eventId, ChainProposalState state) {
        return new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1L, "task-1", eventId, state,
                null, null, Instant.EPOCH);
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static String retryId(String rootId, String eventId) {
        return id("context", "proposal-retry\0" + rootId + "\0" + eventId);
    }

    private static String id(String kind, String source) {
        return kind + "." + sha256(kind + "\0" + source);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(
            ProductChainModelFailureProgression progression,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainContextBuildFailureAuthority contextAuthority) {}
}
