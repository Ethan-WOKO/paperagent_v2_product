package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRouteDecisionWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.instruction.ChainInstructionState;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** The sole runtime that turns an accepted route proposal into a formal route fact. */
public final class ChainRouteRuntime {
    private final ChainModelRepository models;
    private final ChainWorkflowRepository workflow;
    private final ChainRouteDecisionWriter writer;
    private final ChainInstructionStateReader instructions;
    private final ProposalOfficialBinder proposalBinder;

    public ChainRouteRuntime(
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainRouteDecisionWriter writer,
            ChainInstructionStateReader instructions,
            ProposalOfficialBinder proposalBinder) {
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.proposalBinder = Objects.requireNonNull(
                proposalBinder, "proposalBinder");
    }

    public ChainPersistenceRecords.RouteDecisionRecord commitDirect(
            InitialRouteRequest request,
            PlannerPayload.DirectRoute payload) {
        Objects.requireNonNull(payload, "payload");
        requireCurrentInstruction(request.common(), "INITIAL", false);
        String routeDecisionId = routeDecisionId(request.common(), "INITIAL");
        ChainPersistenceRecords.ModelProposalRecord proposal = requireAccepted(
                request.common(), ChainProposalKind.PLANNER_DIRECT_ROUTE,
                payload, routeDecisionId);
        if (payload.needsTool() || payload.needsNetwork()
                || payload.needsProject() || payload.needsPersistentProgress()) {
            throw failure(ChainRouteException.Code.DIRECT_BOUNDARY_VIOLATION,
                    "DIRECT route crossed a persistent execution boundary");
        }
        ChainPersistenceRecords.RouteDecisionRecord decision = initialDecision(
                request, proposal, ChainExecutionMode.DIRECT, payload.routeReason(),
                ChainRouteCanonical.canonical(java.util.Map.of(
                        "specification", payload.directTaskSpecification())),
                canonicalList(payload.userConstraints()),
                canonicalList(payload.answerRequiredRefs()), false, false, false, false);
        return appendInitial(decision, request.common(), payload);
    }

    public ChainPersistenceRecords.RouteDecisionRecord commitPersistent(
            InitialRouteRequest request,
            PlannerPayload.PersistentPlan payload) {
        Objects.requireNonNull(payload, "payload");
        if (!payload.routingBoundary().requiresPersistentExecution()) {
            throw failure(ChainRouteException.Code.DIRECT_BOUNDARY_VIOLATION,
                    "PERSISTENT_ROUTE_WITHOUT_REQUIREMENT");
        }
        requireCurrentInstruction(request.common(), "INITIAL", false);
        String routeDecisionId = routeDecisionId(request.common(), "INITIAL");
        ChainPersistenceRecords.ModelProposalRecord proposal = requireAccepted(
                request.common(), ChainProposalKind.PLANNER_PERSISTENT_PLAN,
                payload, routeDecisionId);
        ChainPersistenceRecords.RouteDecisionRecord decision = initialDecision(
                request, proposal, ChainExecutionMode.PERSISTENT_PLAN_EXECUTE,
                "accepted PERSISTENT_PLAN requires persistent execution",
                null, null, null,
                payload.routingBoundary().needsTool(),
                payload.routingBoundary().needsNetwork(),
                payload.routingBoundary().needsProject(),
                payload.routingBoundary().needsPersistentProgress());
        return appendInitial(decision, request.common(), payload);
    }

