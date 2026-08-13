package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.instruction.ChainInstructionState;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Selects a model role mechanically; Proposal prose is never an input. */
public final class ChainRoleRouter {
    private final ChainInstructionStateReader instructions;
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final StepRoutingAuthority steps;

    public ChainRoleRouter(
            ChainInstructionStateReader instructions,
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            StepRoutingAuthority steps) {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.steps = Objects.requireNonNull(steps, "steps");
    }

    public RoutingResult next(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        ChainInstructionState instruction = instructions.read(taskId);
        AuthorityOrder authority = AuthorityOrder.load(foundations, taskId);
        var outcome = finalization.findTaskOutcome(taskId);
        if (outcome.isPresent()) {
            authority.sequence(outcome.get(), "TASK_OUTCOME", false);
            ChainWorkState state = outcome.get().outcomeType()
                    == io.paperagent.v2.chain.ChainTaskOutcomeStatus.COMPLETED
                    ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL;
            return new RoutingResult.ModelCall(
                    ChainRole.ANSWER, state, "formal task outcome", outcome.get().outcomeId());
        }
        if (instruction.gate() == ChainInstructionState.Gate.CANCELLED
                || instruction.gate() == ChainInstructionState.Gate.SUPERSEDED
                || instruction.gate() == ChainInstructionState.Gate.TERMINAL) {
            return new RoutingResult.ControlOnly(
                    "task outcome must be committed before another model call",
                    instruction.gateAuthorityRef());
        }
        if (instruction.gate()
                == ChainInstructionState.Gate.PAUSED_FOR_DISPOSITION) {
            return new RoutingResult.ModelCall(
                    ChainRole.PLANNER, ChainWorkState.CLASSIFYING_INSTRUCTION,
                    "new instruction requires formal disposition",
                    instruction.gateAuthorityRef());
        }

        RoutingResult pending = pendingRoute(taskId, authority);
        if (pending != null) {
            return pending;
        }
        if (instruction.gate()
                == ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION) {
            return new RoutingResult.ControlOnly(
                    "pending-item response is not bound to one open formal gap",
                    instruction.gateAuthorityRef());
        }
        if (instruction.gate() == ChainInstructionState.Gate.DIRECT_ANSWER) {
            return new RoutingResult.ModelCall(
                    ChainRole.ANSWER, ChainWorkState.DIRECT_ANSWERING,
                    "formal DIRECT route", instruction.gateAuthorityRef());
        }

        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates =
                workflow.findCandidateStepResults(taskId);
        List<ChainPersistenceRecords.ReviewDecisionRecord> reviews =
                workflow.findReviewDecisions(taskId);
        candidates.forEach(candidate -> authority.sequence(
                candidate, "CANDIDATE_STEP_RESULT", false));
        reviews.forEach(review -> authority.sequence(
                review, "REVIEW_DECISION", false));
        ChainPersistenceRecords.CandidateStepResultRecord unreviewed = candidates.stream()
                .filter(candidate -> reviews.stream().noneMatch(review ->
                        reviewsCandidate(review, candidate, authority)))
                .max(Comparator.comparingLong(candidate -> authority.sequence(
                        candidate, "CANDIDATE_STEP_RESULT", false)))
                .orElse(null);
        if (unreviewed != null) {
            return new RoutingResult.ModelCall(
                    ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                    "formal candidate result awaits review", unreviewed.candidateResultId());
        }
        ChainPersistenceRecords.ReviewDecisionRecord latestReview = reviews.stream()
                .max(Comparator.comparingLong(review -> authority.sequence(
                        review, "REVIEW_DECISION", false))).orElse(null);
        if (latestReview != null) {
            RoutingResult reviewRoute = routeReview(
                    taskId,
                    instruction.currentInstruction().instructionId(),
                    latestReview, authority);
            if (reviewRoute != null) {
                return reviewRoute;
            }
        }

        List<ChainPersistenceRecords.PlanBindingRecord> plans = workflow
                .findPlanBindings(taskId).stream()
                .filter(plan -> instruction.currentInstruction().instructionId()
                        .equals(plan.instructionId()))
                .toList();
        if (!plans.isEmpty() && instruction.allowsNewSideEffects()) {
            plans.forEach(plan -> authority.sequence(
                    plan, "PLAN_BINDING", false));
            ChainPersistenceRecords.PlanBindingRecord plan = plans.stream()
                    .max(Comparator.comparingLong(value -> authority.sequence(
                            value, "PLAN_BINDING", false))).orElseThrow();
            return executorForActiveStep(
                    taskId, plan.planRevisionId(),
                    null,
                    authority.sequence(plan, "PLAN_BINDING", false),
                    true,
                    "formal persistent plan awaits Step activation",
                    plan.planBindingId());
        }
        return new RoutingResult.ModelCall(
                ChainRole.PLANNER, ChainWorkState.PLANNING,
                "no formal route or persistent plan successor",
                instruction.gateAuthorityRef());
    }

