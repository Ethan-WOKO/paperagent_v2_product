package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPermissionDecision;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductReviewPendingContextProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-08T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void noModuleFactsIsEmptyEvenAtANonzeroFormalTaskCut() {
        var fixture = fixture();
        authority(fixture, List.of(event("unrelated.event", 17)));

        var projection = fixture.projector.read(request(building(
                ChainRole.PLANNER)));

        assertEquals(ChainContextModuleStatus.EMPTY,
                projection.presenceKind());
        assertEquals("allCuts=0", projection.emptyWatermark());
        assertEquals(17, number(projection.readBoundaryComponents(),
                "taskEventCut"));
        assertEquals(0, number(projection.sourceVersionComponents(),
                "pendingCut"));
    }

    @Test
    void plannerUsesFormalOrderForReviewGapDispositionAndResumePosition() {
        var fixture = fixture();
        var review = review("review.replan", "review.event", "PLAN_REVISION",
                "revision.1", ChainProposalKind.REFLECTOR_REPLAN_REQUIRED);
        var latest = review("review.latest", "review.latest.event", "TASK",
                "task.1", ChainProposalKind.REFLECTOR_TASK_FAILED);
        var pending = pending("gap.1", "pending.event",
                ChainPendingItemType.USER_INFORMATION, null,
                ChainRole.PLANNER);
        var disposition = disposition("disposition.event", "instruction.1");
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(latest, review));
        when(fixture.workflow.findPendingItems("task.1"))
                .thenReturn(List.of(pending));
        when(fixture.workflow.findInstructionDispositions("task.1"))
                .thenReturn(List.of(disposition));
        authority(fixture, List.of(event(review.eventId(), 3),
                event(latest.eventId(), 5),
                event(pending.eventId(), 8),
                event(disposition.eventId(), 13)));

        var projection = fixture.projector.read(request(building(
                ChainRole.PLANNER)));

        assertEquals("review.latest", text(object(
                projection.projectionFields(), "review.latestDecision"),
                "reviewDecisionRef"));
        assertEquals("REPLAN_REQUIRED", text(object(
                projection.projectionFields(), "review.replanGap"),
                "decisionKind"));
        assertEquals("disposition.1", text(object(
                projection.projectionFields(),
                "review.instructionDisposition"), "dispositionRef"));
        assertEquals("{\"position\":\"STEP\"}", text(object(
                projection.projectionFields(), "review.resumePosition"),
                "json"));
        assertEquals(13, number(object(
                projection.sourceVersionComponents(), "reviewCut"),
                "instructionDispositionEventSequence"));
    }

    @Test
    void executorSeesOnlyExactCandidateBindingAndItsPreviousGap() {
        var fixture = fixture();
        var oldCandidate = candidate("candidate.old", "candidate.event.old",
                "step.1", "activation.old");
        var currentCandidate = candidate("candidate.current",
                "candidate.event.current", "step.1", "activation.1");
        var oldReview = review("review.old", "review.event.old",
                "CANDIDATE_STEP_RESULT", oldCandidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_ACCEPT_STEP);
        var currentReview = review("review.current", "review.event.current",
                "CANDIDATE_STEP_RESULT", currentCandidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_CONTINUE_STEP);
        var previousGap = pending("gap.previous", "pending.event",
                ChainPendingItemType.USER_INFORMATION, null,
                ChainRole.EXECUTOR);
        var resolved = pendingEvent(previousGap.gapId(), "resolved.event",
                ChainPendingItemStatus.RESOLVED);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(currentCandidate, oldCandidate));
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(currentReview, oldReview));
        when(fixture.workflow.findPendingItems("task.1"))
                .thenReturn(List.of(previousGap));
        when(fixture.workflow.findPendingItemEvents(previousGap.gapId()))
                .thenReturn(List.of(resolved));
        authority(fixture, List.of(
                event(oldCandidate.eventId(), 2), event(oldReview.eventId(), 3),
                event(currentCandidate.eventId(), 5),
                event(currentReview.eventId(), 7),
                event(previousGap.eventId(), 11), event(resolved.eventId(), 12)));

        var projection = fixture.projector.read(request(building(
                ChainRole.EXECUTOR)));

        assertEquals("review.current", text(object(
                projection.projectionFields(), "review.latestDecision"),
                "reviewDecisionRef"));
        assertEquals("gap.previous", text(object(
                projection.projectionFields(), "review.previousReviewGap"),
                "gapRef"));
        assertEquals(1, number(object(projection.projectionFields(),
                "review.loopState"), "relatedDecisionCount"));
    }

    @Test
    void reflectorGetsExactHistoryAndFormallyOrderedIncompleteTransition() {
        var fixture = fixture();
        var candidate = candidate("candidate.1", "candidate.event",
                "step.1", "activation.1");
        var review = review("review.1", "review.event",
                "CANDIDATE_STEP_RESULT", candidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_CONTINUE_STEP);
        var transition = transition("review.1", ChainTransitionType.ACCEPT_STEP);
        var open = stage(transition, "transition.open", 0,
                ChainTransitionStage.OPEN);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(review));
        when(fixture.workflow.findTransition(transition.transitionId()))
                .thenReturn(Optional.of(transition));
        when(fixture.workflow.findTransitionStages(transition.transitionId()))
                .thenReturn(List.of(open));
        authority(fixture, List.of(event(candidate.eventId(), 2),
                event(review.eventId(), 4),
                event(transition.eventId(), 6, transition.transitionId()),
                event(open.eventId(), 9, transition.transitionId())));

        var projection = fixture.projector.read(request(building(
                ChainRole.REFLECTOR)));

        var history = array(projection.projectionFields(),
                "review.objectBoundDecisionHistory");
        assertEquals("review.1", text(object(history, 0),
                "reviewDecisionRef"));
        var transitions = array(object(projection.projectionFields(),
                "review.loopState"), "incompleteTransitions");
        assertEquals(transition.transitionId(), text(object(transitions, 0),
                "transitionRef"));
        assertEquals(9, number(projection.sourceVersionComponents(),
                "transitionCut"));
    }

    @Test
    void answerRetainsLatestPermissionDecisionAfterGapIsResolved() {
        var fixture = fixture();
        var permissionGap = pending("gap.permission", "pending.event",
                ChainPendingItemType.PERMISSION, "workspace.write",
                ChainRole.PLANNER);
        var decision = new ChainPersistenceRecords.PermissionDecisionRecord(
                "permission.1", "task.1", "permission.event",
                permissionGap.gapId(), "workspace.write", "USER_GRANT",
                "grant.1", ChainPermissionDecision.GRANTED, "approved", NOW);
        var resolved = pendingEvent(permissionGap.gapId(), "resolved.event",
                ChainPendingItemStatus.RESOLVED);
        when(fixture.workflow.findPendingItems("task.1"))
                .thenReturn(List.of(permissionGap));
        when(fixture.workflow.findPendingItemEvents(permissionGap.gapId()))
                .thenReturn(List.of(resolved));
        when(fixture.workflow.findPermissionDecisions("task.1"))
                .thenReturn(List.of(decision));
        authority(fixture, List.of(event(permissionGap.eventId(), 2),
                event(decision.eventId(), 4), event(resolved.eventId(), 5)));

        var projection = fixture.projector.read(request(building(
                ChainRole.ANSWER)));

        var answer = object(projection.projectionFields(),
                "review.currentQuestionPermissionFailureCompletionOrInstructionDecision");
        assertEquals("permission.1", text(object(answer,
                "permissionDecision"), "permissionDecisionRef"));
        assertEquals("GRANTED", text(object(answer,
                "permissionDecision"), "decision"));
    }

    @Test
    void missingFormalReviewEventIsTypedBlockedInsteadOfHiddenAsEmpty() {
        var fixture = fixture();
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(review("review.1", "missing.event",
                        "PLAN_REVISION", "revision.1",
                        ChainProposalKind.REFLECTOR_REPLAN_REQUIRED)));
        authority(fixture, List.of(event("unrelated.event", 3)));

        assertTypedBlocked(() -> fixture.projector.read(request(building(
                ChainRole.PLANNER))));
    }

    @Test
    void candidateFenceMismatchIsTypedBlocked() {
        var fixture = fixture();
        var candidate = candidate("candidate.1", "candidate.event",
                "step.1", "activation.1");
        var review = new ChainPersistenceRecords.ReviewDecisionRecord(
                "review.1", "task.1", "review.event", "proposal.review.1",
                "CANDIDATE_STEP_RESULT", candidate.candidateResultId(),
                ChainProposalKind.REFLECTOR_ACCEPT_STEP, "accepted", json("[]"),
                "b".repeat(64), NOW);
        when(fixture.workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of(candidate));
        when(fixture.workflow.findReviewDecisions("task.1"))
                .thenReturn(List.of(review));
        authority(fixture, List.of(event(candidate.eventId(), 2),
                event(review.eventId(), 3)));

        assertTypedBlocked(() -> fixture.projector.read(request(building(
                ChainRole.REFLECTOR))));
    }

    private static Fixture fixture() {
        ChainFoundationRepository foundations =
                mock(ChainFoundationRepository.class);
        ProductChainWorkflowRepositoryAdapter workflow =
                mock(ProductChainWorkflowRepositoryAdapter.class);
        when(foundations.findTask("task.1")).thenReturn(Optional.of(
                mock(ChainPersistenceRecords.TaskRecord.class)));
        when(workflow.findCandidateStepResults("task.1"))
                .thenReturn(List.of());
        when(workflow.findReviewDecisions("task.1")).thenReturn(List.of());
        when(workflow.findPendingItems("task.1")).thenReturn(List.of());
        when(workflow.findPermissionDecisions("task.1"))
                .thenReturn(List.of());
        when(workflow.findInstructionDispositions("task.1"))
                .thenReturn(List.of());
        return new Fixture(foundations, workflow,
                new ProductReviewPendingContextProjector(
                        foundations, workflow));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            ChainRole role) {
        ChainWorkState state = switch (role) {
            case PLANNER -> ChainWorkState.PLANNING;
            case EXECUTOR -> ChainWorkState.EXECUTING;
            case REFLECTOR -> ChainWorkState.AWAITING_REVIEW;
            case ANSWER -> ChainWorkState.DELIVERING;
        };
        return new ChainPersistenceRecords.ContextRevisionRecord(
                "context.1", "task.1", null, role, state, "TEST",
                "instruction.1", "frame.1", "plan.1", "revision.1", 1L,
                "step.1", "activation.1", null, null, "workspace.1",
                null, null, null, null, null, "projectors.v1", "pages.v1",
                "policy.v1", ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static ChainContextProjectionRequest request(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        return new ChainContextProjectionRequest(building, 1_000_000);
    }

    private static ChainPersistenceRecords.CandidateStepResultRecord candidate(
            String id, String eventId, String stepId, String activationId) {
        return new ChainPersistenceRecords.CandidateStepResultRecord(
                id, "task.1", eventId, "proposal." + id, "content." + id,
                "instruction.1", "frame.1", "plan.1", "revision.1", 1,
                stepId, activationId, null, null, null, json("[]"), null,
                null, null, json("[]"), HASH, NOW);
    }

    private static ChainPersistenceRecords.ReviewDecisionRecord review(
            String id, String eventId, String objectType, String objectId,
            ChainProposalKind decision) {
        return new ChainPersistenceRecords.ReviewDecisionRecord(
                id, "task.1", eventId, "proposal." + id, objectType,
                objectId, decision, "formal reason", json("[]"), HASH, NOW);
    }

    private static ChainPersistenceRecords.PendingItemRecord pending(
            String gapId, String eventId, ChainPendingItemType type,
            String permissionScope, ChainRole resumeRole) {
        return new ChainPersistenceRecords.PendingItemRecord(
                gapId, "task.1", eventId, "proposal.pending", type, HASH,
                json("[]"), permissionScope, "question", "plain text",
                ChainRole.PLANNER, resumeRole,
                json("{\"position\":\"STEP\"}"), HASH, NOW);
    }

    private static ChainPersistenceRecords.PendingItemEventRecord pendingEvent(
            String gapId, String eventId, ChainPendingItemStatus status) {
        return new ChainPersistenceRecords.PendingItemEventRecord(
                gapId, 1, status, "task.1", eventId, null, null, null,
                json("{}"), NOW);
    }

    private static ChainPersistenceRecords.InstructionDispositionRecord
            disposition(String eventId, String instructionId) {
        return new ChainPersistenceRecords.InstructionDispositionRecord(
                "disposition.1", "task.1", eventId, "proposal.disposition",
                instructionId, "CONTINUE", "KEEP", false, "CURRENT_STEP",
                false, json("{}"), json("[]"), NOW);
    }

    private static ChainPersistenceRecords.TransitionRecord transition(
            String sourceDecisionId, ChainTransitionType type) {
        String id = new ChainIdentity.Transition(type, "task.1",
                sourceDecisionId, HASH).transitionId();
        return new ChainPersistenceRecords.TransitionRecord(
                id, "task.1", "transition.event", type, sourceDecisionId,
                HASH, NOW);
    }

    private static ChainPersistenceRecords.TransitionStageRecord stage(
            ChainPersistenceRecords.TransitionRecord transition,
            String eventId, int ordinal, ChainTransitionStage stage) {
        return new ChainPersistenceRecords.TransitionStageRecord(
                transition.transitionId(), stage, "task.1", eventId, ordinal,
                null, null, null, null, NOW);
    }

    private static void authority(
            Fixture fixture,
            List<ChainPersistenceRecords.AuthorityEventRecord> events) {
        long cut = events.stream().mapToLong(
                ChainPersistenceRecords.AuthorityEventRecord::eventSequence)
                .max().orElse(0);
        when(fixture.foundations.highestAuthorityEventSequence("task.1"))
                .thenReturn(cut);
        when(fixture.foundations.findAuthorityEvents("task.1", cut))
                .thenReturn(events);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord event(
            String eventId, long sequence) {
        return event(eventId, sequence, null);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord event(
            String eventId, long sequence, String transitionId) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                eventId, "task.1", sequence, "TEST", transitionId,
                HASH, NOW);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String body) {
        return new ChainPersistenceRecords.CanonicalJson(1, HASH, body);
    }

    private static void assertTypedBlocked(
            org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(ChainContextException.class, executable);
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static ChainContextValue.ArrayValue array(
            Map<String, ChainContextValue> values, String key) {
        return (ChainContextValue.ArrayValue) values.get(key);
    }

    private static Map<String, ChainContextValue> object(
            Map<String, ChainContextValue> values, String key) {
        return ((ChainContextValue.ObjectValue) values.get(key)).values();
    }

    private static Map<String, ChainContextValue> object(
            ChainContextValue.ArrayValue values, int index) {
        return ((ChainContextValue.ObjectValue) values.values().get(index))
                .values();
    }

    private static String text(Map<String, ChainContextValue> values,
                               String key) {
        return ((ChainContextValue.Text) values.get(key)).value();
    }

    private static long number(Map<String, ChainContextValue> values,
                               String key) {
        return ((ChainContextValue.NumberValue) values.get(key)).value();
    }

    private record Fixture(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductReviewPendingContextProjector projector) {
    }
}
