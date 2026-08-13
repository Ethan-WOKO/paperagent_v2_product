package io.paperagent.v2.chain.state;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPendingItemWriter;
import io.paperagent.v2.chain.ChainPermissionDecision;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative PendingItem lifecycle. Model output is accepted only through a
 * formal proposal source; normal successors and permission decisions remain
 * owned by their dedicated authority runtimes.
 */
public final class ChainPendingItemRuntime {
    private final ChainWorkflowRepository workflow;
    private final ChainFoundationRepository foundation;
    private final ChainPendingItemWriter pendingItems;
    private final PendingProposalSource pendingProposalSource;
    private final GapValidationProposalSource validationProposalSource;
    private final NormalSuccessorPort normalSuccessors;
    private final PermissionDecisionSource permissionDecisions;
    private final ProposalOfficialBinder proposalBinder;

    public ChainPendingItemRuntime(
            ChainWorkflowRepository workflow,
            ChainFoundationRepository foundation,
            ChainPendingItemWriter pendingItems,
            PendingProposalSource pendingProposalSource,
            GapValidationProposalSource validationProposalSource,
            NormalSuccessorPort normalSuccessors,
            PermissionDecisionSource permissionDecisions,
            ProposalOfficialBinder proposalBinder) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundation = Objects.requireNonNull(foundation, "foundation");
        this.pendingItems = Objects.requireNonNull(pendingItems, "pendingItems");
        this.pendingProposalSource = Objects.requireNonNull(
                pendingProposalSource, "pendingProposalSource");
        this.validationProposalSource = Objects.requireNonNull(
                validationProposalSource, "validationProposalSource");
        this.normalSuccessors = Objects.requireNonNull(normalSuccessors, "normalSuccessors");
        this.permissionDecisions = Objects.requireNonNull(
                permissionDecisions, "permissionDecisions");
        this.proposalBinder = Objects.requireNonNull(proposalBinder, "proposalBinder");
    }

    public ChainPersistenceRecords.PendingItemRecord open(OpenRequest request) {
        return open(request, null);
    }

    /**
     * Opens the same PendingItem lifecycle from a Reflector proposal that is
     * already formally bound to its ReviewDecision.  The proposal remains
     * singly bound to that decision; this method neither creates a second
     * proposal state nor introduces a second PendingItem state machine.
     */
    public ChainPersistenceRecords.PendingItemRecord openFromReviewDecision(
            OpenRequest request, String reviewDecisionId) {
        requireText(reviewDecisionId, "reviewDecisionId");
        return open(request, reviewDecisionId);
    }

    private ChainPersistenceRecords.PendingItemRecord open(
            OpenRequest request, String reviewDecisionId) {
        Objects.requireNonNull(request, "request");
        PendingProposal source = pendingProposalSource.load(request.proposalId());
        require(source.taskId().equals(request.taskId()), "pending proposal task mismatch");
        String gapIdentity = sha256(source.identityMaterial());
        String gapId = "gap." + sha256(
                request.taskId() + "\0" + request.proposalId() + "\0" + gapIdentity);
        if (reviewDecisionId == null) {
            requireAcceptedOrBound(
                    source.currentState(), request.taskId(), request.proposalId(),
                    "PENDING_ITEM", gapId);
        } else {
            requireAcceptedOrBound(
                    source.currentState(), request.taskId(), request.proposalId(),
                    "REVIEW_DECISION", reviewDecisionId);
        }

        ChainPersistenceRecords.PendingItemRecord requested =
                new ChainPersistenceRecords.PendingItemRecord(
                        gapId, request.taskId(), request.eventId(), request.proposalId(),
                        source.pendingType(), gapIdentity, canonicalArray(source.missingFields()),
                        source.permissionScope(), source.question(), source.expectedFormat(),
                        source.validationRole(), source.resumeRole(),
                        canonicalObject("position", source.resumePosition()),
                        source.versionFenceSha256(), request.createdAt());
        ChainPersistenceRecords.AuthorityEventRequest event = authorityEvent(
                request.eventId(), request.taskId(), "PENDING_ITEM", null,
                gapIdentity, request.createdAt());
        ChainPersistenceRecords.PendingItemRecord stored = pendingItems.appendPendingItem(
                new ChainPersistenceRecords.AuthoritativeFact<>(event, requested)).fact();
        require(stored.equals(requested), "pending-item append changed frozen fields");
        if (reviewDecisionId == null) {
            proposalBinder.bindOfficialResult(
                    request.taskId(), request.proposalId(), "PENDING_ITEM", gapId);
        }
        return stored;
    }

    public Directive recordResponse(ResponseRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.PendingItemRecord item = item(request.taskId(), request.gapId());
        FoldedState folded = fold(item);
        require(folded.status() == ChainPendingItemStatus.PENDING,
                "only a PENDING item may receive a response");
        ChainPersistenceRecords.InstructionRecord answer = foundation
                .findInstruction(request.answerInstructionId())
                .orElseThrow(() -> new IllegalStateException(
                        "formal answer Instruction does not exist"));
        require(answer.originTaskId().equals(item.taskId())
                        && answer.relationKind() == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM
                        && item.gapId().equals(answer.answeredGapId()),
                "answer Instruction is not bound to this PendingItem");
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> taskBindings =
                foundation.findTaskInstructions(item.taskId(), Long.MAX_VALUE);
        require(taskBindings.stream().allMatch(binding ->
                        binding.taskId().equals(item.taskId())),
                "task instruction binding query returned another task");
        ChainPersistenceRecords.TaskInstructionBindingRecord head = taskBindings.stream()
                .max(Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord::taskInstructionSequence))
                .orElseThrow(() -> new IllegalStateException(
                        "answer Instruction lacks a formal task binding"));
        require(head.instructionId().equals(answer.instructionId()),
                "answer Instruction is not the current task binding head");
        int responseRound = folded.responseRound() + 1;
        ChainPersistenceRecords.PendingItemEventRecord event = pendingEvent(
                item, responseRound, ChainPendingItemStatus.RESPONSE_RECEIVED,
                request.eventId(), request.answerInstructionId(), null, null,
                canonicalObject("answerInstructionId", request.answerInstructionId()),
                request.committedAt());
        appendEvent(event, null);
        return nextDirective(request.taskId(), request.gapId());
    }

    public Directive applyValidation(ValidationRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.PendingItemRecord item = item(request.taskId(), request.gapId());
        require(item.pendingType() != ChainPendingItemType.PERMISSION,
                "permission gaps require a formal product PermissionDecision");
        FoldedState folded = fold(item);
        AcceptedGapValidation validation = validationProposalSource.load(
                request.validationProposalId());
        require(validation.proposalId().equals(request.validationProposalId()),
                "validation source returned another proposal");
        validateGapProposal(item, folded, validation);

        if (folded.status() == ChainPendingItemStatus.RESOLVED
                && Objects.equals(folded.validationInvocationId(), validation.invocationId())) {
            String transitionId = gapTransitionId(
                    item, folded.responseRound(), validation.invocationId());
            OfficialSuccessor successor = normalSuccessors
                    .findCommitted(request.taskId(), transitionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "RESOLVED replay lacks its formal normal successor"));
            requireAcceptedOrBound(
                    validation.currentState(), item.taskId(), validation.proposalId(),
                    successor.authorityType(), successor.authorityRef());
            proposalBinder.bindOfficialResult(
                    item.taskId(), validation.proposalId(),
                    successor.authorityType(), successor.authorityRef());
            return nextDirective(request.taskId(), request.gapId());
        }
        if (folded.status() == ChainPendingItemStatus.PENDING
                && Objects.equals(folded.validationInvocationId(), validation.invocationId())
                && validation.validation().outcome() == GapValidation.Outcome.STILL_PENDING) {
            requireAcceptedOrBound(
                    validation.currentState(), item.taskId(), validation.proposalId(),
                    "PENDING_ITEM", item.gapId());
            proposalBinder.bindOfficialResult(
                    item.taskId(), validation.proposalId(), "PENDING_ITEM", item.gapId());
            return nextDirective(request.taskId(), request.gapId());
        }
        require(folded.status() == ChainPendingItemStatus.RESPONSE_RECEIVED,
                "gap validation requires RESPONSE_RECEIVED");

        if (validation.validation().outcome() == GapValidation.Outcome.STILL_PENDING) {
            require(validation.currentState().stateKind() == ChainProposalState.ACCEPTED,
                    "new STILL_PENDING validation requires an ACCEPTED proposal");
            StillPendingDetail remaining = stillPendingDetail(validation);
            ChainPersistenceRecords.PendingItemEventRecord pending = pendingEvent(
                    item, folded.responseRound(), ChainPendingItemStatus.PENDING,
                    request.eventId(), folded.answerInstructionId(), validation.invocationId(),
                    GapValidation.Outcome.STILL_PENDING,
                    remaining.canonical(validation.proposalId()),
                    request.committedAt());
            appendEvent(pending, null);
            proposalBinder.bindOfficialResult(
                    item.taskId(), validation.proposalId(), "PENDING_ITEM", item.gapId());
            return nextDirective(request.taskId(), request.gapId());
        }

        String transitionId = gapTransitionId(item, folded.responseRound(), validation.invocationId());
        Optional<OfficialSuccessor> alreadyCommitted = normalSuccessors.findCommitted(
                request.taskId(), transitionId);
        OfficialSuccessor successor;
        if (validation.currentState().stateKind()
                == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
            successor = alreadyCommitted.orElseThrow(() -> new IllegalStateException(
                    "bound validation proposal lacks its formal normal successor"));
            requireAcceptedOrBound(
                    validation.currentState(), item.taskId(), validation.proposalId(),
                    successor.authorityType(), successor.authorityRef());
        } else {
            require(validation.currentState().stateKind() == ChainProposalState.ACCEPTED,
                    "new RESOLVED validation requires an ACCEPTED proposal");
            successor = alreadyCommitted.orElseGet(() -> normalSuccessors.commit(
                    new NormalSuccessorRequest(
                            request.taskId(), request.gapId(), transitionId,
                            validation.proposalId(), validation.invocationId(),
                            validation.payload())));
        }
        require(successor != null && transitionId.equals(successor.transitionId()),
                "normal successor did not commit the stable gap transition");
        OfficialSuccessor readableSuccessor = normalSuccessors
                .findCommitted(request.taskId(), transitionId)
                .orElseThrow(() -> new IllegalStateException(
                        "normal successor is not formally readable after commit"));
        require(successor.equals(readableSuccessor),
                "normal successor commit/read identities differ");
        proposalBinder.bindOfficialResult(
                item.taskId(), validation.proposalId(),
                successor.authorityType(), successor.authorityRef());

        ChainPersistenceRecords.PendingItemEventRecord resolved = pendingEvent(
                item, folded.responseRound(), ChainPendingItemStatus.RESOLVED,
                request.eventId(), folded.answerInstructionId(), validation.invocationId(),
                GapValidation.Outcome.RESOLVED,
                canonicalObject("successorAuthorityRef", successor.authorityRef()),
                request.committedAt());
        appendEvent(resolved, transitionId);
        return nextDirective(request.taskId(), request.gapId());
    }

    public Directive applyPermissionDecision(PermissionRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.PendingItemRecord item = item(request.taskId(), request.gapId());
        require(item.pendingType() == ChainPendingItemType.PERMISSION,
                "only a permission PendingItem consumes PermissionDecision");
        FoldedState folded = fold(item);
        ChainPersistenceRecords.PermissionDecisionRecord decision = permissionDecisions
                .find(request.taskId(), request.gapId(), request.permissionDecisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "formal PermissionDecision does not exist"));
        require(decision.taskId().equals(item.taskId())
                        && decision.gapId().equals(item.gapId())
                        && decision.permissionScope().equals(item.permissionScope()),
                "PermissionDecision does not match the frozen permission gap");
        ChainPendingItemStatus target = decision.decision() == ChainPermissionDecision.GRANTED
                ? ChainPendingItemStatus.RESOLVED
                : ChainPendingItemStatus.REJECTED;
        if (folded.status() == target) {
            return nextDirective(request.taskId(), request.gapId());
        }
        require(folded.status() == ChainPendingItemStatus.PENDING
                        || folded.status() == ChainPendingItemStatus.RESPONSE_RECEIVED,
                "permission PendingItem is already terminal");
        ChainPersistenceRecords.PendingItemEventRecord event = pendingEvent(
                item, folded.responseRound(), target, request.eventId(),
                folded.answerInstructionId(), null, null,
                canonicalObject("permissionDecisionId", decision.permissionDecisionId()),
                request.committedAt());
        appendEvent(event, null);
        return nextDirective(request.taskId(), request.gapId());
    }

    public Directive terminate(TerminationRequest request) {
        Objects.requireNonNull(request, "request");
        require(request.status() == ChainPendingItemStatus.REJECTED
                        || request.status() == ChainPendingItemStatus.CANCELLED,
                "termination status must be REJECTED or CANCELLED");
        ChainPersistenceRecords.PendingItemRecord item = item(request.taskId(), request.gapId());
        FoldedState folded = fold(item);
        require(folded.status() == ChainPendingItemStatus.PENDING
                        || folded.status() == ChainPendingItemStatus.RESPONSE_RECEIVED,
                "only an open PendingItem may terminate");
        ChainPersistenceRecords.PendingItemEventRecord event = pendingEvent(
                item, folded.responseRound(), request.status(), request.eventId(),
                folded.answerInstructionId(), null, null,
                canonicalObject("reason", request.reason()), request.committedAt());
        appendEvent(event, null);
        return nextDirective(request.taskId(), request.gapId());
    }

    /** Derives the only next role from persisted PendingItem/transition/permission facts. */
    public Directive nextDirective(String taskId, String gapId) {
        ChainPersistenceRecords.PendingItemRecord item = item(taskId, gapId);
        FoldedState folded = fold(item);
        return switch (folded.status()) {
            case PENDING -> new Directive.QuestionRequired(
                    gapId, ChainRole.ANSWER,
                    item.pendingType() == ChainPendingItemType.PERMISSION
                            ? ChainWorkState.WAITING_PERMISSION
                            : ChainWorkState.WAITING_USER,
                    folded.missingFields(), folded.question(), folded.expectedFormat());
            case RESPONSE_RECEIVED -> new Directive.ValidationRequired(
                    gapId, item.validationRole(), ChainWorkState.VALIDATING_PENDING_ITEM,
                    folded.answerInstructionId(), folded.responseRound());
            case RESOLVED -> resolvedDirective(item, folded);
            case REJECTED, CANCELLED -> new Directive.Terminal(gapId, folded.status());
        };
    }

    private Directive resolvedDirective(
            ChainPersistenceRecords.PendingItemRecord item, FoldedState folded) {
        if (item.pendingType() == ChainPendingItemType.PERMISSION) {
            ChainPersistenceRecords.PermissionDecisionRecord decision = permissionDecisions
                    .findLatest(item.taskId(), item.gapId())
                    .filter(value -> value.decision() == ChainPermissionDecision.GRANTED)
                    .orElseThrow(() -> new IllegalStateException(
                            "RESOLVED permission gap lacks a formal GRANTED decision"));
            return new Directive.PermissionReintakeRequired(
                    item.gapId(), ChainRole.PLANNER, ChainWorkState.PLANNING,
                    decision.permissionDecisionId());
        }
        require(folded.validationInvocationId() != null,
                "RESOLVED gap lacks its validation invocation");
        String transitionId = gapTransitionId(
                item, folded.responseRound(), folded.validationInvocationId());
        normalSuccessors.findCommitted(item.taskId(), transitionId)
                .orElseThrow(() -> new IllegalStateException(
                        "RESOLVED gap lacks its formal normal successor"));
        return new Directive.ResumeRequired(
                item.gapId(), item.resumeRole(), item.resumePosition(), transitionId);
    }

    private void validateGapProposal(
            ChainPersistenceRecords.PendingItemRecord item,
            FoldedState folded,
            AcceptedGapValidation validation) {
        validateGapProposalAuthority(
                item, folded.status(), folded.responseRound(),
                folded.answerInstructionId(), validation);
    }

    /**
     * Shared formal validator for a typed pending-item validation proposal.
     * Recovery adapters call this same source instead of duplicating a
     * weaker proposal/state/invocation check.
     */
    public static void validateGapProposalAuthority(
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPendingItemStatus currentStatus,
            int responseRound,
            String answerInstructionId,
            AcceptedGapValidation validation) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(currentStatus, "currentStatus");
        Objects.requireNonNull(validation, "validation");
        require(validation.taskId().equals(item.taskId()), "validation task mismatch");
        require(validation.currentState().taskId().equals(item.taskId())
                        && validation.currentState().proposalId()
                        .equals(validation.proposalId()),
                "validation proposal state identity mismatch");
        require(validation.proposal().taskId().equals(item.taskId())
                        && validation.invocation().taskId().equals(item.taskId()),
                "validation proposal/invocation task mismatch");
        require(validation.invocation().role() == item.validationRole(),
                "validation role differs from the frozen PendingItem role");
        require(validation.proposal().role() == validation.invocation().role()
                        && validation.payload().role() == validation.invocation().role(),
                "validation proposal/payload/invocation role mismatch");
        require(validation.invocation().workState() == ChainWorkState.VALIDATING_PENDING_ITEM,
                "validation invocation must use VALIDATING_PENDING_ITEM");
        require(validation.proposal().invocationId().equals(validation.invocationId()),
                "validation proposal/invocation mismatch");
        require(validation.proposal().proposalKind() == validation.payload().kind(),
                "typed validation payload does not match the formal proposal kind");
        require(validation.validation().gapId().equals(item.gapId()),
                "gapValidation targets another PendingItem");
        if (validation.validation().outcome() == GapValidation.Outcome.STILL_PENDING) {
            boolean legal = validation.payload() instanceof PlannerPayload.NeedUserInput
                    || validation.payload() instanceof ExecutorPayload.StepBlocked blocked
                    && !blocked.remainingMissingFields().isEmpty();
            require(legal, "STILL_PENDING cannot use this proposal kind");
        }
        if (currentStatus == ChainPendingItemStatus.RESPONSE_RECEIVED) {
            require(responseRound > 0,
                    "RESPONSE_RECEIVED lacks its positive response round");
            require(answerInstructionId != null,
                    "RESPONSE_RECEIVED lacks its bound answer instruction");
        }
    }

    private void requireAcceptedOrBound(
            ChainPersistenceRecords.ProposalStateEventRecord state,
            String taskId,
            String proposalId,
            String expectedAuthorityType,
            String expectedAuthorityRef) {
        require(state.taskId().equals(taskId) && state.proposalId().equals(proposalId),
                "proposal state identity mismatch");
        if (state.stateKind() == ChainProposalState.ACCEPTED) {
            return;
        }
        require(state.stateKind() == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT,
                "proposal is not formally accepted");
        if (expectedAuthorityType != null) {
            require(expectedAuthorityType.equals(state.officialAuthorityType())
                            && expectedAuthorityRef.equals(state.officialAuthorityRef()),
                    "proposal was replaced by another official result");
        }
    }

    private ChainPersistenceRecords.PendingItemRecord item(String taskId, String gapId) {
        requireText(taskId, "taskId");
        requireText(gapId, "gapId");
        List<ChainPersistenceRecords.PendingItemRecord> matches = workflow.findPendingItems(taskId)
                .stream().filter(value -> value.gapId().equals(gapId)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("PendingItem must resolve to exactly one formal record");
        }
        return matches.get(0);
    }

    private FoldedState fold(ChainPersistenceRecords.PendingItemRecord item) {
        ChainPendingItemStatus status = ChainPendingItemStatus.PENDING;
        int responseRound = 0;
        String answerInstructionId = null;
        String validationInvocationId = null;
        List<String> missingFields = parseCanonicalArray(item.missingFields());
        String question = item.question();
        String expectedFormat = item.expectedFormat();
        for (ChainPersistenceRecords.PendingItemEventRecord event
                : workflow.findPendingItemEvents(item.gapId())) {
            require(event.taskId().equals(item.taskId()) && event.gapId().equals(item.gapId()),
                    "pending event identity mismatch");
            switch (event.eventKind()) {
                case RESPONSE_RECEIVED -> {
                    require(status == ChainPendingItemStatus.PENDING
                                    && event.responseRound() == responseRound + 1
                                    && hasText(event.answerInstructionId())
                                    && event.validationInvocationId() == null
                                    && event.gapValidationOutcome() == null,
                            "invalid RESPONSE_RECEIVED transition");
                    responseRound = event.responseRound();
                    answerInstructionId = event.answerInstructionId();
                    validationInvocationId = null;
                }
                case PENDING -> {
                    require(status == ChainPendingItemStatus.RESPONSE_RECEIVED
                                    && event.responseRound() == responseRound
                                    && hasText(event.validationInvocationId())
                                    && event.gapValidationOutcome()
                                    == GapValidation.Outcome.STILL_PENDING,
                            "PENDING may only reopen the same validated response round");
                    validationInvocationId = event.validationInvocationId();
                    StillPendingDetail remaining = StillPendingDetail.parse(event.detail());
                    missingFields = remaining.missingFields();
                    question = remaining.question();
                    expectedFormat = remaining.expectedFormat();
                }
                case RESOLVED -> {
                    boolean permissionResolution = (status == ChainPendingItemStatus.PENDING
                            || status == ChainPendingItemStatus.RESPONSE_RECEIVED)
                            && item.pendingType() == ChainPendingItemType.PERMISSION
                            && event.responseRound() == responseRound
                            && event.validationInvocationId() == null
                            && event.gapValidationOutcome() == null;
                    boolean modelResolution = status == ChainPendingItemStatus.RESPONSE_RECEIVED
                            && event.responseRound() == responseRound
                            && hasText(event.validationInvocationId())
                            && event.gapValidationOutcome() == GapValidation.Outcome.RESOLVED;
                    require(permissionResolution || modelResolution,
                            "RESOLVED lacks its formal permission or model validation predecessor");
                    validationInvocationId = event.validationInvocationId();
                }
                case REJECTED, CANCELLED -> require(
                        (status == ChainPendingItemStatus.PENDING
                                || status == ChainPendingItemStatus.RESPONSE_RECEIVED)
                                && event.responseRound() == responseRound
                                && event.validationInvocationId() == null
                                && event.gapValidationOutcome() == null,
                        "invalid PendingItem terminal transition");
            }
            status = event.eventKind();
        }
        return new FoldedState(
                status, responseRound, answerInstructionId, validationInvocationId,
                missingFields, question, expectedFormat);
    }

    private void appendEvent(
            ChainPersistenceRecords.PendingItemEventRecord requested,
            String transitionId) {
        String sourceDigest = sha256(
                requested.gapId() + "\0" + requested.responseRound() + "\0"
                        + requested.eventKind() + "\0" + nullSafe(requested.answerInstructionId())
                        + "\0" + nullSafe(requested.validationInvocationId()));
        ChainPersistenceRecords.AuthorityEventRequest event = authorityEvent(
                requested.eventId(), requested.taskId(),
                "PENDING_ITEM_" + requested.eventKind().name(), transitionId,
                sourceDigest, requested.committedAt());
        ChainPersistenceRecords.PendingItemEventRecord stored = pendingItems
                .appendPendingItemEvent(new ChainPersistenceRecords.AuthoritativeFact<>(event, requested))
                .fact();
        require(stored.equals(requested), "pending event append changed frozen fields");
    }

    private static ChainPersistenceRecords.PendingItemEventRecord pendingEvent(
            ChainPersistenceRecords.PendingItemRecord item,
            int responseRound,
            ChainPendingItemStatus status,
            String eventId,
            String answerInstructionId,
            String validationInvocationId,
            GapValidation.Outcome validationOutcome,
            ChainPersistenceRecords.CanonicalJson detail,
            Instant committedAt) {
        return new ChainPersistenceRecords.PendingItemEventRecord(
                item.gapId(), responseRound, status, item.taskId(), eventId,
                answerInstructionId, validationInvocationId, validationOutcome,
                detail, committedAt);
    }

    /** Stable GAP_RESOLUTION identity shared with product recovery wiring. */
    public static String gapTransitionId(
            ChainPersistenceRecords.PendingItemRecord item,
            int responseRound,
            String validationInvocationId) {
        String targetDigest = sha256(
                item.taskId() + "\0" + item.gapId() + "\0" + responseRound
                        + "\0" + validationInvocationId);
        return new ChainIdentity.Transition(
                ChainTransitionType.GAP_RESOLUTION,
                item.taskId(), validationInvocationId, targetDigest).transitionId();
    }

    private static ChainPersistenceRecords.AuthorityEventRequest authorityEvent(
            String eventId,
            String taskId,
            String eventType,
            String transitionId,
            String sourceIdentitySha256,
            Instant committedAt) {
        return new ChainPersistenceRecords.AuthorityEventRequest(
                eventId, taskId, eventType, transitionId,
                sourceIdentitySha256, committedAt);
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(List<String> values) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, "values"));
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < copy.size(); index++) {
            if (index > 0) json.append(',');
            quote(json, copy.get(index));
        }
        json.append(']');
        return canonical(json.toString());
    }

    private static List<String> parseCanonicalArray(
            ChainPersistenceRecords.CanonicalJson value) {
        require(value.sha256().equals(sha256(value.json())),
                "stored PendingItem detail digest mismatch");
        JsonStringReader reader = new JsonStringReader(value.json());
        List<String> values = reader.stringArray();
        reader.requireEnd();
        require(canonicalArray(values).equals(value),
                "stored PendingItem array is not canonical");
        return values;
    }

    private static StillPendingDetail stillPendingDetail(
            AcceptedGapValidation validation) {
        if (validation.payload() instanceof PlannerPayload.NeedUserInput value) {
            return new StillPendingDetail(
                    value.missingFields(), value.exactQuestion(), value.expectedFormat());
        }
        if (validation.payload() instanceof ExecutorPayload.StepBlocked value) {
            return new StillPendingDetail(
                    value.remainingMissingFields(), value.exactQuestion(), value.expectedFormat());
        }
        throw new IllegalStateException("STILL_PENDING payload lacks a new exact question");
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalObject(
            String key, String value) {
        StringBuilder json = new StringBuilder("{");
        quote(json, key);
        json.append(':');
        quote(json, value);
        json.append('}');
        return canonical(json.toString());
    }

    private record StillPendingDetail(
            List<String> missingFields,
            String question,
            String expectedFormat) {
        private StillPendingDetail {
            missingFields = List.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
            if (missingFields.isEmpty()) {
                throw new IllegalArgumentException("remaining missing fields must not be empty");
            }
            requireText(question, "question");
            requireText(expectedFormat, "expectedFormat");
        }

        private ChainPersistenceRecords.CanonicalJson canonical(String validationProposalId) {
            StringBuilder json = new StringBuilder("{\"expectedFormat\":");
            quote(json, expectedFormat);
            json.append(",\"missingFields\":[");
            for (int index = 0; index < missingFields.size(); index++) {
                if (index > 0) json.append(',');
                quote(json, missingFields.get(index));
            }
            json.append("],\"question\":");
            quote(json, question);
            json.append(",\"validationProposalId\":");
            quote(json, validationProposalId);
            json.append('}');
            return ChainPendingItemRuntime.canonical(json.toString());
        }

        private static StillPendingDetail parse(
                ChainPersistenceRecords.CanonicalJson value) {
            require(value.sha256().equals(sha256(value.json())),
                    "stored STILL_PENDING detail digest mismatch");
            JsonStringReader reader = new JsonStringReader(value.json());
            reader.expect("{\"expectedFormat\":");
            String expectedFormat = reader.string();
            reader.expect(",\"missingFields\":");
            List<String> missingFields = reader.stringArray();
            reader.expect(",\"question\":");
            String question = reader.string();
            reader.expect(",\"validationProposalId\":");
            String validationProposalId = reader.string();
            requireText(validationProposalId, "validationProposalId");
            reader.expect("}");
            reader.requireEnd();
            StillPendingDetail detail = new StillPendingDetail(
                    missingFields, question, expectedFormat);
            require(detail.canonical(validationProposalId).equals(value),
                    "stored STILL_PENDING detail is not canonical");
            return detail;
        }
    }

    private static final class JsonStringReader {
        private final String value;
        private int offset;

        private JsonStringReader(String value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        private void expect(String expected) {
            if (!value.startsWith(expected, offset)) {
                throw new IllegalStateException("invalid canonical PendingItem detail");
            }
            offset += expected.length();
        }

        private List<String> stringArray() {
            expect("[");
            List<String> values = new java.util.ArrayList<>();
            if (take(']')) {
                return List.of();
            }
            while (true) {
                values.add(string());
                if (take(']')) {
                    return List.copyOf(values);
                }
                expect(",");
            }
        }

        private String string() {
            if (!take('"')) {
                throw new IllegalStateException("canonical detail string expected");
            }
            StringBuilder result = new StringBuilder();
            while (offset < value.length()) {
                char character = value.charAt(offset++);
                if (character == '"') {
                    return result.toString();
                }
                if (character != '\\') {
                    if (character < 0x20) {
                        throw new IllegalStateException("control character in canonical detail");
                    }
                    result.append(character);
                    continue;
                }
                if (offset >= value.length()) {
                    throw new IllegalStateException("unterminated canonical detail escape");
                }
                char escaped = value.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw new IllegalStateException("invalid canonical detail escape");
                }
            }
            throw new IllegalStateException("unterminated canonical detail string");
        }

        private char unicode() {
            if (offset + 4 > value.length()) {
                throw new IllegalStateException("incomplete canonical unicode escape");
            }
            try {
                char result = (char) Integer.parseInt(
                        value.substring(offset, offset + 4), 16);
                offset += 4;
                return result;
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException("invalid canonical unicode escape", invalid);
            }
        }

        private boolean take(char expected) {
            if (offset < value.length() && value.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void requireEnd() {
            require(offset == value.length(), "trailing canonical PendingItem detail");
        }
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(json), json);
    }

    private static void quote(StringBuilder json, String value) {
        requireText(value, "canonical value");
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) json.append(String.format("\\u%04x", (int) character));
                    else json.append(character);
                }
            }
        }
        json.append('"');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String requireText(String value, String name) {
        if (!hasText(value)) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record FoldedState(
            ChainPendingItemStatus status,
            int responseRound,
            String answerInstructionId,
            String validationInvocationId,
            List<String> missingFields,
            String question,
            String expectedFormat) {
    }

    public record OpenRequest(
            String taskId, String proposalId, String eventId, Instant createdAt) {
        public OpenRequest {
            requireText(taskId, "taskId");
            requireText(proposalId, "proposalId");
            requireText(eventId, "eventId");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record ResponseRequest(
            String taskId, String gapId, String eventId,
            String answerInstructionId, Instant committedAt) {
        public ResponseRequest {
            requireText(taskId, "taskId");
            requireText(gapId, "gapId");
            requireText(eventId, "eventId");
            requireText(answerInstructionId, "answerInstructionId");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record ValidationRequest(
            String taskId, String gapId, String eventId,
            String validationProposalId, Instant committedAt) {
        public ValidationRequest {
            requireText(taskId, "taskId");
            requireText(gapId, "gapId");
            requireText(eventId, "eventId");
            requireText(validationProposalId, "validationProposalId");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record PermissionRequest(
            String taskId, String gapId, String eventId,
            String permissionDecisionId, Instant committedAt) {
        public PermissionRequest {
            requireText(taskId, "taskId");
            requireText(gapId, "gapId");
            requireText(eventId, "eventId");
            requireText(permissionDecisionId, "permissionDecisionId");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public record TerminationRequest(
            String taskId, String gapId, String eventId,
            ChainPendingItemStatus status, String reason, Instant committedAt) {
        public TerminationRequest {
            requireText(taskId, "taskId");
            requireText(gapId, "gapId");
            requireText(eventId, "eventId");
            Objects.requireNonNull(status, "status");
            requireText(reason, "reason");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    public interface PendingProposalSource {
        PendingProposal load(String proposalId);
    }

    public record PendingProposal(
            String taskId,
            String proposalId,
            ChainProposalKind proposalKind,
            ChainPersistenceRecords.ProposalStateEventRecord currentState,
            ChainPendingItemType pendingType,
            List<String> missingFields,
            String permissionScope,
            String question,
            String expectedFormat,
            ChainRole validationRole,
            ChainRole resumeRole,
            String resumePosition,
            String versionFenceSha256) {
        public PendingProposal {
            requireText(taskId, "taskId");
            requireText(proposalId, "proposalId");
            Objects.requireNonNull(proposalKind, "proposalKind");
            Objects.requireNonNull(currentState, "currentState");
            Objects.requireNonNull(pendingType, "pendingType");
            missingFields = List.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
            requireText(question, "question");
            requireText(expectedFormat, "expectedFormat");
            Objects.requireNonNull(validationRole, "validationRole");
            Objects.requireNonNull(resumeRole, "resumeRole");
            requireText(resumePosition, "resumePosition");
            requireSha256(versionFenceSha256, "versionFenceSha256");
            boolean allowedKind = proposalKind == ChainProposalKind.PLANNER_NEED_USER_INPUT
                    || proposalKind == ChainProposalKind.PLANNER_NEED_PERMISSION
                    || proposalKind == ChainProposalKind.PLANNER_PLANNING_BLOCKED
                    || proposalKind == ChainProposalKind.REFLECTOR_NEED_USER_INPUT
                    || proposalKind == ChainProposalKind.REFLECTOR_NEED_PERMISSION;
            if (!allowedKind) throw new IllegalArgumentException(
                    "proposal kind cannot create a PendingItem");
            boolean permissionKind = proposalKind == ChainProposalKind.PLANNER_NEED_PERMISSION
                    || proposalKind == ChainProposalKind.REFLECTOR_NEED_PERMISSION;
            if (permissionKind != (pendingType == ChainPendingItemType.PERMISSION)
                    || permissionKind != (permissionScope != null)) {
                throw new IllegalArgumentException("permission proposal/type/scope mismatch");
            }
            if (!permissionKind && missingFields.isEmpty()) {
                throw new IllegalArgumentException("user PendingItem requires missing fields");
            }
            if (validationRole != ChainRole.PLANNER && validationRole != ChainRole.EXECUTOR) {
                throw new IllegalArgumentException("PendingItem validation role must be Planner or Executor");
            }
        }

        String identityMaterial() {
            return taskId + "\0" + proposalId + "\0" + proposalKind + "\0" + pendingType
                    + "\0" + String.join("\0", missingFields) + "\0" + nullSafe(permissionScope)
                    + "\0" + question + "\0" + expectedFormat + "\0" + validationRole
                    + "\0" + resumeRole + "\0" + resumePosition + "\0" + versionFenceSha256;
        }
    }

    public interface GapValidationProposalSource {
        AcceptedGapValidation load(String proposalId);
    }

    public record AcceptedGapValidation(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ProposalStateEventRecord currentState,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainProposalPayload payload) {
        public AcceptedGapValidation {
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(currentState, "currentState");
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(payload, "payload");
            if (payload.gapValidation() == null) {
                throw new IllegalArgumentException("validation payload lacks gapValidation");
            }
            if (!proposal.proposalId().equals(currentState.proposalId())
                    || !proposal.taskId().equals(currentState.taskId())
                    || !proposal.taskId().equals(invocation.taskId())
                    || !proposal.invocationId().equals(invocation.invocationId())
                    || proposal.role() != invocation.role()
                    || proposal.role() != payload.role()
                    || proposal.proposalKind() != payload.kind()) {
                throw new IllegalArgumentException(
                        "proposal/state/invocation/payload identities must match");
            }
        }

        public String taskId() { return proposal.taskId(); }
        public String proposalId() { return proposal.proposalId(); }
        public String invocationId() { return invocation.invocationId(); }
        public GapValidation validation() { return payload.gapValidation(); }
    }

    public interface NormalSuccessorPort {
        OfficialSuccessor commit(NormalSuccessorRequest request);

        Optional<OfficialSuccessor> findCommitted(String taskId, String transitionId);
    }

    public record NormalSuccessorRequest(
            String taskId,
            String gapId,
            String transitionId,
            String validationProposalId,
            String validationInvocationId,
            ChainProposalPayload payload) {
        public NormalSuccessorRequest {
            requireText(taskId, "taskId");
            requireText(gapId, "gapId");
            requireText(transitionId, "transitionId");
            requireText(validationProposalId, "validationProposalId");
            requireText(validationInvocationId, "validationInvocationId");
            Objects.requireNonNull(payload, "payload");
        }
    }

    public record OfficialSuccessor(
            String transitionId, String authorityType, String authorityRef) {
        public OfficialSuccessor {
            requireText(transitionId, "transitionId");
            requireText(authorityType, "authorityType");
            requireText(authorityRef, "authorityRef");
        }
    }

    public interface PermissionDecisionSource {
        Optional<ChainPersistenceRecords.PermissionDecisionRecord> find(
                String taskId, String gapId, String permissionDecisionId);

        Optional<ChainPersistenceRecords.PermissionDecisionRecord> findLatest(
                String taskId, String gapId);
    }

    public interface ProposalOfficialBinder {
        void bindOfficialResult(
                String taskId, String proposalId, String authorityType, String authorityRef);
    }

    public sealed interface Directive permits
            Directive.QuestionRequired,
            Directive.ValidationRequired,
            Directive.ResumeRequired,
            Directive.PermissionReintakeRequired,
            Directive.Terminal {

        record QuestionRequired(
                String gapId, ChainRole role, ChainWorkState workState,
                List<String> missingFields,
                String question, String expectedFormat) implements Directive {
        }

        record ValidationRequired(
                String gapId, ChainRole role, ChainWorkState workState,
                String answerInstructionId, int responseRound) implements Directive {
        }

        record ResumeRequired(
                String gapId, ChainRole role,
                ChainPersistenceRecords.CanonicalJson resumePosition,
                String transitionId) implements Directive {
        }

        record PermissionReintakeRequired(
                String gapId, ChainRole role, ChainWorkState workState,
                String permissionDecisionId) implements Directive {
        }

        record Terminal(String gapId, ChainPendingItemStatus status) implements Directive {
        }
    }

    private static void requireSha256(String value, String name) {
        requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }
}