    private RoutingResult pendingRoute(
            String taskId,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.PendingItemRecord> open =
                workflow.findOpenPendingItems(taskId);
        open.forEach(item -> authority.sequence(
                item, "PENDING_ITEM", false));
        if (open.isEmpty()) {
            return null;
        }
        if (open.size() != 1) {
            return new RoutingResult.ControlOnly(
                    "multiple open gaps require mechanical answer binding",
                    taskId);
        }
        ChainPersistenceRecords.PendingItemRecord item = open.get(0);
        List<ChainPersistenceRecords.PendingItemEventRecord> pendingEvents = workflow
                .findPendingItemEvents(item.gapId());
        pendingEvents.forEach(event -> authority.sequence(
                event, "PENDING_ITEM_" + event.eventKind().name(), false));
        List<ChainPersistenceRecords.PendingItemEventRecord> events = pendingEvents.stream()
                .sorted(Comparator.comparingLong(event -> authority.sequence(
                        event, "PENDING_ITEM_" + event.eventKind().name(), false)))
                .toList();
        ChainPendingItemStatus status = events.isEmpty() ? ChainPendingItemStatus.PENDING
                : events.get(events.size() - 1).eventKind();
        if (status == ChainPendingItemStatus.RESPONSE_RECEIVED) {
            return new RoutingResult.ModelCall(item.validationRole(),
                    ChainWorkState.VALIDATING_PENDING_ITEM,
                    "formal pending-item response requires validation", item.gapId());
        }
        if (status == ChainPendingItemStatus.PENDING) {
            ChainWorkState state = item.pendingType() == ChainPendingItemType.PERMISSION
                    ? ChainWorkState.WAITING_PERMISSION : ChainWorkState.WAITING_USER;
            return new RoutingResult.ModelCall(
                    ChainRole.ANSWER, state, "formal pending item", item.gapId());
        }
        return null;
    }

