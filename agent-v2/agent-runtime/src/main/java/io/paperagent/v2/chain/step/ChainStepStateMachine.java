package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords.AcceptedResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CandidateStepResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PendingItemRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ResultApplicabilityRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ReviewDecisionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.PlanSnapshot;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepDefinition;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEvent;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEventCommand;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort.StepEventKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Derives the effective Step state exclusively from formal authorities. */
public final class ChainStepStateMachine {
    private final ChainStepAuthorityPort steps;
    private final ChainWorkflowRepository workflows;
    private final ChainFoundationRepository foundations;
    private final ChainModelRepository models;
    private final ChainContextRepository contexts;

    public ChainStepStateMachine(
            ChainStepAuthorityPort steps,
            ChainWorkflowRepository workflows,
            ChainFoundationRepository foundations,
            ChainModelRepository models,
            ChainContextRepository contexts) {
        this.steps = Objects.requireNonNull(steps, "steps");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    public PlanState derive(String taskId, String planRevisionId) {
        required(taskId, "taskId");
        required(planRevisionId, "planRevisionId");
        PlanSnapshot plan = steps.findPlan(taskId, planRevisionId)
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_PLAN_NOT_FOUND",
                        "stable Plan revision does not exist"));
        if (!plan.taskId().equals(taskId)
                || !plan.planRevisionId().equals(planRevisionId)) {
            throw failure("CHAIN_STEP_PLAN_IDENTITY_MISMATCH",
                    "stable Plan revision returned a different identity");
        }
        List<StepDefinition> definitions = validateDefinitions(plan.steps());
        List<StepEvent> stableEvents = steps.findStepEvents(
                taskId, planRevisionId).stream()
                .sorted(Comparator.comparingLong(StepEvent::authoritySequence))
                .toList();
        validateEvents(plan, definitions, stableEvents);

        long eventCut = foundations.highestAuthorityEventSequence(taskId);
        Map<String, Long> authoritySequence = new HashMap<>();
        for (AuthorityEventRecord event : foundations.findAuthorityEvents(
                taskId, eventCut)) {
            if (authoritySequence.put(event.eventId(),
                    event.eventSequence()) != null) {
                throw failure("CHAIN_STEP_AUTHORITY_DUPLICATE",
                        "duplicate authority event identity");
            }
        }
        List<CandidateStepResultRecord> candidates =
                workflows.findCandidateStepResults(taskId);
        List<ReviewDecisionRecord> reviews =
                workflows.findReviewDecisions(taskId);
        Set<String> gapBlockedSteps = gapBlockedSteps(taskId);
        Set<String> completedStepIds = stableEvents.stream()
                .filter(event -> event.command().eventKind()
                        == StepEventKind.COMPLETED)
                .map(event -> event.command().stepId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<StepState> derived = new ArrayList<>();
        for (StepDefinition definition : definitions) {
            derived.add(deriveStep(plan, definition, stableEvents,
                    candidates, reviews, completedStepIds,
                    authoritySequence, gapBlockedSteps));
        }
        long activeCount = derived.stream().filter(
                state -> state.status() == ChainStepStatus.ACTIVE
                        || state.status() == ChainStepStatus.AWAITING_REVIEW
                        || state.status() == ChainStepStatus.WAITING_GAP)
                .count();
        if (activeCount > 1) {
            throw failure("CHAIN_STEP_MULTIPLE_ACTIVE",
                    "formal facts derive more than one active Step");
        }
        Optional<StepState> active = derived.stream().filter(
                state -> state.status() == ChainStepStatus.ACTIVE
                        || state.status() == ChainStepStatus.AWAITING_REVIEW
                        || state.status() == ChainStepStatus.WAITING_GAP)
                .findFirst();
        boolean unfinished = derived.stream().anyMatch(state ->
                state.status() != ChainStepStatus.COMPLETED
                        && state.status()
                        != ChainStepStatus.SUPERSEDED_BY_REPLAN);
        boolean ready = derived.stream().anyMatch(
                state -> state.status() == ChainStepStatus.READY);
        return new PlanState(plan, List.copyOf(derived), active,
                active.isEmpty() && unfinished && !ready);
    }

