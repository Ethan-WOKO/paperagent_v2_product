package io.paperagent.v2.chain.delivery;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.route.ChainRouteRuntime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Sole runtime allowed to bind an Answer content ref to Delivery/message facts. */
public final class ChainDeliveryRuntime {
    private static final String DELIVERY_WRITE_ERROR =
            "CHAIN_DELIVERY_MESSAGE_WRITE_FAILED";
    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;
    private final ChainModelRepository models;
    private final ChainDeliveryWriter deliveries;
    private final ChainDeliveryMessagePort messages;
    private final ChainRouteRuntime.ProposalOfficialBinder proposalBinder;
    private final Function<String, ChainRuntimePolicy> runtimePolicies;

    public ChainDeliveryRuntime(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            ChainModelRepository models,
            ChainDeliveryWriter deliveries,
            ChainDeliveryMessagePort messages,
            ChainRouteRuntime.ProposalOfficialBinder proposalBinder,
            Function<String, ChainRuntimePolicy> runtimePolicies) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.models = Objects.requireNonNull(models, "models");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.proposalBinder = Objects.requireNonNull(
                proposalBinder, "proposalBinder");
        this.runtimePolicies = Objects.requireNonNull(
                runtimePolicies, "runtimePolicies");
    }

    public Started begin(BeginCommand command) {
        Objects.requireNonNull(command, "command");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId())
                .orElseThrow(() -> invalidSource("Delivery task does not exist"));
        ChainPersistenceRecords.CommandRecord sourceCommand = foundations
                .findCommand(command.sourceCommandId())
                .orElseThrow(() -> invalidSource(
                        "Delivery source command does not exist"));
        if (sourceCommand.userId() != task.userId()
                || sourceCommand.sessionId() != task.sessionId()
                || sourceCommand.status() == ChainCommandStatus.FAILED
                || (sourceCommand.resultTaskId() != null
                && !command.taskId().equals(sourceCommand.resultTaskId()))
                || (!task.createdByCommandId().equals(
                        sourceCommand.commandId())
                && !command.taskId().equals(sourceCommand.targetTaskId()))
                || !commandBoundToTask(task, sourceCommand)) {
            throw invalidSource("Delivery source command crosses task ownership");
        }

        AuthorityOrder authority = AuthorityOrder.load(
                foundations, command.taskId());
        ChainPersistenceRecords.ModelProposalRecord proposal =
                acceptedAnswer(command, authority, false, null);
        validateSource(command, authority);
        ChainPersistenceRecords.ContentRecord content = models
                .findContent(proposal.bodyAuthorityRef())
                .orElseThrow(() -> contentFailure(
                        "Answer body content does not exist"));
        validateContent(command, proposal, content);

        String sourceType = command.source().type();
        String sourceRef = command.source().ref();
        String identity = ChainDeliveryCanonical.sha256(
                command.taskId() + "\0" + sourceType + "\0" + sourceRef
                        + "\0" + command.proposalId() + "\0"
                        + content.contentId());
        String deliveryId = "delivery." + identity;
        acceptedAnswer(command, authority, false, deliveryId);
        ChainPersistenceRecords.DeliveryRecord existing = finalization
                .findDeliveries(command.taskId()).stream()
                .filter(value -> deliveryId.equals(value.deliveryId()))
                .findFirst().orElse(null);
        if (existing != null) {
            validateExistingDelivery(command, content, existing);
            AuthorityOrder.load(foundations, command.taskId()).require(
                    existing, "DELIVERY", null,
                    ChainDeliveryCanonical.sha256(
                            sourceType + "\0" + sourceRef + "\0"
                                    + command.proposalId() + "\0"
                                    + content.contentId()));
            ChainPersistenceRecords.DeliveryEventRecord pending =
                    appendPending(existing, existing.createdAt());
            proposalBinder.bindOfficialResult(
                    command.taskId(), command.proposalId(),
                    "DELIVERY", existing.deliveryId());
            acceptedAnswer(command,
                    AuthorityOrder.load(foundations, command.taskId()),
                    true, existing.deliveryId());
            return new Started(existing, pending);
        }
        long messageId = messages.reserveAssistantMessage(
                new ChainDeliveryMessagePort.Reservation(
                        deliveryId, command.taskId(), content.contentId(),
                        content.bodySha256()));
        if (messageId < 1) {
            throw messageFailure("message reservation returned invalid identity");
        }
        String eventId = "delivery.event." + identity;
        ChainPersistenceRecords.DeliveryRecord requested =
                new ChainPersistenceRecords.DeliveryRecord(
                        deliveryId, command.taskId(), eventId,
                        command.sourceCommandId(),
                        command.source() instanceof RouteSource ? sourceRef : null,
                        command.source() instanceof TaskOutcomeSource
                                ? sourceRef : null,
                        command.source() instanceof GapSource ? sourceRef : null,
                        command.source() instanceof DecisionSource ? sourceRef : null,
                        content.contentId(), messageId, command.committedAt());
        ChainPersistenceRecords.DeliveryRecord stored = appendDelivery(
                requested, ChainDeliveryCanonical.sha256(
                        sourceType + "\0" + sourceRef + "\0"
                                + command.proposalId() + "\0"
                                + content.contentId()));
        ChainPersistenceRecords.DeliveryEventRecord pending = appendPending(
                stored, command.committedAt());
        proposalBinder.bindOfficialResult(
                command.taskId(), command.proposalId(),
                "DELIVERY", stored.deliveryId());
        acceptedAnswer(
                command,
                AuthorityOrder.load(foundations, command.taskId()),
                true,
                stored.deliveryId());
        return new Started(stored, pending);
    }

    public Attempted attempt(
            String taskId,
            String deliveryId,
            Instant committedAt) {
        required(taskId, "taskId");
        required(deliveryId, "deliveryId");
        Objects.requireNonNull(committedAt, "committedAt");
        ChainPersistenceRecords.DeliveryRecord delivery = findDelivery(
                taskId, deliveryId);
        ChainPersistenceRecords.ContentRecord content = models
                .findContent(delivery.answerContentId())
                .orElseThrow(() -> contentFailure(
                        "Delivery Answer content no longer exists"));
        if (!delivery.taskId().equals(content.taskId())
                || content.contentKind() != ChainContentKind.ANSWER_BODY
                || !ChainDeliveryCanonical.sha256(content.body()).equals(
                content.bodySha256())) {
            throw contentFailure("Delivery Answer content authority changed");
        }

        AuthorityOrder authority = AuthorityOrder.load(
                foundations, delivery.taskId());
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposalByInvocation(content.invocationId())
                .filter(value -> content.contentId().equals(
                        value.bodyAuthorityRef()))
                .orElseThrow(() -> proposalFailure(
                        "Delivery Answer proposal no longer exists"));
        requireDeliveryBoundProposal(proposal, delivery, authority);
        authority.require(delivery, "DELIVERY", null,
                deliverySourceDigest(delivery, proposal.proposalId()));
        List<ChainPersistenceRecords.DeliveryEventRecord> prefix =
                eventPrefix(delivery, authority);
        ChainRuntimePolicy runtimePolicy = policy(delivery.taskId());
        ChainPersistenceRecords.DeliveryEventRecord latest =
                prefix.get(prefix.size() - 1);
        if (latest.eventKind() == ChainDeliveryStatus.SUCCEEDED
                || latest.eventKind() == ChainDeliveryStatus.DELIVERY_FAILED) {
            return new Attempted(delivery, latest, true);
        }
        int attemptNo = latest.attemptNo() + 1;
        if (attemptNo > runtimePolicy.deliveryAttemptsTotal()) {
            throw eventFailure("Delivery attempt exceeds runtime policy");
        }
        long sequence = latest.eventSequence() + 1L;
        String attemptIdentity = ChainDeliveryCanonical.sha256(
                delivery.deliveryId() + "\0" + attemptNo);
        String successEventId = "delivery.success.event." + attemptIdentity;
        String failureEventId = "delivery.failure.event." + attemptIdentity;
        boolean terminalOnFailure = attemptNo
                == runtimePolicy.deliveryAttemptsTotal();
        ChainDeliveryMessagePort.AttemptSubmission submission =
                Objects.requireNonNull(messages.attempt(
                        new ChainDeliveryMessagePort.AttemptCommand(
                                delivery.deliveryId(), delivery.taskId(),
                                content.contentId(), content.bodySha256(),
                                delivery.assistantMessageId(), attemptNo, sequence,
                                successEventId, failureEventId, terminalOnFailure,
                                runtimePolicy.policyVersion(), committedAt)),
                        "Delivery attempt submission");
        ChainPersistenceRecords.DeliveryEventRecord event = submission.event();
        validateAttemptResult(delivery, event, attemptNo, sequence,
                successEventId, failureEventId, terminalOnFailure, committedAt);
        AuthorityOrder committedAuthority = AuthorityOrder.load(
                foundations, delivery.taskId());
        committedAuthority.require(
                event, "DELIVERY_" + event.eventKind().name());
        eventPrefix(delivery, committedAuthority);
        return new Attempted(delivery, event, submission.replayed());
    }

    private ChainPersistenceRecords.DeliveryRecord appendDelivery(
            ChainPersistenceRecords.DeliveryRecord requested,
            String sourceDigest) {
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        requested.eventId(), requested.taskId(), "DELIVERY",
                        null, sourceDigest, requested.createdAt());
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.DeliveryRecord> appended =
                deliveries.appendDelivery(
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                event, requested));
        requireAppend(event, requested, appended);
        return appended.fact();
    }

    private ChainPersistenceRecords.DeliveryEventRecord appendPending(
            ChainPersistenceRecords.DeliveryRecord delivery,
            Instant committedAt) {
        List<ChainPersistenceRecords.DeliveryEventRecord> existing =
                finalization.findDeliveryEvents(delivery.deliveryId()).stream()
                        .sorted(Comparator.comparingLong(
                                ChainPersistenceRecords.DeliveryEventRecord
                                        ::eventSequence))
                        .toList();
        if (!existing.isEmpty()) {
            ChainPersistenceRecords.DeliveryEventRecord pending =
                    existing.get(0);
            if (pending.eventSequence() != 1L
                    || pending.eventKind() != ChainDeliveryStatus.PENDING
                    || pending.attemptNo() != 0
                    || !pending.taskId().equals(delivery.taskId())
                    || !pending.deliveryId().equals(delivery.deliveryId())
                    || !pending.committedAt().equals(delivery.createdAt())) {
                throw eventFailure("stored Delivery PENDING identity changed");
            }
            AuthorityOrder.load(foundations, delivery.taskId()).require(
                    pending, "DELIVERY_PENDING", null,
                    ChainDeliveryCanonical.sha256(
                            delivery.deliveryId() + "\0PENDING"));
            return pending;
        }
        String eventId = "delivery.pending.event."
                + ChainDeliveryCanonical.sha256(delivery.deliveryId());
        ChainPersistenceRecords.DeliveryEventRecord requested =
                new ChainPersistenceRecords.DeliveryEventRecord(
                        delivery.deliveryId(), 1, delivery.taskId(), eventId,
                        ChainDeliveryStatus.PENDING, 0, null,
                        policy(delivery.taskId()).policyVersion(), committedAt);
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, delivery.taskId(), "DELIVERY_PENDING", null,
                        ChainDeliveryCanonical.sha256(delivery.deliveryId()
                                + "\0PENDING"), committedAt);
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.DeliveryEventRecord> appended =
                deliveries.appendDeliveryEvent(
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                event, requested));
        requireAppend(event, requested, appended);
        return appended.fact();
    }

    private void validateSource(
            BeginCommand command,
            AuthorityOrder authority) {
        String ref = command.source().ref();
        AnswerPayload payload = command.payload();
        if (command.source() instanceof RouteSource) {
            ChainPersistenceRecords.RouteDecisionRecord route = workflow
                    .findRouteDecisions(command.taskId()).stream()
                    .filter(value -> ref.equals(value.routeDecisionId()))
                    .findFirst().orElseThrow(() -> invalidSource(
                            "formal DIRECT route does not exist"));
            authority.require(route, "ROUTE_DECISION", route.transitionId(),
                    ChainDeliveryCanonical.sha256(
                            route.routeDecisionId() + "\0" + route.taskId()
                                    + "\0" + route.instructionId() + "\0"
                                    + route.proposalId() + "\0"
                                    + route.route().name() + "\0"
                                    + route.decisionOrdinal()));
            if (route.route() != ChainExecutionMode.DIRECT
                    || !sourceCommandForInstruction(
                    command.sourceCommandId(), route.instructionId())
                    || !(payload instanceof AnswerPayload.DirectAnswer answer)
                    || !ref.equals(answer.routeDecisionRef())
                    || !route.directTaskSpecification().json().equals(
                    ChainDeliveryCanonical.jsonValue(
                            Map.of("specification",
                                    answer.directTaskSpecification())))
                    || !route.answerRequiredRefs().json().equals(
                    ChainDeliveryCanonical.jsonValue(answer.factRefs()))
                    || route.needsTool() || route.needsNetwork()
                    || route.needsProject() || route.needsPersistentProgress()) {
                throw invalidSource("DIRECT Delivery source does not match Answer");
            }
            return;
        }
        if (command.source() instanceof TaskOutcomeSource) {
            ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                    .findTaskOutcome(command.taskId())
                    .filter(value -> ref.equals(value.outcomeId()))
                    .orElseThrow(() -> invalidSource(
                            "formal TaskOutcome does not exist"));
            authority.require(outcome, "TASK_OUTCOME",
                    outcome.outcomeType() == ChainTaskOutcomeStatus.COMPLETED
                            ? outcome.sourceDecisionId() : null,
                    ChainDeliveryCanonical.sha256(
                            outcome.outcomeType() + "\0"
                                    + outcome.sourceDecisionId()));
            boolean finalDelivery = payload
                    instanceof AnswerPayload.FinalDelivery answer
                    && ref.equals(answer.taskOutcomeRef())
                    && command.sourceCommandId().equals(
                    outcome.sourceCommandId())
                    && exactFinalDelivery(answer, outcome);
            boolean status = payload
                    instanceof AnswerPayload.StatusOrFailure answer
                    && outcome.outcomeType()
                    != ChainTaskOutcomeStatus.COMPLETED
                    && command.sourceCommandId().equals(
                    outcome.sourceCommandId())
                    && ref.equals(answer.blockerOrTaskOutcomeRef())
                    && ref.equals(answer.taskOrStepStatusRef())
                    && latestDecisionRef(command.taskId(), authority).equals(
                    answer.latestDecisionRef());
            if (!finalDelivery && !status) {
                throw invalidSource(
                        "TaskOutcome Delivery source does not match Answer");
            }
            return;
        }
        if (command.source() instanceof GapSource) {
            ChainPersistenceRecords.PendingItemRecord gap = workflow
                    .findPendingItems(command.taskId()).stream()
                    .filter(value -> ref.equals(value.gapId()))
                    .findFirst().orElseThrow(() -> invalidSource(
                            "formal PendingItem does not exist"));
            authority.require(gap, "PENDING_ITEM", null,
                    gap.gapIdentitySha256());
            requireCurrentSourceCommand(
                    command, authority, gap.eventId());
            requireCurrentPendingGap(gap, authority);
            if (!(payload instanceof AnswerPayload.UserQuestion answer)
                    || !ref.equals(answer.gapId())) {
                throw invalidSource("gap Delivery source does not match Answer");
            }
            return;
        }
        ChainPersistenceRecords.ReviewDecisionRecord decision = workflow
                .findReviewDecisions(command.taskId()).stream()
                .filter(value -> ref.equals(value.reviewDecisionId()))
                .findFirst().orElseThrow(() -> invalidSource(
                        "formal ReviewDecision does not exist"));
        authority.require(decision, "REVIEW_DECISION", null,
                ChainDeliveryCanonical.sha256(
                        decision.proposalId() + "\0"
                                + decision.reviewObjectType() + "\0"
                                + decision.reviewObjectId() + "\0"
                                + decision.versionFenceSha256()));
        requireCurrentSourceCommand(
                command, authority, decision.eventId());
        if (!(payload instanceof AnswerPayload.StatusOrFailure answer)
                || !ref.equals(answer.latestDecisionRef())
                || !ref.equals(answer.blockerOrTaskOutcomeRef())
                || !decision.reviewObjectId().equals(
                answer.taskOrStepStatusRef())
                || !ref.equals(latestDecisionRef(
                command.taskId(), authority))) {
            throw invalidSource("decision Delivery source does not match Answer");
        }
    }

    private ChainPersistenceRecords.ModelProposalRecord acceptedAnswer(
            BeginCommand command,
            AuthorityOrder authority,
            boolean boundRequired,
            String deliveryId) {
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(command.proposalId())
                .orElseThrow(() -> proposalFailure(
                        "Answer proposal does not exist"));
        if (!command.taskId().equals(proposal.taskId())
                || proposal.role() != ChainRole.ANSWER
                || proposal.proposalKind() != command.payload().kind()
                || proposal.bodyAuthorityRef() == null
                || !"ANSWER_BODY".equals(proposal.bodyAuthorityType())
                || !ChainDeliveryCanonical.materializedPayload(
                command.payload(), proposal.bodyAuthorityRef()).equals(
                proposal.payload().json())
                || !ChainDeliveryCanonical.sha256(
                proposal.payload().json()).equals(proposal.payload().sha256())) {
            throw proposalFailure(
                    "accepted Answer payload/body authority is inconsistent");
        }
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords
                                .ProposalStateEventRecord::stateSequence))
                .toList();
        if (states.isEmpty() || states.size() > 2) {
            throw proposalFailure("Answer proposal state prefix is invalid");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ChainPersistenceRecords.ProposalStateEventRecord state =
                    states.get(index);
            authority.require(state, "PROPOSAL_" + state.stateKind().name());
            if (!proposal.proposalId().equals(state.proposalId())
                    || !proposal.taskId().equals(state.taskId())
                    || state.stateSequence() != index + 1L) {
                throw proposalFailure(
                        "Answer proposal state crossed identity boundary");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw proposalFailure("Answer proposal state prefix is illegal");
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw proposalFailure("Answer proposal is not accepted");
        }
        if (states.size() == 1) {
            if (boundRequired) {
                throw proposalFailure("Answer proposal was not bound to Delivery");
            }
        } else {
            ChainPersistenceRecords.ProposalStateEventRecord bound = states.get(1);
            if (!"DELIVERY".equals(bound.officialAuthorityType())
                    || (deliveryId != null
                    && !deliveryId.equals(bound.officialAuthorityRef()))) {
                throw proposalFailure(
                        "Answer proposal is bound to another official result");
            }
        }
        return proposal;
    }

    private void requireDeliveryBoundProposal(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.DeliveryRecord delivery,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        if (states.size() != 2) {
            throw proposalFailure(
                    "Delivery Answer proposal is not officially bound");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            ChainPersistenceRecords.ProposalStateEventRecord state =
                    states.get(index);
            authority.require(state, "PROPOSAL_" + state.stateKind().name());
            if (!proposal.proposalId().equals(state.proposalId())
                    || !delivery.taskId().equals(state.taskId())
                    || state.stateSequence() != index + 1L) {
                throw proposalFailure(
                        "Delivery Answer proposal state changed identity");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw proposalFailure(
                        "Delivery Answer proposal state prefix is illegal");
            }
            prefix.add(state.stateKind());
        }
        ChainPersistenceRecords.ProposalStateEventRecord bound = states.get(1);
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED
                || bound.stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !"DELIVERY".equals(bound.officialAuthorityType())
                || !delivery.deliveryId().equals(
                bound.officialAuthorityRef())) {
            throw proposalFailure(
                    "Delivery Answer proposal is bound to another result");
        }
    }

    private void requireCurrentPendingGap(
            ChainPersistenceRecords.PendingItemRecord gap,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.PendingItemEventRecord> events = workflow
                .findPendingItemEvents(gap.gapId()).stream()
                .sorted(Comparator.comparingLong(value ->
                        authority.sequence(value.eventId()))).toList();
        ChainPendingItemStatus status = ChainPendingItemStatus.PENDING;
        int responseRound = 0;
        String answerInstructionId = null;
        for (ChainPersistenceRecords.PendingItemEventRecord event : events) {
            authority.require(event,
                    "PENDING_ITEM_" + event.eventKind().name(), null,
                    pendingItemEventDigest(event));
            if (!gap.gapId().equals(event.gapId())
                    || !gap.taskId().equals(event.taskId())) {
                throw invalidSource(
                        "PendingItem event prefix crossed identity");
            }
            boolean valid = switch (event.eventKind()) {
                case RESPONSE_RECEIVED ->
                        status == ChainPendingItemStatus.PENDING
                                && event.responseRound() == responseRound + 1
                                && hasText(event.answerInstructionId())
                                && event.validationInvocationId() == null
                                && event.gapValidationOutcome() == null;
                case PENDING ->
                        status == ChainPendingItemStatus.RESPONSE_RECEIVED
                                && event.responseRound() == responseRound
                                && Objects.equals(event.answerInstructionId(),
                                answerInstructionId)
                                && hasText(event.validationInvocationId())
                                && event.gapValidationOutcome()
                                == GapValidation.Outcome.STILL_PENDING;
                case RESOLVED -> {
                    boolean common = event.responseRound() == responseRound
                            && Objects.equals(event.answerInstructionId(),
                            answerInstructionId);
                    boolean permission = common
                            && (status == ChainPendingItemStatus.PENDING
                            || status
                            == ChainPendingItemStatus.RESPONSE_RECEIVED)
                            && gap.pendingType()
                            == ChainPendingItemType.PERMISSION
                            && event.validationInvocationId() == null
                            && event.gapValidationOutcome() == null;
                    boolean model = common
                            && status
                            == ChainPendingItemStatus.RESPONSE_RECEIVED
                            && hasText(event.validationInvocationId())
                            && event.gapValidationOutcome()
                            == GapValidation.Outcome.RESOLVED;
                    yield permission || model;
                }
                case REJECTED, CANCELLED ->
                        (status == ChainPendingItemStatus.PENDING
                                || status
                                == ChainPendingItemStatus.RESPONSE_RECEIVED)
                                && event.responseRound() == responseRound
                                && Objects.equals(event.answerInstructionId(),
                                answerInstructionId)
                                && event.validationInvocationId() == null
                                && event.gapValidationOutcome() == null;
            };
            if (!valid) {
                throw invalidSource("PendingItem event prefix is invalid");
            }
            status = event.eventKind();
            if (status == ChainPendingItemStatus.RESPONSE_RECEIVED) {
                responseRound = event.responseRound();
                answerInstructionId = event.answerInstructionId();
            }
        }
        if (status != ChainPendingItemStatus.PENDING) {
            throw invalidSource("gap Delivery requires a current PENDING item");
        }
    }

    private static void validateContent(
            BeginCommand command,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ContentRecord content) {
        if (!command.taskId().equals(content.taskId())
                || !proposal.invocationId().equals(content.invocationId())
                || content.contentKind() != ChainContentKind.ANSWER_BODY
                || !proposal.bodyAuthorityRef().equals(content.contentId())
                || !answerBody(command.payload()).equals(content.body())
                || !ChainDeliveryCanonical.sha256(content.body()).equals(
                content.bodySha256())) {
            throw contentFailure(
                    "Delivery does not reference the single Answer body authority");
        }
    }

    private boolean sourceCommandForInstruction(
            String sourceCommandId,
            String instructionId) {
        return foundations.findInstruction(instructionId)
                .filter(value -> sourceCommandId.equals(value.commandId()))
                .isPresent();
    }

    private void requireCurrentSourceCommand(
            BeginCommand command,
            AuthorityOrder authority,
            String sourceEventId) {
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId()).orElseThrow(() ->
                        invalidSource("Delivery task no longer exists"));
        long sourceSequence = authority.sequence(sourceEventId);
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> prior =
                foundations.findTaskInstructions(
                        command.taskId(), sourceSequence).stream()
                .filter(value -> authority.sequence(value.eventId())
                        < sourceSequence)
                .toList();
        if (prior.isEmpty()) {
            if (task.createdByCommandId().equals(command.sourceCommandId())
                    && sourceCommandForInstruction(
                    command.sourceCommandId(), task.sourceInstructionId())) {
                return;
            }
            throw invalidSource(
                    "Delivery source command has no prior task binding");
        }
        var current = prior.stream().max(Comparator.comparingLong(value ->
                authority.sequence(value.eventId()))).orElseThrow();
        if (!sourceCommandForInstruction(
                command.sourceCommandId(), current.instructionId())) {
            throw invalidSource(
                    "Delivery source command is not the source-current instruction");
        }
    }

    private List<ChainPersistenceRecords.DeliveryEventRecord> eventPrefix(
            ChainPersistenceRecords.DeliveryRecord delivery,
            AuthorityOrder authority) {
        ChainRuntimePolicy runtimePolicy = policy(delivery.taskId());
        List<ChainPersistenceRecords.DeliveryEventRecord> prefix = finalization
                .findDeliveryEvents(delivery.deliveryId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords
                                .DeliveryEventRecord::eventSequence))
                .toList();
        if (prefix.isEmpty()) {
            throw eventFailure("Delivery lacks its PENDING event");
        }
        for (int index = 0; index < prefix.size(); index++) {
            ChainPersistenceRecords.DeliveryEventRecord event = prefix.get(index);
            authority.require(event, "DELIVERY_" + event.eventKind().name(),
                    null, deliveryEventDigest(event));
            if (!delivery.deliveryId().equals(event.deliveryId())
                    || !delivery.taskId().equals(event.taskId())
                    || event.eventSequence() != index + 1L
                    || !runtimePolicy.policyVersion().equals(
                    event.runtimePolicyVersion())) {
                throw eventFailure("Delivery event prefix identity is invalid");
            }
            if (index == 0
                    && (event.eventKind() != ChainDeliveryStatus.PENDING
                    || event.attemptNo() != 0)) {
                throw eventFailure("Delivery event prefix must start PENDING");
            }
            if (index > 0) {
                ChainPersistenceRecords.DeliveryEventRecord previous =
                        prefix.get(index - 1);
                if (event.attemptNo() != index
                        || event.eventKind() == ChainDeliveryStatus.PENDING
                        || ((event.eventKind()
                        == ChainDeliveryStatus.RETRYING
                        || event.eventKind()
                        == ChainDeliveryStatus.DELIVERY_FAILED)
                        && !DELIVERY_WRITE_ERROR.equals(event.errorCode()))
                        || previous.eventKind()
                        == ChainDeliveryStatus.SUCCEEDED
                        || previous.eventKind()
                        == ChainDeliveryStatus.DELIVERY_FAILED
                        || (event.eventKind() == ChainDeliveryStatus.RETRYING
                        && event.attemptNo()
                        >= runtimePolicy.deliveryAttemptsTotal())
                        || (event.eventKind()
                        == ChainDeliveryStatus.DELIVERY_FAILED
                        && event.attemptNo()
                        != runtimePolicy.deliveryAttemptsTotal())) {
                    throw eventFailure(
                            "Delivery event prefix transition is illegal");
                }
            }
        }
        return prefix;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateAttemptResult(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainPersistenceRecords.DeliveryEventRecord event,
            int attemptNo,
            long sequence,
            String successEventId,
            String failureEventId,
            boolean terminalOnFailure,
            Instant committedAt) {
        boolean success = event.eventKind() == ChainDeliveryStatus.SUCCEEDED;
        ChainDeliveryStatus expectedFailure = terminalOnFailure
                ? ChainDeliveryStatus.DELIVERY_FAILED
                : ChainDeliveryStatus.RETRYING;
        if (!delivery.deliveryId().equals(event.deliveryId())
                || !delivery.taskId().equals(event.taskId())
                || event.attemptNo() != attemptNo
                || event.eventSequence() != sequence
                || !policy(delivery.taskId()).policyVersion().equals(
                event.runtimePolicyVersion())
                || (success && !successEventId.equals(event.eventId()))
                || (success && event.errorCode() != null)
                || (!success && (!failureEventId.equals(event.eventId())
                || event.eventKind() != expectedFailure
                || !DELIVERY_WRITE_ERROR.equals(event.errorCode())))) {
            throw messageFailure(
                    "message attempt returned another Delivery event identity");
        }
    }

    private ChainRuntimePolicy policy(String taskId) {
        return Objects.requireNonNull(runtimePolicies.apply(taskId),
                "runtime policy");
    }

    private ChainPersistenceRecords.DeliveryRecord findDelivery(
            String taskId,
            String deliveryId) {
        return finalization.findDeliveries(taskId).stream()
                .filter(value -> deliveryId.equals(value.deliveryId()))
                .findFirst()
                .orElseThrow(() -> invalidSource("Delivery does not exist"));
    }

    private boolean commandBoundToTask(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.CommandRecord command) {
        if (task.createdByCommandId().equals(command.commandId())) {
            ChainPersistenceRecords.InstructionRecord source = foundations
                    .findInstruction(task.sourceInstructionId())
                    .orElse(null);
            return source != null
                    && command.commandId().equals(source.commandId())
                    && task.taskId().equals(source.originTaskId());
        }
        long cut = foundations.highestAuthorityEventSequence(task.taskId());
        return foundations.findTaskInstructions(task.taskId(), cut).stream()
                .map(ChainPersistenceRecords.TaskInstructionBindingRecord
                        ::instructionId)
                .map(foundations::findInstruction)
                .flatMap(java.util.Optional::stream)
                .anyMatch(value -> command.commandId().equals(
                        value.commandId()));
    }

    private static void validateExistingDelivery(
            BeginCommand command,
            ChainPersistenceRecords.ContentRecord content,
            ChainPersistenceRecords.DeliveryRecord existing) {
        String sourceRef = command.source().ref();
        if (!command.sourceCommandId().equals(existing.sourceCommandId())
                || !content.contentId().equals(existing.answerContentId())
                || existing.assistantMessageId() == null
                || (command.source() instanceof RouteSource
                ? !sourceRef.equals(existing.routeDecisionId())
                : existing.routeDecisionId() != null)
                || (command.source() instanceof TaskOutcomeSource
                ? !sourceRef.equals(existing.taskOutcomeId())
                : existing.taskOutcomeId() != null)
                || (command.source() instanceof GapSource
                ? !sourceRef.equals(existing.gapId())
                : existing.gapId() != null)
                || (command.source() instanceof DecisionSource
                ? !sourceRef.equals(existing.decisionId())
                : existing.decisionId() != null)) {
            throw new ChainDeliveryException(
                    ChainDeliveryException.Code.DELIVERY_REPLAY_MISMATCH,
                    "stable Delivery identity changed immutable contents");
        }
    }

    private static boolean exactFinalDelivery(
            AnswerPayload.FinalDelivery answer,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        if (outcome.outcomeType() != ChainTaskOutcomeStatus.COMPLETED) {
            return false;
        }
        List<String> expectedArtifacts = outcome.finalArtifactId() == null
                ? List.of(ChainIdentity.NONE)
                : List.of(ChainIdentity.candidateArtifactRef(
                        outcome.finalArtifactId()),
                outcome.candidateKey());
        String expectedPublish = outcome.publishReceiptId() == null
                ? ChainIdentity.NONE : outcome.publishReceiptId();
        return expectedArtifacts.equals(answer.artifactAndCandidateRefs())
                && outcome.validationId().equals(answer.validationRef())
                && expectedPublish.equals(answer.publishRef());
    }

    private String latestDecisionRef(
            String taskId,
            AuthorityOrder authority) {
        return workflow.findReviewDecisions(taskId).stream()
                .filter(value -> authority.contains(value.eventId()))
                .max(Comparator.comparingLong(
                        value -> authority.sequence(value.eventId())))
                .map(ChainPersistenceRecords.ReviewDecisionRecord
                        ::reviewDecisionId)
                .orElse(ChainIdentity.NONE);
    }

    private static String deliveryEventDigest(
            ChainPersistenceRecords.DeliveryEventRecord event) {
        if (event.eventKind() == ChainDeliveryStatus.PENDING) {
            return ChainDeliveryCanonical.sha256(
                    event.deliveryId() + "\0PENDING");
        }
        String identity = event.deliveryId() + "\0" + event.attemptNo()
                + "\0" + event.eventKind();
        if (event.errorCode() != null) {
            identity += "\0" + event.errorCode();
        }
        return ChainDeliveryCanonical.sha256(identity);
    }

    private static String pendingItemEventDigest(
            ChainPersistenceRecords.PendingItemEventRecord event) {
        return ChainDeliveryCanonical.sha256(
                event.gapId() + "\0" + event.responseRound() + "\0"
                        + event.eventKind() + "\0"
                        + Objects.toString(event.answerInstructionId(), "")
                        + "\0" + Objects.toString(
                        event.validationInvocationId(), ""));
    }

    private static String deliverySourceDigest(
            ChainPersistenceRecords.DeliveryRecord delivery,
            String proposalId) {
        String sourceType;
        String sourceRef;
        if (delivery.routeDecisionId() != null) {
            sourceType = RouteSource.class.getSimpleName();
            sourceRef = delivery.routeDecisionId();
        } else if (delivery.taskOutcomeId() != null) {
            sourceType = TaskOutcomeSource.class.getSimpleName();
            sourceRef = delivery.taskOutcomeId();
        } else if (delivery.gapId() != null) {
            sourceType = GapSource.class.getSimpleName();
            sourceRef = delivery.gapId();
        } else {
            sourceType = DecisionSource.class.getSimpleName();
            sourceRef = delivery.decisionId();
        }
        return ChainDeliveryCanonical.sha256(
                sourceType + "\0" + sourceRef + "\0" + proposalId
                        + "\0" + delivery.answerContentId());
    }

    private static String answerBody(AnswerPayload payload) {
        if (payload instanceof AnswerPayload.DirectAnswer value) {
            return value.inlineAnswerBody();
        }
        if (payload instanceof AnswerPayload.UserQuestion value) {
            return value.inlineAnswerBody();
        }
        if (payload instanceof AnswerPayload.StatusOrFailure value) {
            return value.inlineAnswerBody();
        }
        if (payload instanceof AnswerPayload.FinalDelivery value) {
            return value.inlineAnswerBody();
        }
        throw proposalFailure("Answer kind has no deliverable body authority");
    }

    private static <T extends Record &
            ChainPersistenceRecords.TaskAuthorityFact>
            void requireAppend(
                    ChainPersistenceRecords.AuthorityEventRequest requestedEvent,
                    T requestedFact,
                    ChainPersistenceRecords.AuthoritativeAppendResult<T> appended) {
        if (!sameRecordIgnoringAuditTime(requestedFact, appended.fact())
                || !requestedEvent.eventId().equals(appended.event().eventId())
                || !requestedEvent.taskId().equals(appended.event().taskId())
                || !requestedEvent.eventType().equals(
                appended.event().eventType())
                || !Objects.equals(requestedEvent.transitionId(),
                appended.event().transitionId())
                || !requestedEvent.sourceIdentitySha256().equals(
                appended.event().sourceIdentitySha256())
                || !storedFactTime(appended.fact()).equals(
                appended.event().committedAt())) {
            throw new ChainDeliveryException(
                    ChainDeliveryException.Code.DELIVERY_REPLAY_MISMATCH,
                    "Delivery append/replay changed immutable facts");
        }
    }

    private static boolean sameRecordIgnoringAuditTime(
            Record expected, Record actual) {
        if (!expected.getClass().equals(actual.getClass())) return false;
        try {
            for (var component : expected.getClass().getRecordComponents()) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    continue;
                }
                if (!Objects.equals(component.getAccessor().invoke(expected),
                        component.getAccessor().invoke(actual))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Instant storedFactTime(Record fact) {
        try {
            for (var component : fact.getClass().getRecordComponents()) {
                if (component.getName().equals("createdAt")
                        || component.getName().equals("committedAt")) {
                    return (Instant) component.getAccessor().invoke(fact);
                }
            }
            throw new IllegalStateException("formal fact has no audit time");
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ChainDeliveryException invalidSource(String message) {
        return new ChainDeliveryException(
                ChainDeliveryException.Code.SOURCE_INVALID, message);
    }

    private static ChainDeliveryException proposalFailure(String message) {
        return new ChainDeliveryException(
                ChainDeliveryException.Code.PROPOSAL_INVALID, message);
    }

    private static ChainDeliveryException contentFailure(String message) {
        return new ChainDeliveryException(
                ChainDeliveryException.Code.CONTENT_AUTHORITY_INVALID, message);
    }

    private static ChainDeliveryException eventFailure(String message) {
        return new ChainDeliveryException(
                ChainDeliveryException.Code.DELIVERY_EVENT_PREFIX_INVALID,
                message);
    }

    private static ChainDeliveryException messageFailure(String message) {
        return new ChainDeliveryException(
                ChainDeliveryException.Code.MESSAGE_ATTEMPT_INVALID, message);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record BeginCommand(
            String taskId,
            String sourceCommandId,
            String proposalId,
            Source source,
            AnswerPayload payload,
            Instant committedAt) {
        public BeginCommand {
            taskId = required(taskId, "taskId");
            sourceCommandId = required(sourceCommandId, "sourceCommandId");
            proposalId = required(proposalId, "proposalId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public sealed interface Source permits
            RouteSource, TaskOutcomeSource, GapSource, DecisionSource {
        String ref();

        default String type() {
            return getClass().getSimpleName();
        }
    }

    public record RouteSource(String ref) implements Source {
        public RouteSource {
            ref = required(ref, "ref");
        }
    }

    public record TaskOutcomeSource(String ref) implements Source {
        public TaskOutcomeSource {
            ref = required(ref, "ref");
        }
    }

    public record GapSource(String ref) implements Source {
        public GapSource {
            ref = required(ref, "ref");
        }
    }

    public record DecisionSource(String ref) implements Source {
        public DecisionSource {
            ref = required(ref, "ref");
        }
    }

    public record Started(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainPersistenceRecords.DeliveryEventRecord pending) {
        public Started {
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(pending, "pending");
        }
    }

    public record Attempted(
            ChainPersistenceRecords.DeliveryRecord delivery,
            ChainPersistenceRecords.DeliveryEventRecord event,
            boolean replayed) {
        public Attempted {
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(event, "event");
        }
    }

    private record AuthorityOrder(
            String taskId,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> events) {
        static AuthorityOrder load(
                ChainFoundationRepository foundations,
                String taskId) {
            long highest = foundations.highestAuthorityEventSequence(taskId);
            List<ChainPersistenceRecords.AuthorityEventRecord> prefix =
                    foundations.findAuthorityEvents(taskId, highest).stream()
                            .sorted(Comparator.comparingLong(
                                    ChainPersistenceRecords
                                            .AuthorityEventRecord
                                            ::eventSequence))
                            .toList();
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId =
                    new HashMap<>();
            Set<Long> sequences = new HashSet<>();
            if (highest != prefix.size()) {
                throw eventFailure("authority event prefix is not contiguous");
            }
            for (int index = 0; index < prefix.size(); index++) {
                ChainPersistenceRecords.AuthorityEventRecord event =
                        prefix.get(index);
                if (!taskId.equals(event.taskId())
                        || event.eventSequence() != index + 1L
                        || byId.put(event.eventId(), event) != null
                        || !sequences.add(event.eventSequence())) {
                    throw eventFailure("authority event prefix is inconsistent");
                }
            }
            return new AuthorityOrder(taskId, Map.copyOf(byId));
        }

        void require(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String eventType) {
            ChainPersistenceRecords.AuthorityEventRecord event =
                    events.get(fact.eventId());
            require(fact, eventType,
                    event == null ? null : event.transitionId(),
                    event == null ? null : event.sourceIdentitySha256());
        }

        void require(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String eventType,
                String transitionId,
                String sourceIdentitySha256) {
            ChainPersistenceRecords.AuthorityEventRecord event =
                    events.get(fact.eventId());
            if (!taskId.equals(fact.taskId())
                    || event == null
                    || !eventType.equals(event.eventType())
                    || !Objects.equals(transitionId, event.transitionId())
                    || !Objects.equals(sourceIdentitySha256,
                    event.sourceIdentitySha256())) {
                throw eventFailure(
                        "formal Delivery source lacks exact authority event");
            }
        }

        boolean contains(String eventId) {
            return events.containsKey(eventId);
        }

        long sequence(String eventId) {
            ChainPersistenceRecords.AuthorityEventRecord event =
                    events.get(eventId);
            if (event == null) {
                throw eventFailure("formal decision lacks authority event");
            }
            return event.eventSequence();
        }
    }
}
