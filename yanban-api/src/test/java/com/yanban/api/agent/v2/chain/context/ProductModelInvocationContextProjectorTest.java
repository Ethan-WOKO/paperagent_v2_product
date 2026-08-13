package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextProjectionRequest;
import io.paperagent.v2.chain.context.ChainContextValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductModelInvocationContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T09:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void noPriorLineageInvocationIsFormalEmpty() {
        Fixture fixture = fixture();

        var projection = fixture.subject.read(request(
                building(ChainRole.PLANNER, null, null, null)));

        assertEquals(ChainContextModuleStatus.EMPTY,
                projection.presenceKind());
        assertEquals("priorInvocationOrdinal=0", projection.emptyWatermark());
        assertEquals(0, number(projection.readBoundaryComponents(),
                "priorInvocationOrdinal"));
        verify(fixture.models).findInvocations("task.1", 0L);
    }

    @Test
    void invocationOutsideEmptyLineagePreventsFalseEmptyProjection() {
        Fixture fixture = fixture();
        when(fixture.models.highestInvocationOrdinal("task.1"))
                .thenReturn(1L);

        ChainContextException failure = assertThrows(
                ChainContextException.class, () -> fixture.subject.read(
                        request(building(ChainRole.PLANNER,
                                null, null, null))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void unknownRequiredFieldIsTypedBlocked() {
        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> ProductModelRequiredFieldSelector.select(
                        List.of("model.misspelledField"), Map.of()));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void sparseOrdinalPrefixIsBlockedInsteadOfCounted() {
        Fixture fixture = fixture();
        var first = completeContext("context.1", null, ChainRole.PLANNER,
                ChainWorkState.PLANNING, "FIRST");
        var second = completeContext("context.2", "context.1",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING, "SECOND");
        when(fixture.contexts.findContextRevision("context.2"))
                .thenReturn(Optional.of(second));
        when(fixture.contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(first));
        when(fixture.models.findInvocationsByContextRevisionId(
                "task.1", "context.1"))
                .thenReturn(List.of(invocation(first, 1)));
        when(fixture.models.findInvocationsByContextRevisionId(
                "task.1", "context.2"))
                .thenReturn(List.of(invocation(second, 3)));

        ChainContextException failure = assertThrows(
                ChainContextException.class, () -> fixture.subject.read(
                        request(building(ChainRole.REFLECTOR, "context.2",
                                7L, HASH))));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
        verify(fixture.models, never()).findInvocations(
                anyString(), anyLong());
    }

    @Test
    void acceptedProposalRemainsNonOfficialUntilReplacement() {
        Fixture accepted = fixture();
        wireSingle(accepted, ChainRole.PLANNER,
                ChainProposalKind.PLANNER_PERSISTENT_PLAN,
                List.of(state("proposal.1", 1, ChainProposalState.ACCEPTED,
                        null, null)));
        var acceptedProjection = accepted.subject.read(request(
                building(ChainRole.PLANNER, "context.1", null, null)));
        var acceptedProposal = nested(acceptedProjection.projectionFields(),
                "model.latestAcceptedOrFailedPlannerMetadata", "proposal");
        assertInstanceOf(ChainContextValue.NullValue.class,
                acceptedProposal.values().get("formalResult"));

        Fixture replaced = fixture();
        wireSingle(replaced, ChainRole.PLANNER,
                ChainProposalKind.PLANNER_PERSISTENT_PLAN,
                List.of(state("proposal.1", 1, ChainProposalState.ACCEPTED,
                                null, null),
                        state("proposal.1", 2,
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "PLAN_BINDING", "binding.1")));
        var replacedProjection = replaced.subject.read(request(
                building(ChainRole.PLANNER, "context.1", null, null)));
        var replacedProposal = nested(replacedProjection.projectionFields(),
                "model.latestAcceptedOrFailedPlannerMetadata", "proposal");
        var formal = (ChainContextValue.ObjectValue)
                replacedProposal.values().get("formalResult");
        assertEquals("binding.1", text(formal.values(), "authorityRef"));
        verify(replaced.models).findInvocations("task.1", 1L);
    }

    @Test
    void reflectorGetsReviewedExecutorCandidateProposalMetadata() {
        Fixture fixture = fixture();
        wireSingle(fixture, ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_STEP_RESULT,
                List.of(state("proposal.1", 1, ChainProposalState.ACCEPTED,
                                null, null),
                        state("proposal.1", 2,
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "CANDIDATE_STEP_RESULT", "candidate-result.1")));

        var projection = fixture.subject.read(request(building(
                ChainRole.REFLECTOR, "context.1", 7L, HASH)));

        var reviewed = (ChainContextValue.ObjectValue)
                projection.projectionFields().get(
                        "model.reviewedCandidateProposal");
        assertEquals(7, ((ChainContextValue.NumberValue) reviewed.values()
                .get("candidateArtifactId")).value());
        var executor = (ChainContextValue.ObjectValue) reviewed.values()
                .get("executorProposal");
        assertEquals("invocation.1", text(executor.values(),
                "invocationRef"));
        assertEquals("candidate-result.1", text(reviewed.values(),
                "officialCandidateResultRef"));
    }

    @Test
    void laterRejectedExecutorProposalDoesNotReplaceReviewedCandidate() {
        var firstContext = completeContext(
                "context.1", null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "FIRST");
        var secondContext = completeContext(
                "context.2", "context.1", ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "SECOND");
        var first = invocation(firstContext, 1);
        var second = invocation(secondContext, 2);
        var firstProposal = proposal(first, "proposal.1",
                ChainProposalKind.EXECUTOR_STEP_RESULT);
        var rejectedProposal = proposal(second, "proposal.2",
                ChainProposalKind.EXECUTOR_STEP_RESULT);
        var formal = new ProductModelInvocationProjectionValues.InvocationView(
                firstContext, first, List.of(), firstProposal,
                List.of(state("proposal.1", 1,
                                ChainProposalState.ACCEPTED, null, null),
                        state("proposal.1", 2,
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "CANDIDATE_STEP_RESULT",
                                "candidate-result.1")));
        var rejected = new ProductModelInvocationProjectionValues.InvocationView(
                secondContext, second, List.of(), rejectedProposal,
                List.of(state("proposal.2", 1,
                        ChainProposalState.REJECTED, null, null)));

        var values = ProductModelInvocationProjectionValues.create(
                building(ChainRole.REFLECTOR, "context.2", 7L, HASH),
                List.of("model.reviewedCandidateProposal"),
                List.of("context.1", "context.2"),
                List.of(formal, rejected), null, List.of());

        var reviewed = (ChainContextValue.ObjectValue) values.fields().get(
                "model.reviewedCandidateProposal");
        var executor = (ChainContextValue.ObjectValue) reviewed.values().get(
                "executorProposal");
        assertEquals("invocation.1", text(executor.values(),
                "invocationRef"));
        assertEquals("candidate-result.1", text(reviewed.values(),
                "officialCandidateResultRef"));
    }

    @Test
    void newerFormalCandidateForAnotherStepDoesNotReplaceReviewedScope() {
        var reviewedContext = scopedCompleteContext(
                "context.1", null, "step.reviewed", "activation.reviewed");
        var otherContext = scopedCompleteContext(
                "context.2", "context.1", "step.other", "activation.other");
        var reviewedInvocation = invocation(reviewedContext, 1);
        var otherInvocation = invocation(otherContext, 2);
        var reviewed = formalCandidateView(
                reviewedContext, reviewedInvocation, "proposal.1",
                "candidate-result.reviewed");
        var other = formalCandidateView(
                otherContext, otherInvocation, "proposal.2",
                "candidate-result.other");

        var values = ProductModelInvocationProjectionValues.create(
                scopedBuilding("context.2", "step.reviewed",
                        "activation.reviewed"),
                List.of("model.reviewedCandidateProposal"),
                List.of("context.1", "context.2"),
                List.of(reviewed, other), null, List.of());

        var field = (ChainContextValue.ObjectValue) values.fields().get(
                "model.reviewedCandidateProposal");
        var executor = (ChainContextValue.ObjectValue) field.values().get(
                "executorProposal");
        assertEquals("invocation.1", text(executor.values(),
                "invocationRef"));
        assertEquals("candidate-result.reviewed", text(field.values(),
                "officialCandidateResultRef"));
    }

    @Test
    void answerReadsOnlyFormalReplacementAndDeliveryFailureSources() {
        Fixture fixture = fixture();
        wireSingle(fixture, ChainRole.ANSWER,
                ChainProposalKind.ANSWER_FINAL_DELIVERY,
                List.of(state("proposal.1", 1, ChainProposalState.ACCEPTED,
                                null, null),
                        state("proposal.1", 2,
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "DELIVERY", "delivery.1")));
        var delivery = new ChainPersistenceRecords.DeliveryRecord(
                "delivery.1", "task.1", "delivery.created", "command.1",
                null, "outcome.1", null, null, null, null, NOW);
        var pending = new ChainPersistenceRecords.DeliveryEventRecord(
                "delivery.1", 1, "task.1", "delivery.pending",
                ChainDeliveryStatus.PENDING, 0, null, "policy.v1", NOW);
        var retrying = new ChainPersistenceRecords.DeliveryEventRecord(
                "delivery.1", 2, "task.1", "delivery.retrying",
                ChainDeliveryStatus.RETRYING, 1, "MESSAGE_UNAVAILABLE",
                "policy.v1", NOW);
        when(fixture.finalization.findTaskOutcome("task.1"))
                .thenReturn(Optional.empty());
        when(fixture.finalization.findDeliveries("task.1"))
                .thenReturn(List.of(delivery));
        when(fixture.finalization.findDeliveryEvents("delivery.1"))
                .thenReturn(List.of(pending, retrying));

        var projection = fixture.subject.read(request(
                building(ChainRole.ANSWER, "context.1", null, null)));

        var failure = (ChainContextValue.ObjectValue)
                projection.projectionFields().get(
                        "model.latestDeliveryFailureMetadata");
        assertEquals("RETRYING", text(failure.values(), "status"));
        assertEquals("MESSAGE_UNAVAILABLE",
                text(failure.values(), "errorCode"));
        var sources = (ChainContextValue.ArrayValue)
                projection.projectionFields().get("model.officialSourceRecords");
        assertEquals(2, sources.values().size());
    }

    private static void wireSingle(
            Fixture fixture, ChainRole priorRole, ChainProposalKind kind,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states) {
        ChainWorkState workState = stateFor(priorRole);
        var context = completeContext("context.1", null, priorRole,
                workState, "PRIOR_CALL");
        var invocation = invocation(context, 1);
        var proposal = proposal(invocation, "proposal.1", kind);
        when(fixture.contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(context));
        when(fixture.models.findInvocationsByContextRevisionId(
                "task.1", "context.1")).thenReturn(List.of(invocation));
        when(fixture.models.findInvocations("task.1", 1L))
                .thenReturn(List.of(invocation));
        when(fixture.models.highestInvocationOrdinal("task.1"))
                .thenReturn(1L);
        when(fixture.models.findProviderAttempts(invocation.invocationId()))
                .thenReturn(List.of());
        when(fixture.models.findProposalByInvocation(invocation.invocationId()))
                .thenReturn(Optional.of(proposal));
        when(fixture.models.findProposalStateEvents("proposal.1"))
                .thenReturn(states);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            String proposalId, ChainProposalKind kind) {
        return new ChainPersistenceRecords.ModelProposalRecord(
                proposalId, "task.1", invocation.invocationId(), 1,
                invocation.role(), kind, json("{\"value\":1}"),
                json("{\"refs\":[]}"), null, null, NOW);
    }

    private static ProductModelInvocationProjectionValues.InvocationView
            formalCandidateView(
                    ChainPersistenceRecords.ContextRevisionRecord context,
                    ChainPersistenceRecords.ModelInvocationRecord invocation,
                    String proposalId, String candidateResultId) {
        return new ProductModelInvocationProjectionValues.InvocationView(
                context, invocation, List.of(), proposal(
                        invocation, proposalId,
                        ChainProposalKind.EXECUTOR_STEP_RESULT),
                List.of(state(proposalId, 1, ChainProposalState.ACCEPTED,
                                null, null),
                        state(proposalId, 2,
                                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                                "CANDIDATE_STEP_RESULT", candidateResultId)));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role, String parent, Long artifactId,
            String candidateFingerprint) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.current", "task.1", parent, role, stateFor(role),
                "CURRENT_CALL", "instruction.current", null, null, null, null,
                null, null, null, null, null, artifactId,
                candidateFingerprint, null, null, null, "projectors.v1",
                "pages.v1", "policy.v1", ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            completeContext(
                    String id, String parent, ChainRole role,
                    ChainWorkState workState, String callReason) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                id, "task.1", parent, role, workState, callReason,
                "instruction.prior", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"), HASH,
                "completion." + id, null, null, NOW, NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            scopedCompleteContext(
                    String id, String parent, String step,
                    String activation) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                id, "task.1", parent, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "SCOPED", "instruction.prior",
                "frame.1", "plan.1", "revision.1", 1L, step, activation,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1", "policy.v1",
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"), HASH,
                "completion." + id, null, null, NOW, NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            scopedBuilding(
                    String parent, String step, String activation) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.current", "task.1", parent, ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, "CURRENT_CALL",
                "instruction.current", "frame.1", "plan.1", "revision.1",
                1L, step, activation, null, null, null, 7L, HASH, null,
                null, null, "projectors.v1", "pages.v1", "policy.v1",
                ChainContextRevisionStatus.BUILDING, 0, null, null, null,
                null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ModelInvocationRecord invocation(
            ChainPersistenceRecords.ContextRevisionRecord context,
            int ordinal) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                "invocation." + ordinal, "task.1",
                context.contextRevisionId(), context.completionToken(),
                context.role(), context.workState(), context.callReason(),
                "provider", "model", ordinal,
                context.runtimePolicyVersion(), NOW);
    }

    private static ChainPersistenceRecords.ProposalStateEventRecord state(
            String proposalId, long sequence, ChainProposalState state,
            String authorityType, String authorityRef) {
        return new ChainPersistenceRecords.ProposalStateEventRecord(
                proposalId, sequence, "task.1", "proposal.event." + sequence,
                state, authorityType, authorityRef, NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, ProductChainContractProjectionCodec.sha256(value), value);
    }

    private static ChainWorkState stateFor(ChainRole role) {
        return switch (role) {
            case PLANNER -> ChainWorkState.PLANNING;
            case EXECUTOR -> ChainWorkState.EXECUTING;
            case REFLECTOR -> ChainWorkState.AWAITING_REVIEW;
            case ANSWER -> ChainWorkState.DELIVERING;
        };
    }

    private static ChainContextProjectionRequest request(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static Fixture fixture() {
        var models = mock(ProductChainModelRepositoryAdapter.class);
        var contexts = mock(ProductChainContextRepositoryAdapter.class);
        var finalization = mock(
                ProductChainFinalizationRepositoryAdapter.class);
        return new Fixture(models, contexts, finalization,
                new ProductModelInvocationContextProjector(
                        models, contexts, finalization));
    }

    private static ChainContextValue.ObjectValue nested(
            Map<String, ChainContextValue> root, String first, String second) {
        return (ChainContextValue.ObjectValue)
                ((ChainContextValue.ObjectValue) root.get(first))
                        .values().get(second);
    }

    private static long number(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private static String text(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private record Fixture(
            ProductChainModelRepositoryAdapter models,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductModelInvocationContextProjector subject) {
    }
}
