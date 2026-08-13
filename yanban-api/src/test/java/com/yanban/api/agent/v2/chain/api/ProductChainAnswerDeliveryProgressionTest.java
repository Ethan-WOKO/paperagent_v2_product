package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.finalization.ProductChainTerminalOutcomeAuthority;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.model.ChainProviderProtocolException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainAnswerDeliveryProgressionTest {
    private static final Instant NOW = Instant.parse("2026-08-08T06:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void terminalAnswerUsesOutcomeRevisionWithoutRequiringPlanBinding() {
        var task = mock(ChainPersistenceRecords.TaskRecord.class);
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        var readiness = mock(ChainPersistenceRecords
                .FinalizationReadinessRecord.class);
        var terminal = mock(ProductChainTerminalOutcomeAuthority
                .TerminalFacts.class);
        var steps = mock(ProductChainStepAuthorityAdapter.class);
        var revision = mock(io.paperagent.v2.contracts.PlanRevision.class);
        var revisionId = new io.paperagent.v2.contracts.PlanRevisionId(
                "revision-2");
        var frameId = new io.paperagent.v2.contracts.TaskFrameId("frame-1");
        var step = mock(io.paperagent.v2.contracts.PlanStep.class);
        when(task.taskId()).thenReturn("task-1");
        when(outcome.finalPlanRevisionId()).thenReturn("revision-2");
        when(outcome.finalPlanId()).thenReturn("plan-1");
        when(outcome.taskFrameId()).thenReturn("frame-1");
        when(terminal.readiness()).thenReturn(readiness);
        when(terminal.finalStepId()).thenReturn("step-final");
        when(terminal.activationEventId()).thenReturn("activation-final");
        when(readiness.finalPlanId()).thenReturn("plan-1");
        when(readiness.finalPlanRevisionId()).thenReturn("revision-2");
        when(revision.id()).thenReturn(revisionId);
        when(revision.taskFrameId()).thenReturn(frameId);
        when(revision.steps()).thenReturn(List.of(step));
        when(step.id()).thenReturn(
                new io.paperagent.v2.contracts.PlanStepId("step-final"));
        when(steps.findPlanRevision("task-1", "revision-2"))
                .thenReturn(Optional.of(revision));

        var selected = ProductChainAnswerDeliveryProgression.terminalPlan(
                task, outcome, terminal, steps);

        assertEquals(revision, selected.revision());
        assertEquals(step, selected.step());
    }

    @Test
    void terminalAnswerFailsClosedWhenOutcomeRevisionIsUnavailable() {
        var task = mock(ChainPersistenceRecords.TaskRecord.class);
        var outcome = mock(ChainPersistenceRecords.TaskOutcomeRecord.class);
        var readiness = mock(ChainPersistenceRecords
                .FinalizationReadinessRecord.class);
        var terminal = mock(ProductChainTerminalOutcomeAuthority
                .TerminalFacts.class);
        var steps = mock(ProductChainStepAuthorityAdapter.class);
        when(task.taskId()).thenReturn("task-1");
        when(outcome.finalPlanRevisionId()).thenReturn("revision-2");
        when(terminal.readiness()).thenReturn(readiness);
        when(terminal.finalStepId()).thenReturn("step-final");
        when(terminal.activationEventId()).thenReturn("activation-final");
        when(steps.findPlanRevision("task-1", "revision-2"))
                .thenReturn(Optional.empty());

        var failure = assertThrows(IllegalStateException.class, () ->
                ProductChainAnswerDeliveryProgression.terminalPlan(
                        task, outcome, terminal, steps));

        assertEquals("CHAIN_ANSWER_PLAN_REVISION_MISSING",
                failure.getMessage());
    }

    @Test
    void latestDecisionUsesAuthoritySequenceRatherThanAuditTimestamp() {
        var laterTimestampButOlderAuthority = review(
                "review-old", "event-old", NOW.plusSeconds(10));
        var earlierTimestampButNewerAuthority = review(
                "review-new", "event-new", NOW);
        var oldEvent = authority("event-old", 1);
        var newEvent = authority("event-new", 2);

        assertEquals("review-new",
                ProductChainAnswerDeliveryProgression.latestDecisionRef(
                        List.of(laterTimestampButOlderAuthority,
                                earlierTimestampButNewerAuthority),
                        List.of(oldEvent, newEvent)));
    }

    @Test
    void statusAnswerMustCopyExactTerminalAuthorityRefs() {
        ChainPersistenceRecords.TaskOutcomeRecord outcome = failedOutcome();
        var valid = new AnswerPayload.StatusOrFailure(
                outcome.outcomeId(), ChainIdentity.NONE,
                outcome.outcomeId(), "formal failure answer");

        ProductChainAnswerDeliveryProgression.validateTerminalAnswerRefs(
                valid, false, outcome, ChainIdentity.NONE,
                List.of(ChainIdentity.NONE), ChainIdentity.NONE, ChainIdentity.NONE);

        var mismatched = new AnswerPayload.StatusOrFailure(
                outcome.outcomeId(), outcome.sourceDecisionId(),
                outcome.outcomeId(), "formal failure answer");
        assertThrows(ChainProviderProtocolException.class, () ->
                ProductChainAnswerDeliveryProgression.validateTerminalAnswerRefs(
                        mismatched, false, outcome, ChainIdentity.NONE,
                        List.of(ChainIdentity.NONE), ChainIdentity.NONE,
                        ChainIdentity.NONE));
    }

    @Test
    void finalAnswerMustUseFormalArtifactAuthorityRatherThanRawDatabaseId() {
        ChainPersistenceRecords.TaskOutcomeRecord outcome =
                outcomeWithCandidate("candidate.1");
        List<String> formalRefs = List.of(
                ChainIdentity.candidateArtifactRef(41), "candidate.1");
        var valid = new AnswerPayload.FinalDelivery(
                outcome.outcomeId(), formalRefs, ChainIdentity.NONE,
                ChainIdentity.NONE, "formal final answer");

        assertDoesNotThrow(() -> ProductChainAnswerDeliveryProgression
                .validateTerminalAnswerRefs(valid, true, outcome,
                        ChainIdentity.NONE, formalRefs, ChainIdentity.NONE,
                        ChainIdentity.NONE));

        var rawDatabaseId = new AnswerPayload.FinalDelivery(
                outcome.outcomeId(), List.of("41", "candidate.1"),
                ChainIdentity.NONE, ChainIdentity.NONE,
                "formal final answer");
        assertThrows(ChainProviderProtocolException.class, () ->
                ProductChainAnswerDeliveryProgression
                        .validateTerminalAnswerRefs(rawDatabaseId, true,
                                outcome, ChainIdentity.NONE, formalRefs,
                                ChainIdentity.NONE, ChainIdentity.NONE));
    }

    @Test
    void answerContextCarriesTheCompleteFormalValidationIdentity() {
        var identity = ProductChainAnswerDeliveryProgression
                .validationIdentity(new ProductChainTerminalOutcomeAuthority
                                .ValidationIdentity("validation-1",
                                "b".repeat(64), "c".repeat(64)),
                        "validation-1");

        assertEquals("validation-1", identity.validationId());
        assertEquals("b".repeat(64), identity.requestDigest());
        assertEquals("c".repeat(64), identity.receiptDigest());
    }

    @Test
    void answerContextRejectsAnIncompleteFormalValidationIdentity() {
        assertThrows(IllegalStateException.class, () ->
                ProductChainAnswerDeliveryProgression.validationIdentity(
                        new ProductChainTerminalOutcomeAuthority
                                .ValidationIdentity("validation-1",
                                "b".repeat(64), "c".repeat(64)),
                        "validation-other"));
    }

    @Test
    void answerContextResolvesFailureCandidateWhenOutcomeKeyIsFingerprint() {
        var outcome = outcomeWithCandidate(HASH);
        var candidate = candidate("workspace-candidate-1", 41L, HASH);

        assertEquals(HASH, ProductChainAnswerDeliveryProgression
                .outcomeCandidateFingerprint(outcome, List.of(candidate)));
    }

    @Test
    void answerContextResolvesCompletedCandidateWhenOutcomeKeyIsCandidateId() {
        var outcome = outcomeWithCandidate("workspace-candidate-1");
        var candidate = candidate("workspace-candidate-1", 41L, HASH);

        assertEquals(HASH, ProductChainAnswerDeliveryProgression
                .outcomeCandidateFingerprint(outcome, List.of(candidate)));
    }

    @Test
    void answerContextRejectsCandidateFromAnotherArtifact() {
        var outcome = outcomeWithCandidate(HASH);
        var candidate = candidate("workspace-candidate-1", 42L, HASH);

        assertThrows(IllegalStateException.class, () ->
                ProductChainAnswerDeliveryProgression
                        .outcomeCandidateFingerprint(
                                outcome, List.of(candidate)));
    }

    @Test
    void directAnswerPayloadMustReferenceTheSelectedRoute() {
        var direct = new AnswerPayload.DirectAnswer(
                "route-1", "summarize", "answer", List.of("fact-1"));
        var escalation = new AnswerPayload.EscalateToPersistent(
                "route-1", "tool required", List.of("sandbox"),
                List.of(), true);

        assertDoesNotThrow(() -> ProductChainAnswerDeliveryProgression
                .validateDirectPayload(direct, "route-1"));
        assertDoesNotThrow(() -> ProductChainAnswerDeliveryProgression
                .validateDirectRoutePayload(direct, route()));
        assertDoesNotThrow(() -> ProductChainAnswerDeliveryProgression
                .validateDirectPayload(escalation, "route-1"));
        assertThrows(ChainProviderProtocolException.class, () ->
                ProductChainAnswerDeliveryProgression.validateDirectPayload(
                        direct, "route-2"));
        var changedSpecification = new AnswerPayload.DirectAnswer(
                "route-1", "rewrite", "answer", List.of("fact-1"));
        assertThrows(ChainProviderProtocolException.class, () ->
                ProductChainAnswerDeliveryProgression
                        .validateDirectRoutePayload(
                                changedSpecification, route()));
    }

    @Test
    void acceptedDirectProposalRetainsExactInvocationAndContextIdentity() {
        var task = task();
        var instruction = instruction();
        var route = route();
        String contextId = ProductChainAnswerDeliveryProgression
                .directContextId(task.taskId(), route.routeDecisionId());
        String invocationId = "invocation." + sha256(contextId);
        var proposal = proposal(invocationId);
        var invocation = invocation(invocationId, contextId);
        var context = context(contextId);

        assertDoesNotThrow(() -> ProductChainAnswerDeliveryProgression
                .validateDirectInvocationIdentity(
                        task, instruction, route, proposal,
                        invocation, context));

        var crossed = new ChainPersistenceRecords.ModelProposalRecord(
                proposal.proposalId(), proposal.taskId(), "invocation-other",
                proposal.schemaVersion(), proposal.role(),
                proposal.proposalKind(), proposal.payload(),
                proposal.sourceRefs(), proposal.bodyAuthorityType(),
                proposal.bodyAuthorityRef(), proposal.createdAt());
        assertThrows(IllegalStateException.class, () ->
                ProductChainAnswerDeliveryProgression
                        .validateDirectInvocationIdentity(
                                task, instruction, route, crossed,
                                invocation, context));
    }

    @Test
    void acceptedDirectProposalCannotBeBoundToAnotherAuthorityType() {
        String contextId = ProductChainAnswerDeliveryProgression
                .directContextId("task-1", "route-1");
        var proposal = proposal("invocation." + sha256(contextId));
        var accepted = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 1, proposal.taskId(),
                "proposal-accepted-1", ChainProposalState.ACCEPTED,
                null, null, NOW);
        var delivered = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 2, proposal.taskId(),
                "proposal-bound-1",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "DELIVERY", "delivery-1", NOW.plusMillis(1));

        assertDoesNotThrow(() -> ProductChainAnswerDeliveryProgression
                .validateAcceptedStatePrefix(
                        proposal, List.of(accepted, delivered)));

        var wrong = new ChainPersistenceRecords.ProposalStateEventRecord(
                proposal.proposalId(), 2, proposal.taskId(),
                "proposal-bound-wrong",
                ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "ROUTE_DECISION", "route-2", NOW.plusMillis(1));
        assertThrows(IllegalStateException.class, () ->
                ProductChainAnswerDeliveryProgression
                        .validateAcceptedStatePrefix(
                                proposal, List.of(accepted, wrong)));
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord review(
            String id, String eventId, Instant createdAt) {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                id, "task-1", eventId, "proposal-1", "CANDIDATE_STEP_RESULT",
                "candidate-result-1", ChainProposalKind.REFLECTOR_ACCEPT_STEP,
                "reviewed", canonical("[]"), HASH, createdAt);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord authority(
            String eventId, long sequence) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                eventId, "task-1", sequence, "REVIEW_DECISION",
                null, HASH, NOW.plusSeconds(sequence));
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord failedOutcome() {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "outcome-event-1", "command-1",
                ChainTaskOutcomeStatus.FAILED, "instruction-1", "frame-1",
                "plan-1", "revision-1", canonical("[]"), canonical("[]"),
                null, ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null, canonical("[]"),
                canonical("[\"failure\"]"), canonical("[]"),
                "EXECUTION", "STEP_EXECUTION_NOT_COMPLETED",
                "execution-failure-1", NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord outcomeWithCandidate(
            String candidateKey) {
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-candidate", "task-1", "outcome-event-1", "command-1",
                ChainTaskOutcomeStatus.FAILED, "instruction-1", "frame-1",
                "plan-1", "revision-1", canonical("[]"), canonical("[]"),
                41L, candidateKey, ChainIdentity.NONE,
                null, null, null, null, canonical("[]"),
                canonical("[\"failure\"]"), canonical("[]"),
                "EXECUTION", "STEP_EXECUTION_NOT_COMPLETED",
                "execution-failure-1", NOW);
    }

    private static ChainPersistenceRecords.WorkspaceCandidateRecord candidate(
            String candidateId, long artifactId, String fingerprint) {
        return new ChainPersistenceRecords.WorkspaceCandidateRecord(
                candidateId, "task-1", "candidate-event-1",
                "candidate-action-1", "workspace-1", "project-version-1",
                artifactId, fingerprint, HASH, HASH, NOW);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                1L, 2L, 3L, 4L, "request-1", HASH,
                null, null, 1L, NOW);
    }

    private static ChainPersistenceRecords.InstructionRecord instruction() {
        return new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 2L, "task-1", 4L,
                HASH, "message-1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
    }

    private static ChainPersistenceRecords.RouteDecisionRecord route() {
        return new ChainPersistenceRecords.RouteDecisionRecord(
                "route-1", "task-1", "route-event-1", "instruction-1",
                "planner-proposal-1",
                ChainPersistenceRecords.RouteDecisionType.INITIAL, 0,
                ChainExecutionMode.DIRECT, "direct", canonical(
                "{\"specification\":\"summarize\"}"),
                canonical("[]"), canonical("[\"fact-1\"]"),
                false, false, false, false,
                null, null, null, NOW);
    }

    private static ChainPersistenceRecords.ModelProposalRecord proposal(
            String invocationId) {
        return new ChainPersistenceRecords.ModelProposalRecord(
                "answer-proposal-1", "task-1", invocationId, 1,
                ChainRole.ANSWER, ChainProposalKind.ANSWER_DIRECT_ANSWER,
                canonical("{}"), canonical("[]"),
                "ANSWER_BODY", "answer-body-1", NOW);
    }

    private static ChainPersistenceRecords.ModelInvocationRecord invocation(
            String invocationId, String contextId) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                invocationId, "task-1", contextId, "completion-token-1",
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                "DIRECT_ROUTE", "provider", "model", 1,
                "chain-runtime-policy-v1", NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord context(
            String contextId) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                contextId, "task-1", null,
                ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                "DIRECT_ROUTE", "instruction-1",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "chain-product-projector-v1", "v1",
                "chain-runtime-policy-v1",
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"),
                HASH, "completion-token-1", null, null,
                NOW, NOW.plusMillis(1));
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
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