    public ChainPersistenceRecords.RouteDecisionRecord escalate(
            EscalationRequest request,
            AnswerPayload.EscalateToPersistent payload) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(payload, "payload");
        requireCurrentInstruction(request.common(), "ESCALATION", true);
        String routeDecisionId = routeDecisionId(
                request.common(), "ESCALATION");
        ChainPersistenceRecords.ModelProposalRecord proposal = requireAccepted(
                request.common(), ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT,
                payload, routeDecisionId);
        List<ChainPersistenceRecords.RouteDecisionRecord> routes = routes(request.common().taskId());
        ChainPersistenceRecords.RouteDecisionRecord parent = routes.stream()
                .filter(route -> route.routeDecisionId().equals(payload.directRouteDecisionRef()))
                .findFirst().orElseThrow(() -> failure(
                        ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                        "escalation must bind the formal DIRECT route"));
        if (parent.route() != ChainExecutionMode.DIRECT
                || parent.decisionKind() != ChainPersistenceRecords.RouteDecisionType.INITIAL
                || !parent.instructionId().equals(request.common().instructionId())) {
            throw failure(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                    "escalation parent is not the current instruction's initial DIRECT route");
        }
        ChainPersistenceRecords.RouteDecisionRecord decision =
                new ChainPersistenceRecords.RouteDecisionRecord(
                        routeDecisionId, request.common().taskId(), request.common().eventId(),
                        request.common().instructionId(), proposal.proposalId(),
                        ChainPersistenceRecords.RouteDecisionType.ESCALATION, 1,
                        ChainExecutionMode.PERSISTENT_PLAN_EXECUTE,
                        payload.escalationReason(), null, null, null,
                        !payload.requiredTools().isEmpty(), false,
                        !payload.requiredProjectEvidence().isEmpty(),
                        payload.persistentProgressRequired(), parent.routeDecisionId(),
                        payload.escalationReason(), null, request.common().createdAt());
        ChainPersistenceRecords.RouteDecisionRecord same = routes.stream()
                .filter(route -> route.routeDecisionId().equals(routeDecisionId))
                .findFirst().orElse(null);
        if (same != null) {
            verifySame(same, decision);
        } else if (routes.stream().anyMatch(route -> route.instructionId()
                .equals(request.common().instructionId())
                && route.decisionKind()
                == ChainPersistenceRecords.RouteDecisionType.ESCALATION)) {
            throw failure(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                    "one instruction version may be escalated only once");
        }
        validateAcceptedPrefix(proposal, routeDecisionId, false);
        return appendAndBind(decision, proposal);
    }

    private ChainPersistenceRecords.RouteDecisionRecord appendInitial(
            ChainPersistenceRecords.RouteDecisionRecord decision,
            CommonRequest request,
            Object payload) {
        List<ChainPersistenceRecords.RouteDecisionRecord> routes = routes(request.taskId());
        ChainPersistenceRecords.RouteDecisionRecord same = routes.stream()
                .filter(route -> route.routeDecisionId().equals(decision.routeDecisionId()))
                .findFirst().orElse(null);
        if (same != null) {
            verifySame(same, decision);
        } else if (routes.stream().anyMatch(route -> route.instructionId()
                .equals(request.instructionId())
                && route.decisionKind() == ChainPersistenceRecords.RouteDecisionType.INITIAL)) {
            throw failure(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                    "one instruction version may have only one initial route");
        }
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.proposalId())
                .orElseThrow(() -> failure(
                        ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "route proposal does not exist"));
        if (!proposal.proposalId().equals(decision.proposalId())
                || !proposal.taskId().equals(decision.taskId())) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "route decision crossed its proposal identity boundary");
        }
        validatePayload(proposal, payload);
        validateAcceptedPrefix(proposal, decision.routeDecisionId(), false);
        return appendAndBind(decision, proposal);
    }

    private ChainPersistenceRecords.RouteDecisionRecord appendAndBind(
            ChainPersistenceRecords.RouteDecisionRecord decision,
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        if (!decision.proposalId().equals(proposal.proposalId())
                || !decision.taskId().equals(proposal.taskId())) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "formal route and proposal identities do not match");
        }
        String sourceDigest = ChainRouteCanonical.sha256(
                decision.routeDecisionId() + "\0" + decision.taskId() + "\0"
                        + decision.instructionId() + "\0" + decision.proposalId() + "\0"
                        + decision.route().name() + "\0" + decision.decisionOrdinal());
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        decision.eventId(), decision.taskId(), "ROUTE_DECISION",
                        decision.transitionId(), sourceDigest, decision.createdAt());
        var appended = writer.appendRouteDecision(
                new ChainPersistenceRecords.AuthoritativeFact<>(event, decision));
        ChainPersistenceRecords.RouteDecisionRecord stored = appended.fact();
        verifySame(stored, decision, appended.event());
        proposalBinder.bindOfficialResult(
                decision.taskId(), proposal.proposalId(),
                "ROUTE_DECISION", decision.routeDecisionId());
        validateAcceptedPrefix(proposal, decision.routeDecisionId(), true);
        return stored;
    }

    private ChainPersistenceRecords.ModelProposalRecord requireAccepted(
            CommonRequest request,
            ChainProposalKind kind,
            Object payload,
            String expectedOfficialRouteId) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.proposalId())
                .orElseThrow(() -> failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "route proposal does not exist"));
        if (!proposal.taskId().equals(request.taskId()) || proposal.proposalKind() != kind) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "route proposal task or kind does not match");
        }
        validatePayload(proposal, payload);
        validateAcceptedPrefix(proposal, expectedOfficialRouteId, false);
        return proposal;
    }

    private void requireCurrentInstruction(
            CommonRequest request, String identityType, boolean escalation) {
        ChainInstructionState state = instructions.read(request.taskId());
        if (!state.currentInstruction().instructionId().equals(request.instructionId())) {
            throw failure(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                    "route proposal targets a stale instruction version");
        }
        boolean replay = workflow.findRouteDecisions(request.taskId()).stream()
                .anyMatch(route -> route.routeDecisionId()
                        .equals(routeDecisionId(request, identityType)));
        if (replay) {
            return;
        }
        if (escalation) {
            if (state.gate() != ChainInstructionState.Gate.DIRECT_ANSWER) {
                throw failure(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                        "only the current formal DIRECT route may be escalated");
            }
        } else if (state.gate() != ChainInstructionState.Gate.PLANNING
                && state.gate() != ChainInstructionState.Gate.PAUSED_FOR_DISPOSITION) {
            throw failure(ChainRouteException.Code.ROUTE_MONOTONICITY_VIOLATION,
                    "current instruction gate does not admit an initial route");
        }
    }

    private void validateAcceptedPrefix(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            String expectedOfficialRouteId,
            boolean officialBindingRequired) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord::stateSequence))
                .toList();
        if (states.isEmpty()) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "route requires a formal ACCEPTED proposal state");
        }
        List<ChainProposalState> prefix = new java.util.ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ChainPersistenceRecords.ProposalStateEventRecord state = states.get(index);
            if (!proposal.proposalId().equals(state.proposalId())
                    || !proposal.taskId().equals(state.taskId())
                    || state.stateSequence() != index + 1L) {
                throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "proposal state prefix crosses an identity boundary");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "proposal state prefix is invalid");
            }
            prefix.add(state.stateKind());
        }
        ChainPersistenceRecords.ProposalStateEventRecord first = states.get(0);
        if (first.stateKind() != ChainProposalState.ACCEPTED
                || first.officialAuthorityType() != null
                || first.officialAuthorityRef() != null
                || states.size() > 2) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "route requires one accepted proposal state prefix");
        }
        if (states.size() == 1) {
            if (officialBindingRequired) {
                throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                        "route proposal was not bound to its formal RouteDecision");
            }
            return;
        }
        ChainPersistenceRecords.ProposalStateEventRecord replacement = states.get(1);
        if (replacement.stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !"ROUTE_DECISION".equals(replacement.officialAuthorityType())
                || !expectedOfficialRouteId.equals(
                replacement.officialAuthorityRef())) {
            throw failure(ChainRouteException.Code.PROPOSAL_NOT_ACCEPTED,
                    "proposal was already replaced by a different official result");
        }
    }

    private static void validatePayload(
            ChainPersistenceRecords.ModelProposalRecord proposal, Object payload) {
        String canonical = payload instanceof PlannerPayload.DirectRoute
                ? ChainRouteCanonical.payload(
                payload, proposal.bodyAuthorityRef())
                : ChainRouteCanonical.payload(payload);
        if (!canonical.equals(proposal.payload().json())
                || !ChainRouteCanonical.sha256(canonical).equals(proposal.payload().sha256())) {
            throw failure(ChainRouteException.Code.PROPOSAL_PAYLOAD_MISMATCH,
                    "typed route payload does not match the accepted immutable proposal");
        }
    }

    private static ChainPersistenceRecords.RouteDecisionRecord initialDecision(
            InitialRouteRequest request,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainExecutionMode route,
            String reason,
            ChainPersistenceRecords.CanonicalJson directSpecification,
            ChainPersistenceRecords.CanonicalJson constraints,
            ChainPersistenceRecords.CanonicalJson answerRefs,
            boolean needsTool,
            boolean needsNetwork,
            boolean needsProject,
            boolean needsProgress) {
        return new ChainPersistenceRecords.RouteDecisionRecord(
                routeDecisionId(request.common(), "INITIAL"), request.common().taskId(),
                request.common().eventId(), request.common().instructionId(),
                proposal.proposalId(), ChainPersistenceRecords.RouteDecisionType.INITIAL, 0,
                route, reason, directSpecification, constraints, answerRefs,
                needsTool, needsNetwork, needsProject, needsProgress,
                null, null, null, request.common().createdAt());
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalList(List<String> values) {
        String json = ChainRouteCanonical.jsonList(values);
        return new ChainPersistenceRecords.CanonicalJson(
                1, ChainRouteCanonical.sha256(json), json);
    }

    private static String routeDecisionId(CommonRequest request, String type) {
        return "route." + ChainRouteCanonical.sha256(
                request.taskId() + "\0" + request.instructionId() + "\0"
                        + request.proposalId() + "\0" + type);
    }

    private List<ChainPersistenceRecords.RouteDecisionRecord> routes(String taskId) {
        List<ChainPersistenceRecords.RouteDecisionRecord> routes =
                workflow.findRouteDecisions(taskId);
        if (routes.stream().anyMatch(route -> !taskId.equals(route.taskId()))) {
            throw failure(ChainRouteException.Code.FORMAL_FACTS_INCONSISTENT,
                    "route repository returned a fact from another task");
        }
        return List.copyOf(routes);
    }

    private static void verifySame(
            ChainPersistenceRecords.RouteDecisionRecord stored,
            ChainPersistenceRecords.RouteDecisionRecord requested) {
        if (!sameImmutableContents(stored, requested)) {
            throw failure(ChainRouteException.Code.ROUTE_REPLAY_MISMATCH,
                    "route decision replay changed immutable contents");
        }
    }

    private static void verifySame(
            ChainPersistenceRecords.RouteDecisionRecord stored,
            ChainPersistenceRecords.RouteDecisionRecord requested,
            ChainPersistenceRecords.AuthorityEventRecord event) {
        if (!sameImmutableContents(stored, requested)
                || !sameDatabaseInstant(
                        stored.createdAt(), event.committedAt())) {
            throw failure(ChainRouteException.Code.ROUTE_REPLAY_MISMATCH,
                    "route decision replay changed immutable contents");
        }
    }

    private static boolean sameImmutableContents(
            ChainPersistenceRecords.RouteDecisionRecord left,
            ChainPersistenceRecords.RouteDecisionRecord right) {
        return left.routeDecisionId().equals(right.routeDecisionId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.instructionId().equals(right.instructionId())
                && left.proposalId().equals(right.proposalId())
                && left.decisionKind() == right.decisionKind()
                && left.decisionOrdinal() == right.decisionOrdinal()
                && left.route() == right.route()
                && left.routeReason().equals(right.routeReason())
                && Objects.equals(left.directTaskSpecification(),
                        right.directTaskSpecification())
                && Objects.equals(left.userConstraints(), right.userConstraints())
                && Objects.equals(left.answerRequiredRefs(), right.answerRequiredRefs())
                && left.needsTool() == right.needsTool()
                && left.needsNetwork() == right.needsNetwork()
                && left.needsProject() == right.needsProject()
                && left.needsPersistentProgress() == right.needsPersistentProgress()
                && Objects.equals(left.parentRouteDecisionId(),
                        right.parentRouteDecisionId())
                && Objects.equals(left.escalationReason(), right.escalationReason())
                && Objects.equals(left.transitionId(), right.transitionId());
    }

    private static boolean sameDatabaseInstant(Instant left, Instant right) {
        return left.truncatedTo(ChronoUnit.MICROS)
                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private static ChainRouteException failure(
            ChainRouteException.Code code, String message) {
        return new ChainRouteException(code, message);
    }

    public record CommonRequest(
            String taskId,
            String instructionId,
            String proposalId,
            String eventId,
            Instant createdAt) {
        public CommonRequest {
            taskId = required(taskId, "taskId");
            instructionId = required(instructionId, "instructionId");
            proposalId = required(proposalId, "proposalId");
            eventId = required(eventId, "eventId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record InitialRouteRequest(CommonRequest common) {
        public InitialRouteRequest { Objects.requireNonNull(common, "common"); }
    }

    public record EscalationRequest(CommonRequest common) {
        public EscalationRequest { Objects.requireNonNull(common, "common"); }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Narrow adapter for the append-only Proposal official-result state. */
    public interface ProposalOfficialBinder {
        void bindOfficialResult(
                String taskId,
                String proposalId,
                String authorityType,
                String authorityRef);
    }
}