    /**
     * Activates the next formally ready Step through the supplied stable
     * authority port. Product adapters use this narrow operation to bridge
     * PLAN_CHANGE into the same state machine; it does not bypass any
     * dependency or replay validation.
     */
    ActivationOutcome activateNext(
            String taskId,
            String planRevisionId,
            String sourceDecisionId,
            String transitionId,
            Instant committedAt) {
        required(sourceDecisionId, "sourceDecisionId");
        required(transitionId, "transitionId");
        Objects.requireNonNull(committedAt, "committedAt");
        PlanState state = derive(taskId, planRevisionId);
        if (state.activeStep().isPresent()) {
            return new ActivationOutcome(
                    ActivationKind.ALREADY_ACTIVE,
                    state.activeStep().get(), null);
        }
        Optional<StepState> next = state.steps().stream()
                .filter(step -> step.status() == ChainStepStatus.READY)
                .min(Comparator.comparingInt(StepState::stableOrder));
        if (next.isEmpty()) {
            return new ActivationOutcome(
                    state.schedulingBlocked()
                            ? ActivationKind.STEP_SCHEDULING_BLOCKED
                            : ActivationKind.NO_STEP,
                    null, null);
        }
        StepState selected = next.get();
        String eventId = "step.activation." + sha256(
                taskId + "\0" + planRevisionId + "\0"
                        + selected.stepId() + "\0" + transitionId);
        StepEventCommand command = new StepEventCommand(
                eventId, taskId, planRevisionId, selected.stepId(),
                eventId, StepEventKind.ACTIVATED, sourceDecisionId,
                transitionId, committedAt);
        AppendResult<StepEvent> appended = steps.appendStepEvent(command);
        if (!appended.value().command().equals(command)) {
            throw failure("CHAIN_STEP_ACTIVATION_REPLAY_MISMATCH",
                    "stable Step authority returned another activation");
        }
        return new ActivationOutcome(
                ActivationKind.ACTIVATED, selected, appended);
    }

    AppendResult<StepEvent> completeAcceptedStep(
            StepTerminalCommand command) {
        Objects.requireNonNull(command, "command");
        StepState current = stateFor(command);
        if (current.status() != ChainStepStatus.AWAITING_REVIEW
                && current.status() != ChainStepStatus.COMPLETED) {
            throw failure("CHAIN_STEP_COMPLETION_STATE_INVALID",
                    "only an awaiting-review Step may complete");
        }
        ReviewDecisionRecord review = workflows.findReviewDecisions(
                        command.taskId()).stream()
                .filter(value -> value.reviewDecisionId().equals(
                        command.sourceDecisionId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_STEP_COMPLETION_REVIEW_MISSING",
                        "formal accepting review does not exist"));
        if (review.decisionKind()
                != ChainProposalKind.REFLECTOR_ACCEPT_STEP
                && review.decisionKind()
                != ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE) {
            throw failure("CHAIN_STEP_COMPLETION_REVIEW_INVALID",
                    "review does not accept the Step result");
        }
        CandidateStepResultRecord candidate = workflows
                .findCandidateStepResults(command.taskId()).stream()
                .filter(value -> value.candidateResultId().equals(
                        review.reviewObjectId())
                        && value.stepId().equals(command.stepId())
                        && value.activationEventId().equals(
                        command.activationEventId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_STEP_COMPLETION_CANDIDATE_MISSING",
                        "accepting review does not bind this Step activation"));
        List<AcceptedResultRecord> accepted = workflows.findAcceptedResults(
                        command.taskId()).stream()
                .filter(value -> value.candidateResultId().equals(
                                candidate.candidateResultId())
                        && value.reviewDecisionId().equals(
                                review.reviewDecisionId())
                        && value.transitionId().equals(
                                command.transitionId())
                        && value.contentId().equals(candidate.contentId()))
                .toList();
        if (accepted.size() != 1) {
            throw failure("CHAIN_STEP_COMPLETION_ACCEPTED_RESULT_MISSING",
                    "Step requires exactly one matching accepted result");
        }
        AcceptedResultRecord acceptedResult = accepted.get(0);
        ChainTransitionType expectedType = review.decisionKind()
                == ChainProposalKind.REFLECTOR_ACCEPT_STEP
                ? ChainTransitionType.ACCEPT_STEP
                : ChainTransitionType.FINAL_STEP_READINESS;
        TransitionRecord transition = requireTransition(
                command, expectedType);
        if (!transition.sourceDecisionId().equals(
                review.reviewDecisionId())
                || !transition.targetIdentityDigest().equals(
                acceptedResult.acceptedIdentitySha256())) {
            throw failure("CHAIN_STEP_COMPLETION_TRANSITION_INVALID",
                    "completion transition does not bind the accepted review");
        }
        ChainTransitionStage acceptedStage = expectedType
                == ChainTransitionType.ACCEPT_STEP
                ? ChainTransitionStage.ACCEPTED_RESULT_COMMITTED
                : ChainTransitionStage
                .ACCEPTED_RESULT_COMMITTED_OR_VERIFIED;
        ChainTransitionStage applicabilityStage = expectedType
                == ChainTransitionType.ACCEPT_STEP
                ? ChainTransitionStage.APPLICABILITY_COMMITTED
                : ChainTransitionStage
                .APPLICABILITY_COMMITTED_OR_EMPTY;
        List<TransitionStageRecord> prefix = requireStagePrefix(
                transition, applicabilityStage);
        requireAcceptedStage(prefix, acceptedStage, acceptedResult);
        requireApplicabilityStage(
                command, transition, prefix, applicabilityStage,
                acceptedResult);
        return appendTerminal(command, StepEventKind.COMPLETED);
    }