    private RoutingResult routeReview(
            String taskId,
            String currentInstructionId,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            AuthorityOrder authority) {
        return switch (review.decisionKind()) {
            case REFLECTOR_CONTINUE_STEP -> routeContinuedStep(
                    taskId, review, authority);
            case REFLECTOR_ACCEPT_STEP -> routeAcceptedStep(
                    taskId, review, authority);
            case REFLECTOR_REPLAN_REQUIRED -> routeReplan(
                    taskId, currentInstructionId, review, authority);
            case REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE,
                    REFLECTOR_READY_TO_FINALIZE -> routeReadiness(
                    taskId, review, authority);
            case REFLECTOR_TASK_FAILED -> new RoutingResult.ControlOnly(
                    "formal failed review awaits TaskOutcome", review.reviewDecisionId());
            case REFLECTOR_NEED_USER_INPUT -> routeUserInput(
                    taskId, review, authority);
            case REFLECTOR_NEED_PERMISSION -> new RoutingResult.ControlOnly(
                    "formal permission review awaits permission re-intake",
                    review.reviewDecisionId());
            default -> throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "non-Reflector proposal kind was stored as ReviewDecision");
        };
    }

    private RoutingResult routeReplan(
            String taskId,
            String currentInstructionId,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            AuthorityOrder authority) {
        long reviewSequence = authority.sequence(
                review, "REVIEW_DECISION", false);
        List<ChainPersistenceRecords.PlanBindingRecord> currentPlans = workflow
                .findPlanBindings(taskId).stream()
                .filter(plan -> currentInstructionId.equals(
                        plan.instructionId()))
                .toList();
        currentPlans.forEach(plan -> authority.sequence(
                plan, "PLAN_BINDING", false));
        ChainPersistenceRecords.PlanBindingRecord successor = currentPlans.stream()
                .filter(plan -> authority.sequence(
                        plan, "PLAN_BINDING", false) > reviewSequence)
                .max(Comparator.comparingLong(plan -> authority.sequence(
                        plan, "PLAN_BINDING", false)))
                .orElse(null);
        if (successor == null) {
            return new RoutingResult.ModelCall(
                    ChainRole.PLANNER, ChainWorkState.PLANNING,
                    "formal review requires replan",
                    review.reviewDecisionId());
        }
        if (successor.transitionId() == null
                || !steps.isTransitionComplete(
                taskId, successor.transitionId(),
                ChainTransitionType.PLAN_CHANGE)) {
            return new RoutingResult.ControlOnly(
                    "new Plan binding awaits its complete PLAN_CHANGE transition",
                    successor.planBindingId());
        }
        return null;
    }

    private RoutingResult routeUserInput(
            String taskId,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.PendingItemRecord> matching = workflow
                .findPendingItems(taskId).stream()
                .filter(item -> review.proposalId().equals(
                        item.sourceProposalId()))
                .toList();
        matching.forEach(item -> authority.sequence(
                item, "PENDING_ITEM", false));
        if (matching.size() != 1) {
            return new RoutingResult.ControlOnly(
                    "formal review awaits its uniquely bound PendingItem",
                    review.reviewDecisionId());
        }
        ChainPersistenceRecords.PendingItemRecord item = matching.get(0);
        long reviewSequence = authority.sequence(
                review, "REVIEW_DECISION", false);
        long itemSequence = authority.sequence(
                item, "PENDING_ITEM", false);
        if (item.pendingType() == ChainPendingItemType.PERMISSION
                || item.resumeRole() != ChainRole.EXECUTOR
                || itemSequence <= reviewSequence) {
            return new RoutingResult.ControlOnly(
                    "PendingItem does not authorize Step execution resume",
                    item.gapId());
        }
        List<ChainPersistenceRecords.PendingItemEventRecord> events = workflow
                .findPendingItemEvents(item.gapId());
        events.forEach(event -> {
            if (!item.gapId().equals(event.gapId())) {
                throw failure(
                        ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                        "PendingItem event belongs to another gap");
            }
            authority.sequence(
                    event, "PENDING_ITEM_" + event.eventKind().name(), false);
        });
        ChainPersistenceRecords.PendingItemEventRecord latest = events.stream()
                .max(Comparator.comparingLong(event -> authority.sequence(
                        event, "PENDING_ITEM_" + event.eventKind().name(), false)))
                .orElse(null);
        if (latest == null
                || latest.eventKind() != ChainPendingItemStatus.RESOLVED
                || authority.sequence(
                latest, "PENDING_ITEM_RESOLVED", false) <= itemSequence) {
            return new RoutingResult.ControlOnly(
                    "formal user gap has not resolved",
                    item.gapId());
        }
        return null;
    }

    private RoutingResult routeContinuedStep(
            String taskId,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            AuthorityOrder authority) {
        if (!"CANDIDATE_STEP_RESULT".equals(review.reviewObjectType())) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "CONTINUE_STEP must review one candidate Step result");
        }
        ChainPersistenceRecords.CandidateStepResultRecord candidate = workflow
                .findCandidateStepResults(taskId).stream()
                .filter(value -> review.reviewObjectId().equals(
                        value.candidateResultId()))
                .findFirst()
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                        "CONTINUE_STEP candidate does not exist"));
        long candidateSequence = authority.sequence(
                candidate, "CANDIDATE_STEP_RESULT", false);
        long reviewSequence = authority.sequence(
                review, "REVIEW_DECISION", false);
        if (candidateSequence >= reviewSequence) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "CONTINUE_STEP review does not follow its candidate");
        }
        return executorForActiveStep(
                taskId, candidate.planRevisionId(),
                candidate.activationEventId(), reviewSequence, false,
                "CONTINUE_STEP awaits its formally resumed ACTIVE Step",
                review.reviewDecisionId());
    }

    private RoutingResult routeAcceptedStep(
            String taskId,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.AcceptedResultRecord> acceptedResults = workflow
                .findAcceptedResults(taskId);
        acceptedResults.forEach(result -> authority.sequence(
                result, "ACCEPTED_RESULT", false));
        ChainPersistenceRecords.AcceptedResultRecord accepted = acceptedResults.stream()
                .filter(result -> review.reviewDecisionId().equals(
                        result.reviewDecisionId()))
                .max(Comparator.comparingLong(result -> authority.sequence(
                        result, "ACCEPTED_RESULT", false)))
                .orElse(null);
        if (accepted == null
                || !steps.isTransitionComplete(
                taskId, accepted.transitionId(),
                ChainTransitionType.ACCEPT_STEP)) {
            return new RoutingResult.ControlOnly(
                    "ACCEPT_STEP awaits its complete formal successor transition",
                    accepted == null
                            ? review.reviewDecisionId()
                            : accepted.transitionId());
        }
        if (authority.sequence(accepted, "ACCEPTED_RESULT", false)
                <= authority.sequence(review, "REVIEW_DECISION", false)) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "accepted result does not follow its accepting review");
        }
        ChainPersistenceRecords.CandidateStepResultRecord candidate = workflow
                .findCandidateStepResults(taskId).stream()
                .filter(value -> accepted.candidateResultId().equals(
                        value.candidateResultId()))
                .findFirst()
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                        "accepted result candidate does not exist"));
        authority.sequence(candidate, "CANDIDATE_STEP_RESULT", false);
        if (!"CANDIDATE_STEP_RESULT".equals(review.reviewObjectType())
                || !candidate.candidateResultId().equals(
                review.reviewObjectId())) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "accepting review does not bind the accepted candidate");
        }
        return executorForActiveStep(
                taskId, candidate.planRevisionId(),
                null,
                authority.sequence(review, "REVIEW_DECISION", false),
                true,
                "completed ACCEPT_STEP transition has no ACTIVE successor Step",
                accepted.transitionId());
    }

    private RoutingResult routeReadiness(
            String taskId,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.FinalizationReadinessRecord> readinessFacts =
                finalization.findReadiness(taskId);
        readinessFacts.forEach(value -> authority.sequence(
                value, "FINALIZATION_READINESS", false));
        ChainPersistenceRecords.FinalizationReadinessRecord readiness = readinessFacts.stream()
                .filter(value -> review.reviewDecisionId().equals(
                        value.reviewDecisionId()))
                .max(Comparator.comparingLong(value -> authority.sequence(
                        value, "FINALIZATION_READINESS", false)))
                .orElse(null);
        if (readiness == null
                || !steps.isTransitionComplete(
                taskId, readiness.transitionId(),
                ChainTransitionType.FINAL_STEP_READINESS)) {
            return new RoutingResult.ControlOnly(
                    "formal READY review awaits finalization readiness",
                    readiness == null
                            ? review.reviewDecisionId()
                            : readiness.transitionId());
        }
        if (authority.sequence(
                readiness, "FINALIZATION_READINESS", false)
                <= authority.sequence(review, "REVIEW_DECISION", false)) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "readiness does not follow its READY review");
        }
        return new RoutingResult.ControlOnly(
                "formal readiness awaits mechanical finalization",
                readiness.readinessId());
    }

    private RoutingResult executorForActiveStep(
            String taskId,
            String requiredPlanRevisionId,
            String requiredActivationEventId,
            long predecessorAuthoritySequence,
            boolean mustFollowPredecessor,
            String waitingReason,
            String waitingAuthorityRef) {
        StepRoutingAuthority.ActiveStep active = steps.findActiveStep(taskId)
                .orElse(null);
        if (active == null) {
            return new RoutingResult.ControlOnly(
                    waitingReason, waitingAuthorityRef);
        }
        if (!taskId.equals(active.taskId())
                || (requiredPlanRevisionId != null
                && !requiredPlanRevisionId.equals(active.planRevisionId()))
                || (requiredActivationEventId != null
                && !requiredActivationEventId.equals(
                active.activationEventId()))
                || (mustFollowPredecessor
                && active.authoritySequence() <= predecessorAuthoritySequence)) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "ACTIVE Step does not follow the formal routing predecessor");
        }
        return new RoutingResult.ModelCall(
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "formal ACTIVE Step", active.activationEventId());
    }

    private static boolean reviewsCandidate(
            ChainPersistenceRecords.ReviewDecisionRecord review,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            AuthorityOrder authority) {
        if (!candidate.taskId().equals(review.taskId())
                || !"CANDIDATE_STEP_RESULT".equals(
                review.reviewObjectType())
                || !candidate.candidateResultId().equals(
                review.reviewObjectId())) {
            return false;
        }
        return authority.sequence(review, "REVIEW_DECISION", false)
                > authority.sequence(
                candidate, "CANDIDATE_STEP_RESULT", false);
    }

    private static ChainRouteException failure(
            ChainRouteException.Code code, String message) {
        return new ChainRouteException(code, message);
    }

    private record AuthorityOrder(
            String taskId,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId) {
        static AuthorityOrder load(
                ChainFoundationRepository foundations,
                String taskId) {
            List<ChainPersistenceRecords.AuthorityEventRecord> events =
                    foundations.findAuthorityEvents(
                            taskId,
                            foundations.highestAuthorityEventSequence(taskId));
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId =
                    new HashMap<>();
            Set<Long> sequences = new HashSet<>();
            for (ChainPersistenceRecords.AuthorityEventRecord event : events) {
                if (!taskId.equals(event.taskId())
                        || byId.put(event.eventId(), event) != null
                        || !sequences.add(event.eventSequence())) {
                    throw failure(
                            ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                            "task authority event prefix is inconsistent");
                }
            }
            return new AuthorityOrder(taskId, Map.copyOf(byId));
        }

        long sequence(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String expectedType,
                boolean prefixMatch) {
            ChainPersistenceRecords.AuthorityEventRecord event = byId.get(
                    fact.eventId());
            boolean typeMatches = event != null && (expectedType == null
                    || (prefixMatch
                    ? event.eventType().startsWith(expectedType)
                    : event.eventType().equals(expectedType)));
            if (!taskId.equals(fact.taskId())
                    || event == null
                    || !taskId.equals(event.taskId())
                    || !typeMatches) {
                throw failure(
                        ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                        "workflow fact lacks its ordered formal authority event");
            }
            return event.eventSequence();
        }
    }

    public sealed interface RoutingResult
            permits RoutingResult.ModelCall, RoutingResult.ControlOnly {
        String reason();
        String authorityRef();

        record ModelCall(
                ChainRole role,
                ChainWorkState workState,
                String reason,
                String authorityRef) implements RoutingResult {
            public ModelCall {
                Objects.requireNonNull(role, "role");
                Objects.requireNonNull(workState, "workState");
                reason = required(reason, "reason");
                authorityRef = required(authorityRef, "authorityRef");
            }
        }

        record ControlOnly(String reason, String authorityRef) implements RoutingResult {
            public ControlOnly {
                reason = required(reason, "reason");
                authorityRef = required(authorityRef, "authorityRef");
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
