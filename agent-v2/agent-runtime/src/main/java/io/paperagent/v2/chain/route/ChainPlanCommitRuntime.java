package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPlanBindingWriter;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.instruction.ChainInstructionState;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;

import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Commits stable Plan-core success into the sole append-only chain PlanBinding. */
public final class ChainPlanCommitRuntime {
    private final ChainFoundationRepository foundations;
    private final ChainModelRepository models;
    private final ChainWorkflowRepository workflow;
    private final ChainPlanBindingWriter bindings;
    private final ChainPlanCommitPort plans;
    private final ChainInstructionStateReader instructions;
    private final ChainRouteRuntime.ProposalOfficialBinder proposalBinder;

    public ChainPlanCommitRuntime(
            ChainFoundationRepository foundations,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainPlanBindingWriter bindings,
            ChainPlanCommitPort plans,
            ChainInstructionStateReader instructions,
            ChainRouteRuntime.ProposalOfficialBinder proposalBinder) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.proposalBinder = Objects.requireNonNull(
                proposalBinder, "proposalBinder");
    }

    public ChainPersistenceRecords.PlanBindingRecord commitPersistent(
            CommitRequest request,
            PlannerPayload.PersistentPlan payload) {
        Objects.requireNonNull(payload, "payload");
        if (request.transitionId() == null) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "initial persistent Plan requires PLAN_CHANGE transition");
        }
        requireCurrentInstruction(request, false);
        AuthorityOrder authority = AuthorityOrder.load(
                foundations, request.taskId());
        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                request, ChainProposalKind.PLANNER_PERSISTENT_PLAN, payload);
        ChainPersistenceRecords.RouteDecisionRecord route = workflow
                .findRouteDecisions(request.taskId()).stream()
                .filter(value -> request.proposalId().equals(value.proposalId())
                        && request.instructionId().equals(value.instructionId()))
                .findFirst()
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.PLAN_SOURCE_INVALID,
                        "PERSISTENT_PLAN lacks its exact formal RouteDecision"));
        authority.sequence(route, "ROUTE_DECISION");
        if (route.route() != ChainExecutionMode.PERSISTENT_PLAN_EXECUTE
                || route.decisionKind()
                != ChainPersistenceRecords.RouteDecisionType.INITIAL) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "PERSISTENT_PLAN source is not the initial persistent route");
        }
        ChainPersistenceRecords.TransitionRecord transition = workflow
                .findTransition(request.transitionId())
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.PLAN_SOURCE_INVALID,
                        "persistent Plan lacks its formal PLAN_CHANGE transition"));
        authority.sequence(transition, "TRANSITION");
        if (!request.taskId().equals(transition.taskId())
                || transition.transitionType() != ChainTransitionType.PLAN_CHANGE
                || !route.routeDecisionId().equals(
                transition.sourceDecisionId())) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "persistent PLAN_CHANGE transition source identity is invalid");
        }
        validateProposalPrefix(
                proposal, "ROUTE_DECISION", route.routeDecisionId(), true,
                authority);

        ChainPlanCommitPort.CommittedPlan committed = Objects.requireNonNull(
                plans.commitPersistent(
                        new ChainPlanCommitPort.PersistentPlanCommand(
                                request.taskId(), request.instructionId(),
                                request.proposalId(), route.routeDecisionId(),
                                request.transitionId(), request.createdAt(),
                                payload)),
                "committed Plan");
        validateInitialCommit(committed, request.taskId());
        ChainPersistenceRecords.PlanBindingRecord binding = binding(
                request, route.routeDecisionId(), committed);
        ensureOnlyReplay(binding, true);
        ChainPersistenceRecords.PlanBindingRecord stored = append(binding);
        validateProposalPrefix(
                proposal, "ROUTE_DECISION", route.routeDecisionId(), true,
                AuthorityOrder.load(foundations, request.taskId()));
        return stored;
    }

    public ChainPersistenceRecords.PlanBindingRecord commitRevision(
            CommitRequest request,
            PlannerPayload.PlanRevision payload) {
        Objects.requireNonNull(payload, "payload");
        if (request.transitionId() == null) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "PLAN_REVISION requires PLAN_CHANGE transition");
        }
        requireCurrentInstruction(request, true);
        AuthorityOrder authority = AuthorityOrder.load(
                foundations, request.taskId());
        ChainPersistenceRecords.ModelProposalRecord proposal = proposal(
                request, ChainProposalKind.PLANNER_PLAN_REVISION, payload);
        validateProposalPrefix(
                proposal, "PLAN_BINDING", null, false, authority);

        List<ChainPersistenceRecords.PlanBindingRecord> current = workflow
                .findPlanBindings(request.taskId()).stream()
                .filter(value -> request.instructionId().equals(
                        value.instructionId()))
                .toList();
        current.forEach(value -> authority.sequence(value, "PLAN_BINDING"));
        ChainPersistenceRecords.PlanBindingRecord previous = current.stream()
                .filter(value -> payload.oldRevisionRef().equals(
                        value.planRevisionId()))
                .findFirst()
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.PLAN_SOURCE_INVALID,
                        "PLAN_REVISION old revision is not formally bound"));
        if (!payload.taskFrameRef().equals(previous.taskFrameId())
                || !payload.oldRevisionRef().equals(
                previous.planRevisionId())) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "PLAN_REVISION changed TaskFrame or old revision source");
        }
        long previousSequence = authority.sequence(previous, "PLAN_BINDING");
        List<ChainPersistenceRecords.PlanBindingRecord> successors = current.stream()
                .filter(value -> authority.sequence(value, "PLAN_BINDING")
                        > previousSequence)
                .toList();
        if (successors.size() > 1
                || successors.stream().anyMatch(value ->
                !request.transitionId().equals(value.transitionId()))) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "PLAN_REVISION old revision is stale for this transition");
        }
        SourceAuthority source = revisionSource(
                request.taskId(), payload.triggerDecisionOrGapRef(), authority);
        ChainPersistenceRecords.TransitionRecord transition = workflow
                .findTransition(request.transitionId())
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.PLAN_SOURCE_INVALID,
                        "formal PLAN_CHANGE transition does not exist"));
        authority.sequence(transition, "TRANSITION");
        if (transition.transitionType() != ChainTransitionType.PLAN_CHANGE
                || !request.taskId().equals(transition.taskId())
                || !source.ref().equals(transition.sourceDecisionId())) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "PLAN_CHANGE transition source identity is invalid");
        }

        ChainPlanCommitPort.CommittedPlan committed = Objects.requireNonNull(
                plans.commitRevision(new ChainPlanCommitPort.PlanRevisionCommand(
                        request.taskId(), request.instructionId(), request.proposalId(),
                        source.type(), source.ref(), request.transitionId(),
                        previous.taskFrameId(), previous.planId(),
                        previous.planRevisionId(), previous.planRevisionNumber(),
                        request.createdAt(), payload)),
                "committed Plan revision");
        validateRevisionCommit(committed, previous);
        ChainPersistenceRecords.PlanBindingRecord binding = binding(
                request, previous.routeDecisionId(), committed);
        ensureOnlyReplay(binding, false);
        ChainPersistenceRecords.PlanBindingRecord stored = append(binding);
        proposalBinder.bindOfficialResult(
                request.taskId(), request.proposalId(),
                "PLAN_BINDING", binding.planBindingId());
        validateProposalPrefix(
                proposal, "PLAN_BINDING", binding.planBindingId(), true,
                AuthorityOrder.load(foundations, request.taskId()));
        return stored;
    }

    private ChainInstructionState requireCurrentInstruction(
            CommitRequest request,
            boolean revision) {
        ChainInstructionState state = instructions.read(request.taskId());
        if (!request.instructionId().equals(
                state.currentInstruction().instructionId())
                || state.gate() == ChainInstructionState.Gate.DIRECT_ANSWER
                || state.gate()
                == ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION
                || state.gate() == ChainInstructionState.Gate.CANCELLED
                || state.gate() == ChainInstructionState.Gate.SUPERSEDED
                || state.gate() == ChainInstructionState.Gate.TERMINAL
                || (!revision
                && state.gate()
                != ChainInstructionState.Gate.PLANNING
                && state.gate()
                != ChainInstructionState.Gate.PAUSED_FOR_DISPOSITION
                && state.gate()
                != ChainInstructionState.Gate.SIDE_EFFECTS_ALLOWED)) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "Plan commit targets a stale or prohibited instruction state");
        }
        return state;
    }

    private ChainPersistenceRecords.ModelProposalRecord proposal(
            CommitRequest request,
            ChainProposalKind kind,
            Object payload) {
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.proposalId())
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "Plan proposal does not exist"));
        if (!request.taskId().equals(proposal.taskId())
                || proposal.proposalKind() != kind
                || !ChainRouteCanonical.payload(payload).equals(
                proposal.payload().json())
                || !ChainRouteCanonical.sha256(
                proposal.payload().json()).equals(proposal.payload().sha256())) {
            throw failure(ChainRouteException.Code.PROPOSAL_PAYLOAD_MISMATCH,
                    "typed Plan payload does not match formal proposal");
        }
        return proposal;
    }

    private void validateProposalPrefix(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            String expectedAuthorityType,
            String expectedAuthorityRef,
            boolean boundRequired,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord::stateSequence))
                .toList();
        if (states.isEmpty() || states.size() > 2) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "Plan proposal state prefix is absent or invalid");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ChainPersistenceRecords.ProposalStateEventRecord state = states.get(index);
            authority.sequence(state, "PROPOSAL_" + state.stateKind().name());
            if (!proposal.proposalId().equals(state.proposalId())
                    || !proposal.taskId().equals(state.taskId())
                    || state.stateSequence() != index + 1L) {
                throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "Plan proposal state crossed an identity boundary");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "Plan proposal state prefix is illegal");
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "Plan proposal was not accepted");
        }
        if (states.size() == 1) {
            if (boundRequired) {
                throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "Plan proposal lacks its official result binding");
            }
            return;
        }
        ChainPersistenceRecords.ProposalStateEventRecord bound = states.get(1);
        if (bound.stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !expectedAuthorityType.equals(bound.officialAuthorityType())
                || (expectedAuthorityRef != null
                && !expectedAuthorityRef.equals(bound.officialAuthorityRef()))) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "Plan proposal is bound to another official result");
        }
    }

    private SourceAuthority revisionSource(
            String taskId,
            String sourceRef,
            AuthorityOrder authority) {
        ChainPersistenceRecords.ReviewDecisionRecord review = workflow
                .findReviewDecisions(taskId).stream()
                .filter(value -> sourceRef.equals(value.reviewDecisionId()))
                .findFirst().orElse(null);
        if (review != null) {
            authority.sequence(review, "REVIEW_DECISION");
            if (review.decisionKind()
                    != ChainProposalKind.REFLECTOR_REPLAN_REQUIRED) {
                throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                        "ReviewDecision does not authorize replan");
            }
            return new SourceAuthority("REVIEW_DECISION", sourceRef);
        }
        ChainPersistenceRecords.PendingItemRecord gap = workflow
                .findPendingItems(taskId).stream()
                .filter(value -> sourceRef.equals(value.gapId()))
                .findFirst().orElseThrow(() -> failure(
                        ChainRouteException.Code.PLAN_SOURCE_INVALID,
                        "PLAN_REVISION formal source does not exist"));
        authority.sequence(gap, "PENDING_ITEM");
        List<ChainPersistenceRecords.PendingItemEventRecord> events = workflow
                .findPendingItemEvents(gap.gapId());
        events.forEach(value -> authority.sequence(
                value, "PENDING_ITEM_" + value.eventKind().name()));
        ChainPersistenceRecords.PendingItemEventRecord latest = events.stream()
                .max(Comparator.comparingLong(value -> authority.sequence(
                        value, "PENDING_ITEM_" + value.eventKind().name())))
                .orElse(null);
        if (latest == null
                || latest.eventKind()
                != io.paperagent.v2.chain.ChainPendingItemStatus.RESOLVED) {
            throw failure(ChainRouteException.Code.PLAN_SOURCE_INVALID,
                    "gap cannot authorize revision before formal resolution");
        }
        return new SourceAuthority("PENDING_ITEM", sourceRef);
    }

    private ChainPersistenceRecords.PlanBindingRecord binding(
            CommitRequest request,
            String routeDecisionId,
            ChainPlanCommitPort.CommittedPlan committed) {
        String id = "plan-binding." + ChainRouteCanonical.sha256(
                request.taskId() + "\0" + request.instructionId() + "\0"
                        + request.proposalId() + "\0" + committed.taskFrameId()
                        + "\0" + committed.planId() + "\0"
                        + committed.planRevisionId() + "\0"
                        + Objects.toString(request.transitionId(), "NONE"));
        return new ChainPersistenceRecords.PlanBindingRecord(
                id, request.taskId(), request.eventId(), request.instructionId(),
                routeDecisionId, committed.taskFrameId(), committed.planId(),
                committed.planRevisionId(), committed.planRevisionNumber(),
                committed.authorityType(), committed.authorityId(),
                committed.authoritySha256(), request.transitionId(),
                request.createdAt());
    }

    private ChainPersistenceRecords.PlanBindingRecord append(
            ChainPersistenceRecords.PlanBindingRecord binding) {
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        binding.eventId(), binding.taskId(), "PLAN_BINDING",
                        binding.transitionId(), binding.authoritySha256(),
                        binding.createdAt());
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.PlanBindingRecord> appended = bindings
                .appendPlanBinding(new ChainPersistenceRecords.AuthoritativeFact<>(
                        event, binding));
        ChainPersistenceRecords.PlanBindingRecord stored = appended.fact();
        if (!sameImmutableContents(stored, binding)) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "PlanBinding replay changed immutable identity");
        }
        ChainPersistenceRecords.AuthorityEventRecord storedEvent =
                appended.event();
        if (!event.eventId().equals(storedEvent.eventId())
                || !event.taskId().equals(storedEvent.taskId())
                || !event.eventType().equals(storedEvent.eventType())
                || !Objects.equals(
                event.transitionId(), storedEvent.transitionId())
                || !event.sourceIdentitySha256().equals(
                storedEvent.sourceIdentitySha256())
                || !sameDatabaseInstant(stored.createdAt(),
                storedEvent.committedAt())) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "PlanBinding authority event changed immutable identity");
        }
        return stored;
    }

    private static boolean sameImmutableContents(
            ChainPersistenceRecords.PlanBindingRecord left,
            ChainPersistenceRecords.PlanBindingRecord right) {
        return left.planBindingId().equals(right.planBindingId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.instructionId().equals(right.instructionId())
                && left.routeDecisionId().equals(right.routeDecisionId())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.planRevisionNumber() == right.planRevisionNumber()
                && left.authorityType().equals(right.authorityType())
                && left.authorityId().equals(right.authorityId())
                && left.authoritySha256().equals(right.authoritySha256())
                && Objects.equals(left.transitionId(), right.transitionId());
    }

    private static boolean sameDatabaseInstant(Instant left, Instant right) {
        return left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private void ensureOnlyReplay(
            ChainPersistenceRecords.PlanBindingRecord requested,
            boolean initial) {
        List<ChainPersistenceRecords.PlanBindingRecord> committed = workflow
                .findPlanBindings(requested.taskId());
        ChainPersistenceRecords.PlanBindingRecord same = committed.stream()
                .filter(value -> requested.planBindingId().equals(
                        value.planBindingId()))
                .findFirst().orElse(null);
        if (same != null && !same.equals(requested)) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "PlanBinding replay changed immutable fields");
        }
        if (same == null && initial && committed.stream().anyMatch(value ->
                requested.instructionId().equals(value.instructionId()))) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "current instruction already has an initial PlanBinding");
        }
        if (same == null && !initial && committed.stream().anyMatch(value ->
                requested.instructionId().equals(value.instructionId())
                        && requested.planRevisionId().equals(
                        value.planRevisionId()))) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "Plan revision already has another chain binding");
        }
    }

    private static void validateInitialCommit(
            ChainPlanCommitPort.CommittedPlan committed,
            String taskId) {
        if (!taskId.equals(committed.taskId())
                || committed.planRevisionNumber() != 1) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "stable core returned an invalid initial Plan identity");
        }
    }

    private static void validateRevisionCommit(
            ChainPlanCommitPort.CommittedPlan committed,
            ChainPersistenceRecords.PlanBindingRecord previous) {
        if (!previous.taskId().equals(committed.taskId())
                || !previous.taskFrameId().equals(committed.taskFrameId())
                || !previous.planId().equals(committed.planId())
                || previous.planRevisionId().equals(committed.planRevisionId())
                || committed.planRevisionNumber()
                <= previous.planRevisionNumber()) {
            throw failure(ChainRouteException.Code.PLAN_COMMIT_MISMATCH,
                    "PLAN_REVISION changed frozen TaskFrame/Plan version identity");
        }
    }

    private static ChainRouteException failure(
            ChainRouteException.Code code,
            String message) {
        return new ChainRouteException(code, message);
    }

    public record CommitRequest(
            String taskId,
            String instructionId,
            String proposalId,
            String eventId,
            String transitionId,
            Instant createdAt) {
        public CommitRequest {
            taskId = required(taskId, "taskId");
            instructionId = required(instructionId, "instructionId");
            proposalId = required(proposalId, "proposalId");
            eventId = required(eventId, "eventId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    private record SourceAuthority(String type, String ref) {
    }

    private record AuthorityOrder(
            String taskId,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId) {
        static AuthorityOrder load(
                ChainFoundationRepository foundations,
                String taskId) {
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events =
                    new HashMap<>();
            Set<Long> sequences = new HashSet<>();
            long highest = foundations.highestAuthorityEventSequence(taskId);
            List<ChainPersistenceRecords.AuthorityEventRecord> prefix =
                    foundations.findAuthorityEvents(taskId, highest).stream()
                            .sorted(Comparator.comparingLong(
                                    ChainPersistenceRecords
                                            .AuthorityEventRecord
                                            ::eventSequence))
                            .toList();
            if (highest != prefix.size()) {
                throw failure(
                        ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                        "Plan authority event prefix is not contiguous");
            }
            for (int index = 0; index < prefix.size(); index++) {
                ChainPersistenceRecords.AuthorityEventRecord event =
                        prefix.get(index);
                if (!taskId.equals(event.taskId())
                        || event.eventSequence() != index + 1L
                        || events.put(event.eventId(), event) != null
                        || !sequences.add(event.eventSequence())) {
                    throw failure(
                            ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                            "Plan authority event prefix is inconsistent");
                }
            }
            return new AuthorityOrder(taskId, Map.copyOf(events));
        }

        long sequence(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String expectedType) {
            ChainPersistenceRecords.AuthorityEventRecord event = byId.get(
                    fact.eventId());
            if (!taskId.equals(fact.taskId())
                    || event == null
                    || !expectedType.equals(event.eventType())) {
                throw failure(
                        ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                        "Plan source lacks exact formal authority event");
            }
            return event.eventSequence();
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