    AppendResult<StepEvent> supersedeForReplan(
            StepTerminalCommand command) {
        Objects.requireNonNull(command, "command");
        StepState current = stateFor(command);
        if (current.status() != ChainStepStatus.ACTIVE
                && current.status() != ChainStepStatus.AWAITING_REVIEW
                && current.status() != ChainStepStatus.WAITING_GAP
                && current.status()
                != ChainStepStatus.SUPERSEDED_BY_REPLAN) {
            throw failure("CHAIN_STEP_SUPERSEDE_STATE_INVALID",
                    "only the current active Step may be superseded");
        }
        TransitionRecord transition = requireTransition(
                command, ChainTransitionType.PLAN_CHANGE);
        List<TransitionStageRecord> prefix = requireStagePrefix(
                transition, ChainTransitionStage.APPLICABILITY_COMMITTED);
        requirePlanChangePredecessors(transition, prefix);
        return appendTerminal(
                command, StepEventKind.SUPERSEDED_BY_REPLAN);
    }

    private TransitionRecord requireTransition(
            StepTerminalCommand command,
            ChainTransitionType expectedType) {
        TransitionRecord transition = workflows.findTransition(
                        command.transitionId())
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_TRANSITION_MISSING",
                        "Step terminal transition does not exist"));
        if (transition.transitionType() != expectedType
                || !transition.taskId().equals(command.taskId())
                || !transition.sourceDecisionId().equals(
                command.sourceDecisionId())) {
            throw failure("CHAIN_STEP_TRANSITION_INVALID",
                    "Step terminal transition type, task, or source differs");
        }
        return transition;
    }

    private List<TransitionStageRecord> requireStagePrefix(
            TransitionRecord transition,
            ChainTransitionStage requiredStage) {
        List<TransitionStageRecord> stages = workflows
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        TransitionStageRecord::stageOrdinal))
                .toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        Set<ChainTransitionStage> unique = new HashSet<>();
        for (TransitionStageRecord stage : stages) {
            if (!stage.taskId().equals(transition.taskId())
                    || !stage.transitionId().equals(
                    transition.transitionId())
                    || !unique.add(stage.stageCode())) {
                throw failure("CHAIN_STEP_TRANSITION_PREFIX_INVALID",
                        "transition stage identities are invalid");
            }
            try {
                stage.validateNextFor(
                        transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_STEP_TRANSITION_PREFIX_INVALID",
                        "transition stage prefix is not legal");
            }
            prefix.add(stage.stageCode());
        }
        if (!prefix.contains(requiredStage)) {
            throw failure("CHAIN_STEP_TRANSITION_PREDECESSOR_MISSING",
                    "Step terminal predecessor stage is not committed");
        }
        return stages;
    }

    private static void requireAcceptedStage(
            List<TransitionStageRecord> stages,
            ChainTransitionStage expectedStage,
            AcceptedResultRecord accepted) {
        TransitionStageRecord stage = stage(stages, expectedStage);
        boolean exactPredecessor = "ACCEPTED_RESULT".equals(
                stage.predecessorAuthorityType())
                && accepted.acceptedResultId().equals(
                stage.predecessorAuthorityRef());
        boolean exactSuccessor = "ACCEPTED_RESULT".equals(
                stage.successorAuthorityType())
                && accepted.acceptedResultId().equals(
                stage.successorAuthorityRef());
        if (!exactPredecessor && !exactSuccessor) {
            throw failure("CHAIN_STEP_ACCEPTED_STAGE_INVALID",
                    "accepted-result stage does not bind the formal result");
        }
    }

    private void requireApplicabilityStage(
            StepTerminalCommand command,
            TransitionRecord transition,
            List<TransitionStageRecord> stages,
            ChainTransitionStage expectedStage,
            AcceptedResultRecord accepted) {
        TransitionStageRecord stage = stage(stages, expectedStage);
        PlanSnapshot plan = steps.findPlan(
                        command.taskId(), command.planRevisionId())
                .orElseThrow(() -> failure("CHAIN_STEP_PLAN_NOT_FOUND",
                        "stable Plan revision does not exist"));
        List<ResultApplicabilityRecord> sourceSet = workflows
                .findApplicabilityDecisions(command.taskId()).stream()
                .filter(value -> value.sourceType()
                                == ChainApplicability.SourceType.ACCEPT_STEP
                        && value.sourceDecisionId().equals(
                                transition.transitionId()))
                .toList();
        if (stage.successorAuthorityType() == null) {
            if (expectedStage
                    != ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY) {
                throw failure("CHAIN_STEP_APPLICABILITY_STAGE_INVALID",
                        "mandatory applicability stage has no authority");
            }
            if (!sourceSet.isEmpty()) {
                throw failure("CHAIN_STEP_APPLICABILITY_STAGE_INVALID",
                        "explicit empty stage conflicts with formal source set");
            }
            return;
        }
        List<AcceptedResultRecord> allAccepted = workflows
                .findAcceptedResults(command.taskId());
        Map<String, AcceptedResultRecord> acceptedById = new HashMap<>();
        for (AcceptedResultRecord result : allAccepted) {
            if (acceptedById.put(result.acceptedResultId(), result) != null) {
                throw failure("CHAIN_STEP_ACCEPTED_SOURCE_SET_INVALID",
                        "accepted result authority contains duplicate IDs");
            }
        }
        Set<String> sourceAcceptedIds = sourceSet.stream()
                .map(ResultApplicabilityRecord::acceptedResultId)
                .collect(java.util.stream.Collectors.toSet());
        boolean currentAcceptedRemainsApplicable = sourceSet.stream()
                .anyMatch(value -> value.acceptedResultId().equals(
                                accepted.acceptedResultId())
                        && value.conclusion()
                                == ChainApplicability.Outcome.APPLICABLE);
        boolean completeSourceSet = !sourceSet.isEmpty()
                && sourceAcceptedIds.size() == sourceSet.size()
                && currentAcceptedRemainsApplicable
                && sourceSet.stream().allMatch(value ->
                        acceptedById.containsKey(value.acceptedResultId())
                                && value.targetTaskFrameId().equals(
                                plan.taskFrameId())
                                && value.targetPlanId().equals(plan.planId())
                                && value.targetPlanRevisionId().equals(
                                command.planRevisionId())
                                && value.targetCandidateKey().equals(
                                plan.targetCandidateKey())
                                && value.targetInstructionVersionId().equals(
                                plan.targetInstructionVersionId()));
        if (!completeSourceSet) {
            throw failure("CHAIN_STEP_APPLICABILITY_SOURCE_SET_INVALID",
                    "applicability barrier does not cover its formal source set");
        }
        boolean stageRefInSourceSet = sourceSet.stream().anyMatch(value ->
                value.applicabilityId().equals(
                        stage.successorAuthorityRef()));
        if (!"RESULT_APPLICABILITY".equals(
                stage.successorAuthorityType())
                || !stageRefInSourceSet) {
            throw failure("CHAIN_STEP_APPLICABILITY_STAGE_INVALID",
                    "applicability stage references another formal fact");
        }
    }

    private void requirePlanChangePredecessors(
            TransitionRecord transition,
            List<TransitionStageRecord> stages) {
        TransitionStageRecord planStage = stage(
                stages, ChainTransitionStage.TASKFRAME_PLAN_COMMITTED);
        if (!"PLAN_BINDING".equals(planStage.successorAuthorityType())) {
            throw failure("CHAIN_STEP_PLAN_STAGE_INVALID",
                    "PLAN_CHANGE lacks a formal Plan binding");
        }
        PlanBindingRecord binding = workflows.findPlanBindings(
                        transition.taskId()).stream()
                .filter(value -> value.planBindingId().equals(
                        planStage.successorAuthorityRef()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_STEP_PLAN_STAGE_INVALID",
                        "PLAN_CHANGE Plan binding reference is missing"));
        if (!Objects.equals(binding.transitionId(),
                transition.transitionId())) {
            throw failure("CHAIN_STEP_PLAN_STAGE_INVALID",
                    "Plan binding belongs to another transition");
        }
        TransitionStageRecord applicabilityStage = stage(
                stages, ChainTransitionStage.APPLICABILITY_COMMITTED);
        List<ResultApplicabilityRecord> sourceSet = workflows
                .findApplicabilityDecisions(transition.taskId()).stream()
                .filter(value -> value.sourceDecisionId().equals(
                                transition.transitionId())
                        && (value.sourceType()
                                == ChainApplicability.SourceType.PLAN_REVISION
                        || value.sourceType()
                                == ChainApplicability.SourceType
                                .PERSISTENT_PLAN))
                .toList();
        if (applicabilityStage.successorAuthorityType() == null) {
            if (!sourceSet.isEmpty()) {
                throw failure("CHAIN_STEP_PLAN_APPLICABILITY_INVALID",
                        "empty PLAN_CHANGE barrier conflicts with source set");
            }
            return;
        }
        Set<ChainApplicability.Identity> identities = sourceSet.stream()
                .map(value -> new ChainApplicability.Identity(
                        value.acceptedResultId(), value.sourceType(),
                        value.sourceDecisionId(), value.targetTaskFrameId(),
                        value.targetPlanId(), value.targetPlanRevisionId(),
                        value.targetCandidateKey(),
                        value.targetInstructionVersionId()))
                .collect(java.util.stream.Collectors.toSet());
        boolean exactSourceSet = !sourceSet.isEmpty()
                && identities.size() == sourceSet.size()
                && sourceSet.stream().allMatch(value ->
                        value.targetTaskFrameId().equals(binding.taskFrameId())
                                && value.targetPlanId().equals(
                                binding.planId())
                                && value.targetPlanRevisionId().equals(
                                binding.planRevisionId())
                                && value.targetInstructionVersionId().equals(
                                binding.instructionId()));
        boolean stageRefInSourceSet = sourceSet.stream().anyMatch(value ->
                value.applicabilityId().equals(
                        applicabilityStage.successorAuthorityRef()));
        if (!"RESULT_APPLICABILITY".equals(
                applicabilityStage.successorAuthorityType())
                || !exactSourceSet || !stageRefInSourceSet) {
            throw failure("CHAIN_STEP_PLAN_APPLICABILITY_INVALID",
                    "PLAN_CHANGE applicability barrier is not exact");
        }
    }

    private static TransitionStageRecord stage(
            List<TransitionStageRecord> stages,
            ChainTransitionStage code) {
        return stages.stream().filter(value -> value.stageCode() == code)
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_STEP_TRANSITION_PREDECESSOR_MISSING",
                        "required transition stage is not committed"));
    }

    private StepState stateFor(StepTerminalCommand command) {
        StepState current = derive(
                command.taskId(), command.planRevisionId()).steps().stream()
                .filter(value -> value.stepId().equals(command.stepId()))
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_STEP_NOT_FOUND", "Step does not exist"));
        if (!Objects.equals(current.activationEventId(),
                command.activationEventId())) {
            throw failure("CHAIN_STEP_ACTIVATION_MISMATCH",
                    "Step terminal command targets another activation");
        }
        return current;
    }

    private AppendResult<StepEvent> appendTerminal(
            StepTerminalCommand command,
            StepEventKind eventKind) {
        String eventId = "step." + eventKind.name().toLowerCase(
                java.util.Locale.ROOT) + "." + sha256(
                command.taskId() + "\0" + command.planRevisionId()
                        + "\0" + command.stepId() + "\0"
                        + command.activationEventId() + "\0"
                        + command.transitionId());
        StepEventCommand event = new StepEventCommand(
                eventId, command.taskId(), command.planRevisionId(),
                command.stepId(), command.activationEventId(), eventKind,
                command.sourceDecisionId(), command.transitionId(),
                command.committedAt());
        AppendResult<StepEvent> appended = steps.appendStepEvent(event);
        if (!appended.value().command().equals(event)) {
            throw failure("CHAIN_STEP_TERMINAL_REPLAY_MISMATCH",
                    "stable Step authority returned another terminal event");
        }
        return appended;
    }

    private StepState deriveStep(
            PlanSnapshot plan,
            StepDefinition definition,
            List<StepEvent> events,
            List<CandidateStepResultRecord> candidates,
            List<ReviewDecisionRecord> reviews,
            Set<String> completedStepIds,
            Map<String, Long> authoritySequence,
            Set<String> gapBlockedSteps) {
        List<StepEvent> ownEvents = events.stream().filter(event ->
                event.command().stepId().equals(definition.stepId())).toList();
        StepEvent latest = ownEvents.isEmpty()
                ? null : ownEvents.get(ownEvents.size() - 1);
        if (latest != null && latest.command().eventKind()
                == StepEventKind.SUPERSEDED_BY_REPLAN) {
            return state(definition, ChainStepStatus.SUPERSEDED_BY_REPLAN,
                    latest.command().activationEventId());
        }
        if (latest != null && latest.command().eventKind()
                == StepEventKind.COMPLETED) {
            return state(definition, ChainStepStatus.COMPLETED,
                    latest.command().activationEventId());
        }
        if (latest != null && latest.command().eventKind()
                == StepEventKind.ACTIVATED) {
            String activationId = latest.command().activationEventId();
            if (gapBlockedSteps.contains(definition.stepId())) {
                return state(definition, ChainStepStatus.WAITING_GAP,
                        activationId);
            }
            CandidateStepResultRecord candidate = latestCandidate(
                    plan, definition.stepId(), activationId,
                    candidates, authoritySequence);
            if (candidate == null) {
                return state(definition, ChainStepStatus.ACTIVE,
                        activationId);
            }
            ReviewDecisionRecord review = latestReview(
                    candidate, reviews, authoritySequence);
            if (review != null && review.decisionKind()
                    == ChainProposalKind.REFLECTOR_CONTINUE_STEP) {
                return state(definition, ChainStepStatus.ACTIVE,
                        activationId);
            }
            return state(definition, ChainStepStatus.AWAITING_REVIEW,
                    activationId);
        }
        boolean dependenciesSatisfied = completedStepIds.containsAll(
                definition.prerequisiteStepIds());
        return state(definition,
                dependenciesSatisfied
                        ? ChainStepStatus.READY
                        : ChainStepStatus.NOT_STARTED,
                null);
    }

    private CandidateStepResultRecord latestCandidate(
            PlanSnapshot plan,
            String stepId,
            String activationId,
            List<CandidateStepResultRecord> candidates,
            Map<String, Long> authoritySequence) {
        return candidates.stream().filter(candidate ->
                        candidate.taskId().equals(plan.taskId())
                                && candidate.planRevisionId().equals(
                                plan.planRevisionId())
                                && candidate.stepId().equals(stepId)
                                && candidate.activationEventId().equals(
                                activationId))
                .max(Comparator.comparingLong(candidate -> sequence(
                        authoritySequence, candidate.eventId())))
                .orElse(null);
    }

    private ReviewDecisionRecord latestReview(
            CandidateStepResultRecord candidate,
            List<ReviewDecisionRecord> reviews,
            Map<String, Long> authoritySequence) {
        return reviews.stream().filter(review ->
                        review.taskId().equals(candidate.taskId())
                                && review.reviewObjectType().equals(
                                "CANDIDATE_STEP_RESULT")
                                && review.reviewObjectId().equals(
                                candidate.candidateResultId()))
                .max(Comparator.comparingLong(review -> sequence(
                        authoritySequence, review.eventId())))
                .orElse(null);
    }

    private Set<String> gapBlockedSteps(String taskId) {
        Set<String> blocked = new HashSet<>();
        for (PendingItemRecord pending : workflows.findOpenPendingItems(taskId)) {
            var proposal = models.findProposal(pending.sourceProposalId())
                    .orElseThrow(() -> failure(
                            "CHAIN_STEP_GAP_SOURCE_MISSING",
                            "open gap source proposal is missing"));
            var invocation = models.findInvocation(proposal.invocationId())
                    .orElseThrow(() -> failure(
                            "CHAIN_STEP_GAP_INVOCATION_MISSING",
                            "open gap source invocation is missing"));
            var context = contexts.findContextRevision(
                            invocation.contextRevisionId())
                    .orElseThrow(() -> failure(
                            "CHAIN_STEP_GAP_CONTEXT_MISSING",
                            "open gap source context is missing"));
            if (!pending.taskId().equals(taskId)
                    || !proposal.taskId().equals(taskId)
                    || !invocation.taskId().equals(taskId)
                    || !context.taskId().equals(taskId)) {
                throw failure("CHAIN_STEP_GAP_TASK_MISMATCH",
                        "open gap authority crosses task identity");
            }
            if (context.stepId() != null) {
                blocked.add(context.stepId());
            }
        }
        return Set.copyOf(blocked);
    }

    private static List<StepDefinition> validateDefinitions(
            List<StepDefinition> definitions) {
        List<StepDefinition> ordered = definitions.stream()
                .sorted(Comparator.comparingInt(StepDefinition::stableOrder))
                .toList();
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (StepDefinition definition : ordered) {
            if (!ids.add(definition.stepId())
                    || !orders.add(definition.stableOrder())) {
                throw failure("CHAIN_STEP_PLAN_AMBIGUOUS",
                        "Step identities and stable order must be unique");
            }
        }
        for (StepDefinition definition : ordered) {
            if (definition.prerequisiteStepIds().contains(
                    definition.stepId())) {
                throw failure("CHAIN_STEP_PLAN_SELF_DEPENDENCY",
                        "a Step cannot depend on itself");
            }
            if (!ids.containsAll(definition.prerequisiteStepIds())) {
                throw failure("CHAIN_STEP_PLAN_DEPENDENCY_UNKNOWN",
                        "a Step dependency does not exist in the Plan");
            }
        }
        Map<String, StepDefinition> byId = ordered.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        StepDefinition::stepId, value -> value));
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (StepDefinition definition : ordered) {
            validateAcyclic(definition.stepId(), byId, visited, visiting);
        }
        for (StepDefinition definition : ordered) {
            boolean forwardDependency = definition.prerequisiteStepIds()
                    .stream().map(byId::get).anyMatch(dependency ->
                            dependency.stableOrder()
                                    >= definition.stableOrder());
            if (forwardDependency) {
                throw failure("CHAIN_STEP_PLAN_DEPENDENCY_ORDER_INVALID",
                        "a Step may depend only on an earlier stable Step");
            }
        }
        return ordered;
    }

    private static void validateAcyclic(
            String stepId,
            Map<String, StepDefinition> definitions,
            Set<String> visited,
            Set<String> visiting) {
        if (visited.contains(stepId)) {
            return;
        }
        if (!visiting.add(stepId)) {
            throw failure("CHAIN_STEP_PLAN_DEPENDENCY_CYCLE",
                    "Step dependencies must be acyclic");
        }
        for (String dependency : definitions.get(stepId)
                .prerequisiteStepIds()) {
            validateAcyclic(dependency, definitions, visited, visiting);
        }
        visiting.remove(stepId);
        visited.add(stepId);
    }

    private static void validateEvents(
            PlanSnapshot plan,
            List<StepDefinition> definitions,
            List<StepEvent> events) {
        Set<String> stepIds = definitions.stream()
                .map(StepDefinition::stepId).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
        long previous = 0;
        Set<String> eventIds = new HashSet<>();
        Map<String, StepEventCommand> latestByStep = new HashMap<>();
        for (StepEvent event : events) {
            StepEventCommand command = event.command();
            if (!command.taskId().equals(plan.taskId())
                    || !command.planRevisionId().equals(
                    plan.planRevisionId())
                    || !stepIds.contains(command.stepId())
                    || event.authoritySequence() <= previous
                    || !eventIds.add(command.eventId())) {
                throw failure("CHAIN_STEP_EVENT_PREFIX_INVALID",
                        "stable Step event prefix is invalid");
            }
            StepEventCommand prior = latestByStep.get(command.stepId());
            if (command.eventKind() == StepEventKind.ACTIVATED) {
                if (prior != null) {
                    throw failure("CHAIN_STEP_EVENT_PREFIX_INVALID",
                            "a stable Step cannot be activated twice");
                }
            } else if (prior == null
                    || prior.eventKind() != StepEventKind.ACTIVATED
                    || !prior.activationEventId().equals(
                    command.activationEventId())) {
                throw failure("CHAIN_STEP_EVENT_PREFIX_INVALID",
                        "Step completion/supersede requires its activation");
            }
            latestByStep.put(command.stepId(), command);
            previous = event.authoritySequence();
        }
    }

    private static long sequence(
            Map<String, Long> events, String eventId) {
        Long value = events.get(eventId);
        if (value == null) {
            throw failure("CHAIN_STEP_AUTHORITY_EVENT_MISSING",
                    "formal Step dependency fact lacks an authority event");
        }
        return value;
    }

    private static StepState state(
            StepDefinition definition,
            ChainStepStatus status,
            String activationEventId) {
        return new StepState(definition.stepId(), definition.stableOrder(),
                status, activationEventId);
    }

    private static ChainStepException failure(
            String code, String message) {
        return new ChainStepException(code, message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record StepState(
            String stepId,
            int stableOrder,
            ChainStepStatus status,
            String activationEventId) {
        public StepState {
            required(stepId, "stepId");
            if (stableOrder < 1) {
                throw new IllegalArgumentException(
                        "stableOrder must be positive");
            }
            Objects.requireNonNull(status, "status");
            boolean needsActivation = status == ChainStepStatus.ACTIVE
                    || status == ChainStepStatus.AWAITING_REVIEW
                    || status == ChainStepStatus.WAITING_GAP
                    || status == ChainStepStatus.COMPLETED
                    || status == ChainStepStatus.SUPERSEDED_BY_REPLAN;
            if (needsActivation != (activationEventId != null)) {
                throw new IllegalArgumentException(
                        "effective status and activation identity mismatch");
            }
        }
    }

    public record PlanState(
            PlanSnapshot plan,
            List<StepState> steps,
            Optional<StepState> activeStep,
            boolean schedulingBlocked) {
        public PlanState {
            Objects.requireNonNull(plan, "plan");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            activeStep = Objects.requireNonNull(activeStep, "activeStep");
            if (schedulingBlocked && activeStep.isPresent()) {
                throw new IllegalArgumentException(
                        "active Plan cannot be scheduling blocked");
            }
        }
    }

    public enum ActivationKind {
        ACTIVATED,
        ALREADY_ACTIVE,
        STEP_SCHEDULING_BLOCKED,
        NO_STEP
    }

    public record ActivationOutcome(
            ActivationKind kind,
            StepState step,
            AppendResult<StepEvent> append) {
        public ActivationOutcome {
            Objects.requireNonNull(kind, "kind");
            if ((kind == ActivationKind.ACTIVATED) != (append != null)) {
                throw new IllegalArgumentException(
                        "only ACTIVATED carries an append result");
            }
        }
    }

    public record StepTerminalCommand(
            String taskId,
            String planRevisionId,
            String stepId,
            String activationEventId,
            String sourceDecisionId,
            String transitionId,
            Instant committedAt) {
        public StepTerminalCommand {
            required(taskId, "taskId");
            required(planRevisionId, "planRevisionId");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            required(sourceDecisionId, "sourceDecisionId");
            required(transitionId, "transitionId");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }
}
