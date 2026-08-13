package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.context.ProductChainContextIdentity;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Mechanical projection from the typed state bound to one frozen snapshot. */
public final class ProductChainNextRoleSelector
        implements ChainRecoveryRuntime.NextRoleSelector {
    /** Returns the exact instruction used by role selection for this cut. */
    public static io.paperagent.v2.chain.ChainPersistenceRecords
            .InstructionRecord currentModelInstruction(
            ChainRecoveryRuntime.RecoverySnapshot snapshot) {
        ProductChainRecoverySource.RoleProjection projection = projection(
                Objects.requireNonNull(snapshot, "snapshot"));
        var binding = currentInstructionBinding(projection,
                deliveryProjection(projection).terminal());
        if (binding == null) {
            throw new IllegalStateException(
                    "frozen model selection has no current instruction");
        }
        var instruction = projection.instructionValues().get(
                binding.value().instructionId());
        if (instruction == null) {
            throw new IllegalStateException(
                    "frozen model selection instruction is missing");
        }
        return instruction;
    }

    @Override
    public ChainRecoveryRuntime.NextDirective select(
            ChainRecoveryRuntime.RecoverySnapshot snapshot) {
        Selection selection = decide(snapshot);
        if (selection instanceof Model model) {
            return model.directive();
        }
        throw new NonModelSelection(snapshot, selection);
    }

    public Selection decide(ChainRecoveryRuntime.RecoverySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ProductChainRecoverySource.RoleProjection projection = projection(
                snapshot);

        DeliveryProjection deliveries = deliveryProjection(projection);
        if (deliveries.nextRecovery() != null) {
            return deliveries.nextRecovery();
        }

        var contextFailure = unresolvedContextFailure(projection);
        if (contextFailure != null) {
            if (contextFailure.contextRevision().role()
                    == ChainRole.EXECUTOR) {
                return model(ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW,
                        contextFailure.sourceAuthorityType(),
                        contextFailure.sourceAuthorityRef());
            }
            return new MechanicalContextFailure(
                    contextFailure.sourceAuthorityRef(),
                    contextFailure.contextRevision().role());
        }

        var modelStepBlock = unresolvedModelFailureStepBlock(projection);
        var actionStepBlock = unresolvedActionReceiptStepBlock(projection);
        if (modelStepBlock != null && actionStepBlock != null) {
            throw new IllegalStateException(
                    "CHAIN_MULTIPLE_UNRESOLVED_STEP_BLOCKS");
        }
        if (actionStepBlock != null) {
            return model(ChainRole.REFLECTOR,
                    ChainWorkState.AWAITING_REVIEW,
                    "ACTION_RECEIPT_STEP_BLOCK",
                    actionStepBlock.value().stepBlockId());
        }

        if (modelStepBlock != null) {
            return model(ChainRole.REFLECTOR,
                    ChainWorkState.AWAITING_REVIEW,
                    "MODEL_FAILURE_STEP_BLOCK",
                    modelStepBlock.value().stepBlockId());
        }

        ProductChainRecoverySource.ModelFailureProjection modelFailure =
                unresolvedModelFailure(projection);
        if (modelFailure != null) {
            FailureSource source = failureSource(projection, modelFailure);
            return new MechanicalModelFailure(
                    modelFailure.invocation().invocationId(),
                    modelFailure.invocation().role(),
                    source.type(), source.ref());
        }

        if (projection.outcome().isPresent()) {
            var outcome = projection.outcome().orElseThrow().value();
            var delivery = taskOutcomeDelivery(projection, outcome);
            if (delivery != null) {
                return new ControlWait(WaitKind.DELIVERY_TERMINAL,
                        "DELIVERY", delivery.value().deliveryId());
            }
            ProductChainRecoverySource.ProposalProjection acceptedAnswer =
                    taskOutcomeAnswerProposal(projection, outcome);
            if (acceptedAnswer != null) {
                return new MechanicalProposal(
                        acceptedAnswer.proposal().proposalId(),
                        acceptedAnswer.proposal().role(),
                        acceptedAnswer.proposal().proposalKind(),
                        acceptedAnswer.states().get(0).eventId());
            }
            if (outcome.outcomeType() == ChainTaskOutcomeStatus.COMPLETED) {
                return model(ChainRole.ANSWER, ChainWorkState.DELIVERING,
                        "TASK_OUTCOME", outcome.outcomeId());
            }
            return model(ChainRole.ANSWER, ChainWorkState.TERMINAL,
                    "TASK_OUTCOME", outcome.outcomeId());
        }

        var currentBinding = currentInstructionBinding(
                projection, deliveries.terminal());
        var currentInstruction = currentBinding == null ? null
                : projection.instructionValues().get(
                        currentBinding.value().instructionId());
        if (currentInstruction != null
                && currentInstruction.relationKind()
                == ChainInstructionRelation.CANCEL) {
            return new ControlWait(WaitKind.TASK_OUTCOME_REQUIRED,
                    "INSTRUCTION", currentInstruction.instructionId());
        }
        if (currentInstruction != null
                && currentInstruction.relationKind()
                != ChainInstructionRelation.INITIAL
                && currentInstruction.relationKind()
                != ChainInstructionRelation.ANSWER_TO_PENDING_ITEM
                && !hasInstructionSuccessor(
                projection, currentInstruction.instructionId(),
                currentBinding.authoritySequence())) {
            return model(ChainRole.PLANNER,
                    ChainWorkState.CLASSIFYING_INSTRUCTION,
                    "INSTRUCTION", currentInstruction.instructionId());
        }

        List<ProductChainRecoverySource.PendingProjection> blocking =
                projection.pending().stream().filter(value ->
                        value.status() == ChainPendingItemStatus.PENDING
                                || value.status()
                                == ChainPendingItemStatus.RESPONSE_RECEIVED)
                        .toList();
        if (blocking.size() > 1) {
            return new ControlWait(WaitKind.AMBIGUOUS_PENDING_ITEM,
                    "TASK", projection.taskId());
        }
        if (blocking.size() == 1) {
            var pending = blocking.get(0);
            if (pending.status()
                    == ChainPendingItemStatus.RESPONSE_RECEIVED) {
                if (pending.item().pendingType()
                        == ChainPendingItemType.PERMISSION) {
                    var decision = permissionDecision(projection, pending);
                    if (decision == null) {
                        return new ControlWait(
                                WaitKind.PERMISSION_DECISION_REQUIRED,
                                "PENDING_ITEM", pending.item().gapId());
                    }
                    return new MechanicalPermission(
                            decision.value().permissionDecisionId(),
                            decision.authoritySequence());
                }
                var validation = pendingValidationProposal(
                        projection, pending);
                if (validation != null) {
                    return new MechanicalProposal(
                            validation.proposal().proposalId(),
                            validation.proposal().role(),
                            validation.proposal().proposalKind(),
                            validation.states().get(0).eventId());
                }
                return model(pending.item().validationRole(),
                        ChainWorkState.VALIDATING_PENDING_ITEM,
                        "PENDING_ITEM", pending.item().gapId());
            }
            ChainWorkState state = pending.item().pendingType()
                    == ChainPendingItemType.PERMISSION
                    ? ChainWorkState.WAITING_PERMISSION
                    : ChainWorkState.WAITING_USER;
            var delivered = sourceDelivery(
                    deliveries.terminal(), SourceKind.GAP,
                    pending.item().gapId());
            if (delivered != null) {
                return new ControlWait(WaitKind.DELIVERY_TERMINAL,
                        "DELIVERY", delivered.value().deliveryId());
            }
            ProductChainRecoverySource.ProposalProjection pendingAnswer =
                    pendingItemAnswerProposal(projection, pending,
                            currentInstruction == null ? null
                                    : currentInstruction.instructionId(),
                            state);
            if (pendingAnswer != null) {
                return new MechanicalProposal(
                        pendingAnswer.proposal().proposalId(),
                        pendingAnswer.proposal().role(),
                        pendingAnswer.proposal().proposalKind(),
                        pendingAnswer.states().get(0).eventId());
            }
            return model(ChainRole.ANSWER, state,
                    "PENDING_ITEM", pending.item().gapId());
        }

        ProductChainRecoverySource.ProposalProjection proposal =
                projection.proposals().isEmpty() ? null
                        : projection.proposals().get(
                        projection.proposals().size() - 1);
        if (proposal != null
                && proposal.latest().stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
            if (proposal.latest().stateKind() == ChainProposalState.ACCEPTED) {
                if (proposal.proposal().proposalKind()
                        == ChainProposalKind.EXECUTOR_STEP_BLOCKED) {
                    return model(ChainRole.REFLECTOR,
                            ChainWorkState.AWAITING_REVIEW,
                            "PROPOSAL_STATE",
                            proposal.latest().eventId());
                }
                return new MechanicalProposal(
                        proposal.proposal().proposalId(),
                        proposal.proposal().role(),
                        proposal.proposal().proposalKind(),
                        proposal.latest().eventId());
            }
            if (proposal.latest().stateKind() == ChainProposalState.REJECTED
                    || proposal.latest().stateKind()
                    == ChainProposalState.STALE) {
                return model(proposal.invocation().role(),
                        proposal.invocation().workState(),
                        "PROPOSAL_STATE", proposal.latest().eventId());
            }
        }

        var unreviewed = projection.candidates().stream().filter(candidate ->
                projection.reviews().stream().noneMatch(review ->
                        review.authoritySequence()
                                > candidate.authoritySequence()
                                && "CANDIDATE_STEP_RESULT".equals(
                                review.value().reviewObjectType())
                                && candidate.value().candidateResultId().equals(
                                review.value().reviewObjectId())))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
        if (unreviewed != null) {
            return model(ChainRole.REFLECTOR,
                    ChainWorkState.AWAITING_REVIEW,
                    "CANDIDATE_STEP_RESULT",
                    unreviewed.value().candidateResultId());
        }

        var latestReview = latest(projection.reviews());
        if (latestReview != null) {
            var delivered = sourceDelivery(
                    deliveries.terminal(), SourceKind.DECISION,
                    latestReview.value().reviewDecisionId());
            if (delivered != null) {
                return new ControlWait(WaitKind.DELIVERY_TERMINAL,
                        "DELIVERY", delivered.value().deliveryId());
            }
            Selection reviewSelection = reviewSelection(
                    projection, latestReview, currentInstruction == null
                            ? null : currentInstruction.instructionId());
            if (reviewSelection != null) {
                return reviewSelection;
            }
        }

        var readiness = latest(projection.readiness());
        if (readiness != null) {
            return new MechanicalFinalization(
                    readiness.value().readinessId(),
                    readiness.authoritySequence());
        }

        if (projection.stepState().isPresent()) {
            var step = projection.stepState().orElseThrow();
            return switch (step.status()) {
                case ACTIVE -> model(ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING,
                        step.authorityType(), step.authorityRef());
                case AWAITING_REVIEW -> model(ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW,
                        step.authorityType(), step.authorityRef());
                case SUPERSEDED_BY_REPLAN -> model(ChainRole.PLANNER,
                        ChainWorkState.PLANNING,
                        step.authorityType(), step.authorityRef());
                case READY, NOT_STARTED -> new ControlWait(
                        WaitKind.STEP_ACTIVATION_REQUIRED,
                        step.authorityType(), step.authorityRef());
                case WAITING_GAP -> new ControlWait(
                        WaitKind.PENDING_ITEM_REQUIRED,
                        step.authorityType(), step.authorityRef());
                case COMPLETED -> new ControlWait(
                        WaitKind.NEXT_STEP_OR_READINESS_REQUIRED,
                        step.authorityType(), step.authorityRef());
            };
        }

        var latestPlan = latestPlanForInstruction(
                projection.plans(), currentInstruction == null
                        ? null : currentInstruction.instructionId());
        if (latestPlan != null) {
            return new ControlWait(WaitKind.STEP_AUTHORITY_REQUIRED,
                    "PLAN_BINDING", latestPlan.value().planBindingId());
        }

        var latestRoute = latestRouteForInstruction(
                projection.routes(), currentInstruction == null
                        ? null : currentInstruction.instructionId());
        if (latestRoute != null
                && latestRoute.value().route() == ChainExecutionMode.DIRECT) {
            var delivered = sourceDelivery(
                    deliveries.terminal(), SourceKind.ROUTE,
                    latestRoute.value().routeDecisionId());
            if (delivered != null) {
                return new ControlWait(WaitKind.DELIVERY_TERMINAL,
                        "DELIVERY", delivered.value().deliveryId());
            }
            return model(ChainRole.ANSWER,
                    ChainWorkState.DIRECT_ANSWERING,
                    "ROUTE_DECISION",
                    latestRoute.value().routeDecisionId());
        }
        return model(ChainRole.PLANNER, ChainWorkState.PLANNING,
                latestRoute == null ? "TASK" : "ROUTE_DECISION",
                latestRoute == null ? projection.taskId()
                        : latestRoute.value().routeDecisionId());
    }

    private static ProductChainRecoverySource.ProposalProjection
            pendingValidationProposal(
                    ProductChainRecoverySource.RoleProjection projection,
                    ProductChainRecoverySource.PendingProjection pending) {
        List<ProductChainRecoverySource.ProposalProjection> matches =
                projection.proposals().stream()
                        .filter(value -> value.authoritySequence()
                                > pending.authoritySequence())
                        .filter(value -> value.invocation().role()
                                == pending.item().validationRole())
                        .filter(value -> value.invocation().workState()
                                == ChainWorkState.VALIDATING_PENDING_ITEM)
                        .filter(value -> value.latest().stateKind()
                                == ChainProposalState.ACCEPTED
                                || value.latest().stateKind()
                                == ChainProposalState
                                .REPLACED_BY_OFFICIAL_RESULT)
                        .filter(value -> targetsGap(
                                value, pending.item().gapId()))
                        .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "multiple current validation proposals target one PendingItem round");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .PermissionDecisionRecord> permissionDecision(
            ProductChainRecoverySource.RoleProjection projection,
            ProductChainRecoverySource.PendingProjection pending) {
        List<ProductChainRecoverySource.Sequenced<
                io.paperagent.v2.chain.ChainPersistenceRecords
                        .PermissionDecisionRecord>> matches = projection
                .permissions().stream()
                .filter(value -> value.authoritySequence()
                        > pending.sourceAuthoritySequence())
                .filter(value -> value.value().taskId().equals(
                        projection.taskId()))
                .filter(value -> value.value().gapId().equals(
                        pending.item().gapId()))
                .filter(value -> Objects.equals(
                        value.value().permissionScope(),
                        pending.item().permissionScope()))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "multiple permission decisions target one PendingItem");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static boolean targetsGap(
            ProductChainRecoverySource.ProposalProjection proposal,
            String gapId) {
        String raw = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposal().proposalKind().wireName()
                + "\",\"payload\":"
                + proposal.proposal().payload().json() + "}";
        return new io.paperagent.v2.chain.model
                .StrictChainProviderOutputParser().parse(
                raw, proposal.invocation().role(),
                proposal.invocation().workState(), gapId)
                .payload().gapValidation().gapId().equals(gapId);
    }

    private static Selection reviewSelection(
            ProductChainRecoverySource.RoleProjection projection,
            ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .ReviewDecisionRecord> review,
            String instructionId) {
        return switch (review.value().decisionKind()) {
            case REFLECTOR_REPLAN_REQUIRED -> {
                var successor = projection.plans().stream().filter(value ->
                        value.authoritySequence() > review.authoritySequence()
                                && (instructionId == null
                                || instructionId.equals(
                                value.value().instructionId())))
                        .findFirst().orElse(null);
                yield successor == null ? model(ChainRole.PLANNER,
                        ChainWorkState.PLANNING, "REVIEW_DECISION",
                        review.value().reviewDecisionId()) : null;
            }
            case REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE,
                    REFLECTOR_READY_TO_FINALIZE -> {
                var readiness = projection.readiness().stream().filter(value ->
                        value.authoritySequence() > review.authoritySequence()
                                && review.value().reviewDecisionId().equals(
                                value.value().reviewDecisionId()))
                        .findFirst().orElse(null);
                yield readiness == null
                        ? new ControlWait(WaitKind.READINESS_REQUIRED,
                        "REVIEW_DECISION",
                        review.value().reviewDecisionId())
                        : new MechanicalFinalization(
                        readiness.value().readinessId(),
                        readiness.authoritySequence());
            }
            case REFLECTOR_ACCEPT_STEP -> {
                boolean accepted = projection.accepted().stream().anyMatch(
                        value -> value.authoritySequence()
                                > review.authoritySequence()
                                && review.value().reviewDecisionId().equals(
                                value.value().reviewDecisionId()));
                yield accepted ? null : new ControlWait(
                        WaitKind.ACCEPTED_RESULT_REQUIRED,
                        "REVIEW_DECISION",
                        review.value().reviewDecisionId());
            }
            case REFLECTOR_NEED_USER_INPUT,
                    REFLECTOR_NEED_PERMISSION ->
                    pendingAfterReview(projection, review) == null
                            ? new ControlWait(
                            WaitKind.PENDING_ITEM_REQUIRED,
                            "REVIEW_DECISION",
                            review.value().reviewDecisionId())
                            : null;
            case REFLECTOR_TASK_FAILED -> new ControlWait(
                    WaitKind.TASK_OUTCOME_REQUIRED,
                    "REVIEW_DECISION", review.value().reviewDecisionId());
            case REFLECTOR_CONTINUE_STEP -> null;
            default -> throw new IllegalStateException(
                    "non-Reflector decision is stored as ReviewDecision");
        };
    }

    static ProductChainRecoverySource.PendingProjection
            pendingAfterReview(
            ProductChainRecoverySource.RoleProjection projection,
            ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .ReviewDecisionRecord> review) {
        List<ProductChainRecoverySource.ProposalProjection> proposals =
                projection.proposals().stream()
                        .filter(value -> value.proposal().proposalId().equals(
                                review.value().proposalId()))
                        .filter(value -> value.latest().stateKind()
                                == ChainProposalState
                                .REPLACED_BY_OFFICIAL_RESULT)
                        .filter(value -> "REVIEW_DECISION".equals(
                                value.latest().officialAuthorityType()))
                        .filter(value -> review.value().reviewDecisionId()
                                .equals(value.latest()
                                        .officialAuthorityRef()))
                        .toList();
        if (proposals.size() != 1) {
            throw new IllegalStateException(
                    "PendingItem ReviewDecision proposal binding is invalid");
        }
        List<ProductChainRecoverySource.PendingProjection> pending =
                projection.pending().stream()
                        .filter(value -> value.authoritySequence()
                                > review.authoritySequence())
                        .filter(value -> value.item().sourceProposalId().equals(
                                review.value().proposalId()))
                        .toList();
        if (pending.size() > 1) {
            throw new IllegalStateException(
                    "ReviewDecision has multiple PendingItems");
        }
        return pending.isEmpty() ? null : pending.get(0);
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord>
            taskOutcomeDelivery(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.TaskOutcomeRecord
                    outcome) {
        return projection.deliveries().stream()
                .filter(value -> outcome.outcomeId().equals(
                        value.value().taskOutcomeId()))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .ActionReceiptStepBlockRecord>
            unresolvedActionReceiptStepBlock(
                    ProductChainRecoverySource.RoleProjection projection) {
        return projection.actionReceiptStepBlocks().stream()
                .filter(block -> projection.outcome().stream().noneMatch(
                        outcome -> outcome.value().sourceDecisionId().equals(
                                block.value().stepBlockId())))
                .filter(block -> projection.reviews().stream().noneMatch(
                        review -> "ACTION_RECEIPT_STEP_BLOCK".equals(
                                review.value().reviewObjectType())
                                && block.value().stepBlockId().equals(
                                review.value().reviewObjectId())))
                .filter(block -> projection.proposals().stream().noneMatch(
                        proposal -> proposal.context() != null
                                && "ACTION_FAILURE_REVIEW".equals(
                                proposal.invocation().callReason())
                                && (proposal.latest().stateKind()
                                == ChainProposalState.ACCEPTED
                                || proposal.latest().stateKind()
                                == ChainProposalState
                                .REPLACED_BY_OFFICIAL_RESULT)
                                && block.value().repairContextRevisionId()
                                .equals(
                                proposal.context()
                                        .parentContextRevisionId())))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
    }


    private static ProductChainRecoverySource.ProposalProjection
            taskOutcomeAnswerProposal(
                    ProductChainRecoverySource.RoleProjection projection,
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .TaskOutcomeRecord outcome) {
        ChainProposalKind expectedKind = outcome.outcomeType()
                == ChainTaskOutcomeStatus.COMPLETED
                ? ChainProposalKind.ANSWER_FINAL_DELIVERY
                : ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        ChainWorkState expectedState = outcome.outcomeType()
                == ChainTaskOutcomeStatus.COMPLETED
                ? ChainWorkState.DELIVERING : ChainWorkState.TERMINAL;
        List<ProductChainRecoverySource.ProposalProjection> matches =
                projection.proposals().stream()
                        .filter(value -> value.context() != null)
                        .filter(value -> value.proposal().role()
                                == ChainRole.ANSWER)
                        .filter(value -> value.proposal().taskId().equals(
                                projection.taskId()))
                        .filter(value -> value.proposal().invocationId()
                                .equals(value.invocation().invocationId()))
                        .filter(value -> value.proposal().proposalKind()
                                == expectedKind)
                        .filter(value -> value.invocation().role()
                                == ChainRole.ANSWER)
                        .filter(value -> value.invocation().taskId().equals(
                                projection.taskId()))
                        .filter(value -> value.invocation().workState()
                                == expectedState)
                        .filter(value -> "TASK_OUTCOME".equals(
                                value.invocation().callReason()))
                        .filter(value -> value.invocation()
                                .contextRevisionId().equals(
                                        value.context()
                                                .contextRevisionId()))
                        .filter(value -> value.context()
                                .contextRevisionId().equals(
                                        ProductChainContextIdentity
                                                .taskOutcomeAnswer(
                                                        projection.taskId(),
                                                        outcome.outcomeId())))
                        .filter(value -> value.context().taskId().equals(
                                projection.taskId()))
                        .filter(value -> value.context().role()
                                == ChainRole.ANSWER)
                        .filter(value -> value.context().workState()
                                == expectedState)
                        .filter(value -> "TASK_OUTCOME".equals(
                                value.context().callReason()))
                        .filter(value -> value.context().status()
                                == ChainContextRevisionStatus.COMPLETE)
                        .filter(value -> Objects.equals(
                                value.context().instructionId(),
                                outcome.instructionId()))
                        .filter(value -> Objects.equals(
                                value.context().taskFrameId(),
                                outcome.taskFrameId()))
                        .filter(value -> Objects.equals(
                                value.context().planId(),
                                outcome.finalPlanId()))
                        .filter(value -> Objects.equals(
                                value.context().planRevisionId(),
                                outcome.finalPlanRevisionId()))
                        .filter(value -> !value.states().isEmpty()
                                && value.states().get(0).stateKind()
                                == ChainProposalState.ACCEPTED)
                        .filter(value -> value.latest().stateKind()
                                == ChainProposalState.ACCEPTED
                                || value.latest().stateKind()
                                == ChainProposalState
                                        .REPLACED_BY_OFFICIAL_RESULT
                                && "DELIVERY".equals(value.latest()
                                        .officialAuthorityType()))
                        .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "TaskOutcome has ambiguous accepted Answer proposals");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static ProductChainRecoverySource.ProposalProjection
            pendingItemAnswerProposal(
                    ProductChainRecoverySource.RoleProjection projection,
                    ProductChainRecoverySource.PendingProjection pending,
                    String instructionId,
                    ChainWorkState expectedState) {
        if (instructionId == null) {
            return null;
        }
        String expectedContextId = ProductChainContextIdentity
                .pendingItemAnswer(projection.taskId(),
                        pending.item().gapId(), expectedState.name());
        List<ProductChainRecoverySource.ProposalProjection> matches =
                projection.proposals().stream()
                        .filter(value -> value.context() != null)
                        .filter(value -> value.proposal().taskId().equals(
                                projection.taskId()))
                        .filter(value -> value.proposal().role()
                                == ChainRole.ANSWER)
                        .filter(value -> value.proposal().proposalKind()
                                == ChainProposalKind.ANSWER_USER_QUESTION)
                        .filter(value -> value.proposal().invocationId()
                                .equals(value.invocation().invocationId()))
                        .filter(value -> value.invocation().taskId().equals(
                                projection.taskId()))
                        .filter(value -> value.invocation().role()
                                == ChainRole.ANSWER)
                        .filter(value -> value.invocation().workState()
                                == expectedState)
                        .filter(value -> "PENDING_ITEM".equals(
                                value.invocation().callReason()))
                        .filter(value -> value.invocation()
                                .contextRevisionId().equals(
                                        expectedContextId))
                        .filter(value -> value.context()
                                .contextRevisionId().equals(
                                        expectedContextId))
                        .filter(value -> value.context().taskId().equals(
                                projection.taskId()))
                        .filter(value -> value.context().role()
                                == ChainRole.ANSWER)
                        .filter(value -> value.context().workState()
                                == expectedState)
                        .filter(value -> "PENDING_ITEM".equals(
                                value.context().callReason()))
                        .filter(value -> value.context().status()
                                == ChainContextRevisionStatus.COMPLETE)
                        .filter(value -> value.context().instructionId()
                                .equals(instructionId))
                        .filter(value -> !value.states().isEmpty()
                                && value.states().get(0).stateKind()
                                == ChainProposalState.ACCEPTED)
                        .filter(value -> value.latest().stateKind()
                                == ChainProposalState.ACCEPTED
                                || value.latest().stateKind()
                                == ChainProposalState
                                        .REPLACED_BY_OFFICIAL_RESULT
                                && "DELIVERY".equals(value.latest()
                                        .officialAuthorityType()))
                        .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "PendingItem has ambiguous accepted Answer proposals");
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static ChainDeliveryStatus deliveryStatus(
            ProductChainRecoverySource.RoleProjection projection,
            ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .DeliveryRecord> delivery) {
        List<io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryEventRecord>
                events = projection.deliveryEvents().getOrDefault(
                delivery.value().deliveryId(), List.of()).stream()
                .sorted(Comparator.comparingLong(value ->
                        value.eventSequence())).toList();
        validateDeliveryEventPrefix(delivery.value(), events,
                preDeliveryFailureCode(projection, delivery.value()));
        ChainDeliveryStatus latest = events.isEmpty() ? null
                : events.get(events.size() - 1).eventKind();
        return latest;
    }

    private static DeliveryProjection deliveryProjection(
            ProductChainRecoverySource.RoleProjection projection) {
        List<ProductChainRecoverySource.Sequenced<
                io.paperagent.v2.chain.ChainPersistenceRecords
                        .DeliveryRecord>> terminal = new java.util.ArrayList<>();
        Selection nextRecovery = null;
        for (var delivery : projection.deliveries().stream()
                .sorted(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).toList()) {
            ChainDeliveryStatus status = deliveryStatus(projection, delivery);
            var proposal = validateDeliveryAuthority(projection, delivery,
                    status == ChainDeliveryStatus.SUCCEEDED
                            || status
                            == ChainDeliveryStatus.DELIVERY_FAILED);
            if (status == ChainDeliveryStatus.SUCCEEDED
                    || status == ChainDeliveryStatus.DELIVERY_FAILED) {
                terminal.add(delivery);
            } else if (nextRecovery == null) {
                if (proposal == null) {
                    throw new IllegalStateException(
                            "Answer failure Delivery must be terminal");
                }
                boolean hasPending = !projection.deliveryEvents()
                        .getOrDefault(delivery.value().deliveryId(), List.of())
                        .isEmpty();
                boolean bound = proposal.latest().stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT;
                if (status == ChainDeliveryStatus.RETRYING && !bound) {
                    throw new IllegalStateException(
                            "retried Delivery lacks its official proposal binding");
                }
                nextRecovery = !hasPending || !bound
                        ? new MechanicalProposal(
                        proposal.proposal().proposalId(),
                        proposal.proposal().role(),
                        proposal.proposal().proposalKind(),
                        proposal.states().get(0).eventId())
                        : new MechanicalDelivery(
                        delivery.value().deliveryId(),
                        delivery.authoritySequence());
            }
        }
        return new DeliveryProjection(
                List.copyOf(terminal), nextRecovery);
    }

    private static ProductChainRecoverySource.ProposalProjection
            validateDeliveryAuthority(
            ProductChainRecoverySource.RoleProjection projection,
            ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .DeliveryRecord> sequenced,
            boolean terminal) {
        var delivery = sequenced.value();
        if (isAnswerContextFailureDelivery(projection, delivery)) {
            if (!terminal || delivery.answerContentId() != null
                    || delivery.assistantMessageId() != null
                    || projection.instructionValues().values().stream()
                    .noneMatch(instruction -> delivery.sourceCommandId()
                            .equals(instruction.commandId()))) {
                throw new IllegalStateException(
                        "Answer Context failure Delivery identity is invalid");
            }
            return null;
        }
        if (isAnswerModelFailureDelivery(projection, delivery)) {
            if (!terminal || delivery.answerContentId() != null
                    || delivery.assistantMessageId() != null) {
                throw new IllegalStateException(
                        "Answer failure Delivery identity is invalid");
            }
            validateFallbackDeliverySource(projection, delivery);
            return null;
        }
        if (isOutcomeFallbackDelivery(projection, delivery)) {
            if (!terminal || delivery.answerContentId() != null
                    || delivery.assistantMessageId() != null
                    || delivery.routeDecisionId() != null
                    || delivery.gapId() != null
                    || delivery.decisionId() != null) {
                throw new IllegalStateException(
                        "Outcome fallback Delivery identity is invalid");
            }
            validateOutcomeFallbackSource(projection, delivery);
            return null;
        }
        if (!projection.taskId().equals(delivery.taskId())
                || delivery.answerContentId() == null
                || delivery.assistantMessageId() == null
                || projection.instructionValues().values().stream().noneMatch(
                instruction -> delivery.sourceCommandId().equals(
                        instruction.commandId()))) {
            throw new IllegalStateException(
                    "Delivery identity is not bound to the frozen task");
        }

        List<ProductChainRecoverySource.ProposalProjection> proposals =
                projection.proposals().stream().filter(value ->
                        delivery.answerContentId().equals(
                                value.proposal().bodyAuthorityRef())).toList();
        if (proposals.size() != 1) {
            throw new IllegalStateException(
                    "Delivery must have one exact Answer proposal");
        }
        var proposal = proposals.get(0);
        boolean bound = proposal.latest().stateKind()
                == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                && "DELIVERY".equals(
                proposal.latest().officialAuthorityType())
                && delivery.deliveryId().equals(
                proposal.latest().officialAuthorityRef());
        if ((proposal.latest().stateKind() != ChainProposalState.ACCEPTED
                && !bound) || (terminal && !bound)
                || proposal.proposal().role() != ChainRole.ANSWER
                || !"ANSWER_BODY".equals(
                proposal.proposal().bodyAuthorityType())) {
            throw new IllegalStateException(
                    "Delivery Answer proposal authority is invalid");
        }

        ChainProposalKind expectedKind;
        if (delivery.routeDecisionId() != null) {
            var route = projection.routes().stream().filter(value ->
                    delivery.routeDecisionId().equals(
                            value.value().routeDecisionId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Delivery DIRECT route is missing"));
            var instruction = projection.instructionValues().get(
                    route.value().instructionId());
            if (instruction == null
                    || !delivery.sourceCommandId().equals(
                    instruction.commandId())) {
                throw new IllegalStateException(
                        "Delivery DIRECT source command is invalid");
            }
            expectedKind = ChainProposalKind.ANSWER_DIRECT_ANSWER;
        } else if (delivery.taskOutcomeId() != null) {
            var outcome = projection.outcome().filter(value ->
                    delivery.taskOutcomeId().equals(
                            value.value().outcomeId())).orElseThrow(() ->
                    new IllegalStateException(
                            "Delivery TaskOutcome source is missing"));
            if (!delivery.sourceCommandId().equals(
                    outcome.value().sourceCommandId())) {
                throw new IllegalStateException(
                        "Delivery TaskOutcome source command is invalid");
            }
            expectedKind = outcome.value().outcomeType()
                    == ChainTaskOutcomeStatus.COMPLETED
                    ? ChainProposalKind.ANSWER_FINAL_DELIVERY
                    : ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        } else if (delivery.gapId() != null) {
            var gap = projection.pending().stream().filter(value ->
                    delivery.gapId().equals(value.item().gapId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Delivery PendingItem source is missing"));
            requireSourceCurrentInstruction(projection,
                    delivery.sourceCommandId(), gap.sourceAuthoritySequence(),
                    "PendingItem");
            expectedKind = ChainProposalKind.ANSWER_USER_QUESTION;
        } else {
            var decision = projection.reviews().stream().filter(value ->
                    delivery.decisionId().equals(
                            value.value().reviewDecisionId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Delivery ReviewDecision source is missing"));
            requireSourceCurrentInstruction(projection,
                    delivery.sourceCommandId(), decision.authoritySequence(),
                    "ReviewDecision");
            expectedKind = ChainProposalKind.ANSWER_STATUS_OR_FAILURE;
        }
        if (proposal.proposal().proposalKind() != expectedKind) {
            throw new IllegalStateException(
                    "Delivery Answer kind does not match its source");
        }
        return proposal;
    }

    private static boolean isOutcomeFallbackDelivery(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery) {
        if (delivery.taskOutcomeId() == null) {
            return false;
        }
        var outcome = projection.outcome().filter(value ->
                delivery.taskOutcomeId().equals(
                        value.value().outcomeId())).orElse(null);
        if (outcome == null) {
            return false;
        }
        String expected = "delivery.fallback." + sha256(
                projection.taskId() + "\0" + outcome.value().outcomeId()
                        + "\0OUTCOME_FALLBACK");
        return expected.equals(delivery.deliveryId())
                && delivery.sourceCommandId().equals(
                outcome.value().sourceCommandId());
    }

    private static boolean isAnswerModelFailureDelivery(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery) {
        return projection.modelFailures().stream()
                .filter(value -> value.invocation().role()
                        == ChainRole.ANSWER)
                .anyMatch(value -> modelFailureDeliveryId(
                        value.invocation().invocationId()).equals(
                        delivery.deliveryId()));
    }

    private static boolean isAnswerContextFailureDelivery(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery) {
        return projection.contextFailures().stream()
                .filter(value -> value.buildFailure() != null)
                .filter(value -> value.contextRevision().role()
                        == ChainRole.ANSWER)
                .anyMatch(value -> contextFailureDeliveryId(
                        projection.taskId(), value.sourceAuthorityRef())
                        .equals(delivery.deliveryId()));
    }

    private static String preDeliveryFailureCode(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery) {
        if (isAnswerModelFailureDelivery(projection, delivery)) {
            return "CHAIN_ANSWER_MODEL_CALL_FAILED";
        }
        if (isAnswerContextFailureDelivery(projection, delivery)) {
            return "CONTEXT_INPUT_BLOCKED";
        }
        return null;
    }

    private static void validateOutcomeFallbackSource(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery) {
        var outcome = projection.outcome().filter(value ->
                delivery.taskOutcomeId().equals(
                        value.value().outcomeId())).orElseThrow(() ->
                new IllegalStateException(
                        "Outcome fallback Delivery source is missing"));
        if (!projection.taskId().equals(delivery.taskId())
                || !outcome.value().sourceCommandId().equals(
                        delivery.sourceCommandId())
                || projection.instructionValues().values().stream().noneMatch(
                        instruction -> delivery.sourceCommandId().equals(
                                instruction.commandId()))) {
            throw new IllegalStateException(
                    "Outcome fallback Delivery source command is invalid");
        }
    }

    private static void validateFallbackDeliverySource(
            ProductChainRecoverySource.RoleProjection projection,
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery) {
        if (!projection.taskId().equals(delivery.taskId())
                || projection.instructionValues().values().stream().noneMatch(
                instruction -> delivery.sourceCommandId().equals(
                        instruction.commandId()))) {
            throw new IllegalStateException(
                    "Answer failure Delivery source command is invalid");
        }
        FailureSource expected;
        if (delivery.taskOutcomeId() != null) {
            expected = new FailureSource("TASK_OUTCOME",
                    delivery.taskOutcomeId());
        } else if (delivery.gapId() != null) {
            expected = new FailureSource("PENDING_ITEM", delivery.gapId());
        } else if (delivery.routeDecisionId() != null) {
            expected = new FailureSource(
                    "ROUTE_DECISION", delivery.routeDecisionId());
        } else {
            throw new IllegalStateException(
                    "Answer failure Delivery source is invalid");
        }
        var failure = projection.modelFailures().stream().filter(value ->
                modelFailureDeliveryId(value.invocation().invocationId())
                        .equals(delivery.deliveryId())).findFirst()
                .orElseThrow();
        if (!failureSource(projection, failure).equals(expected)) {
            throw new IllegalStateException(
                    "Answer failure Delivery changed formal source");
        }
    }

    private static void requireSourceCurrentInstruction(
            ProductChainRecoverySource.RoleProjection projection,
            String sourceCommandId,
            long sourceAuthoritySequence,
            String sourceType) {
        var current = projection.instructions().stream()
                .filter(value -> value.authoritySequence()
                        < sourceAuthoritySequence)
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence))
                .orElseThrow(() -> new IllegalStateException(
                        "Delivery " + sourceType
                                + " source has no prior instruction binding"));
        var instruction = projection.instructionValues().get(
                current.value().instructionId());
        if (instruction == null
                || !sourceCommandId.equals(instruction.commandId())) {
            throw new IllegalStateException(
                    "Delivery " + sourceType
                            + " source command is not source-current");
        }
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .TaskInstructionBindingRecord> currentInstructionBinding(
            ProductChainRecoverySource.RoleProjection projection,
            List<ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .DeliveryRecord>> terminalDeliveries) {
        List<ProductChainRecoverySource.Sequenced<
                io.paperagent.v2.chain.ChainPersistenceRecords
                        .TaskInstructionBindingRecord>> instructions =
                projection.instructions().stream().sorted(
                        Comparator.comparingLong((ProductChainRecoverySource
                                .Sequenced<io.paperagent.v2.chain
                                .ChainPersistenceRecords
                                .TaskInstructionBindingRecord> value) ->
                                value.authoritySequence()).reversed()).toList();
        for (var binding : instructions) {
            var instruction = projection.instructionValues().get(
                    binding.value().instructionId());
            if (instruction == null) {
                throw new IllegalStateException(
                        "bound instruction is absent from the projection");
            }
            boolean completedStatusInquiry = terminalDeliveries.stream()
                    .map(ProductChainRecoverySource.Sequenced::value)
                    .anyMatch(delivery -> delivery.decisionId() != null
                            && delivery.sourceCommandId().equals(
                            instruction.commandId()));
            if (!completedStatusInquiry) {
                return binding;
            }
        }
        return null;
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord>
            sourceDelivery(
            List<ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .DeliveryRecord>> deliveries,
            SourceKind kind,
            String sourceRef) {
        return deliveries.stream().filter(value -> sourceRef.equals(
                        kind.ref(value.value())))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
    }

    private static void validateDeliveryEventPrefix(
            io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord
                    delivery,
            List<io.paperagent.v2.chain.ChainPersistenceRecords
                    .DeliveryEventRecord> events,
            String preDeliveryFailureCode) {
        if (events.isEmpty()) {
            return;
        }
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                events.get(0).runtimePolicyVersion());
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (!delivery.deliveryId().equals(event.deliveryId())
                    || !delivery.taskId().equals(event.taskId())
                    || event.eventSequence() != index + 1L
                    || !runtimePolicy.policyVersion().equals(
                    event.runtimePolicyVersion())) {
                throw new IllegalStateException(
                        "frozen Delivery event prefix identity is invalid");
            }
            if (index == 0) {
                boolean outcomeFallbackSuccess = delivery.deliveryId()
                        .startsWith("delivery.fallback.")
                        && event.eventKind()
                        == ChainDeliveryStatus.SUCCEEDED
                        && event.attemptNo() == 1
                        && event.errorCode() == null;
                boolean preDeliveryFailure = preDeliveryFailureCode != null
                        && event.eventKind()
                        == ChainDeliveryStatus.DELIVERY_FAILED
                        && event.attemptNo() == 1
                        && preDeliveryFailureCode.equals(
                        event.errorCode());
                if (!preDeliveryFailure && !outcomeFallbackSuccess
                        && (event.eventKind()
                        != ChainDeliveryStatus.PENDING
                        || event.attemptNo() != 0)) {
                    throw new IllegalStateException(
                            "frozen Delivery event prefix must start PENDING");
                }
                continue;
            }
            var previous = events.get(index - 1);
            if (event.attemptNo() != index
                    || event.eventKind() == ChainDeliveryStatus.PENDING
                    || ((event.eventKind() == ChainDeliveryStatus.RETRYING
                    || event.eventKind()
                    == ChainDeliveryStatus.DELIVERY_FAILED)
                    && !(preDeliveryFailureCode != null
                    ? preDeliveryFailureCode
                    : "CHAIN_DELIVERY_MESSAGE_WRITE_FAILED").equals(
                            event.errorCode()))
                    || previous.eventKind() == ChainDeliveryStatus.SUCCEEDED
                    || previous.eventKind()
                    == ChainDeliveryStatus.DELIVERY_FAILED
                    || (event.eventKind() == ChainDeliveryStatus.RETRYING
                    && event.attemptNo()
                    >= runtimePolicy.deliveryAttemptsTotal())
                    || (event.eventKind()
                    == ChainDeliveryStatus.DELIVERY_FAILED
                    && event.attemptNo()
                    != runtimePolicy.deliveryAttemptsTotal())) {
                throw new IllegalStateException(
                        "frozen Delivery event prefix transition is invalid");
            }
        }
    }

    private static ProductChainRecoverySource.RoleProjection projection(
            ChainRecoveryRuntime.RecoverySnapshot snapshot) {
        if (!(snapshot.roleProjection()
                instanceof ProductChainRecoverySource.RoleProjection value)) {
            throw new IllegalStateException(
                    "product role selection requires its typed frozen projection");
        }
        if (!snapshot.taskId().equals(value.taskId())
                || snapshot.factCuts().stream().anyMatch(cut ->
                !value.readBoundary().equals(cut.readBoundary()))) {
            throw new IllegalStateException(
                    "frozen role projection does not match the recovery snapshot");
        }
        return value;
    }

    private static boolean hasInstructionSuccessor(
            ProductChainRecoverySource.RoleProjection projection,
            String instructionId, long instructionSequence) {
        return projection.routes().stream().anyMatch(value ->
                value.authoritySequence() > instructionSequence
                        && instructionId.equals(value.value().instructionId()))
                || projection.plans().stream().anyMatch(value ->
                value.authoritySequence() > instructionSequence
                        && instructionId.equals(value.value().instructionId()))
                || projection.dispositions().stream().anyMatch(value ->
                value.authoritySequence() > instructionSequence
                        && instructionId.equals(value.value().instructionId()));
    }

    private static <T> ProductChainRecoverySource.Sequenced<T> latest(
            List<ProductChainRecoverySource.Sequenced<T>> values) {
        return values.stream().max(Comparator.comparingLong(
                ProductChainRecoverySource.Sequenced::authoritySequence))
                .orElse(null);
    }

    private static ProductChainRecoverySource.ModelFailureProjection
            unresolvedModelFailure(
                    ProductChainRecoverySource.RoleProjection projection) {
        return projection.modelFailures().stream()
                .filter(value -> !value.successorInvocationPresent())
                .filter(value -> !value.formalReviewInvocationPresent())
                .filter(value -> value.contextInvocationCount()
                        >= ChainRuntimePolicy.requireVersion(
                        value.invocation().runtimePolicyVersion())
                        .modelInvocationsPerContextTotal())
                .filter(value -> projection.outcome().stream().noneMatch(
                        outcome -> outcome.value().sourceDecisionId().equals(
                                value.invocation().invocationId())))
                .filter(value -> projection.deliveries().stream().noneMatch(
                        delivery -> delivery.value().deliveryId().equals(
                                modelFailureDeliveryId(
                                        value.invocation().invocationId()))))
                .filter(value -> !(value.invocation().role()
                        == ChainRole.ANSWER
                        && projection.outcome().isPresent()
                        && taskOutcomeDelivery(projection,
                        projection.outcome().orElseThrow().value()) != null
                        && deliveryStatus(projection,
                        taskOutcomeDelivery(projection,
                                projection.outcome().orElseThrow().value()))
                        == ChainDeliveryStatus.SUCCEEDED))
                .filter(value -> projection.modelFailureStepBlocks().stream()
                        .noneMatch(block -> block.value().invocationId()
                                .equals(value.invocation().invocationId())))
                .max(Comparator.comparingInt(value ->
                        value.invocation().invocationOrdinal()))
                .orElse(null);
    }

    private static ProductChainRecoverySource.ContextFailureProjection
            unresolvedContextFailure(
                    ProductChainRecoverySource.RoleProjection projection) {
        return projection.contextFailures().stream()
                .filter(value -> value.buildFailure() != null)
                .filter(value -> !value.successorContextPresent())
                .filter(value -> projection.outcome().stream().noneMatch(
                        outcome -> outcome.value().sourceDecisionId().equals(
                                value.sourceAuthorityRef())))
                .filter(value -> projection.deliveries().stream().noneMatch(
                        delivery -> delivery.value().deliveryId().equals(
                                contextFailureDeliveryId(projection.taskId(),
                                        value.sourceAuthorityRef()))))
                .filter(value -> projection.reviews().stream().noneMatch(
                        review -> value.sourceAuthorityType().equals(
                                review.value().reviewObjectType())
                                && value.sourceAuthorityRef().equals(
                                review.value().reviewObjectId())))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.ContextFailureProjection
                                ::authoritySequence)).orElse(null);
    }

    private static String contextFailureDeliveryId(
            String taskId, String failureId) {
        return "delivery.context-failure."
                + sha256(taskId + "\0" + failureId);
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords
                    .ModelFailureStepBlockRecord>
            unresolvedModelFailureStepBlock(
                    ProductChainRecoverySource.RoleProjection projection) {
        return projection.modelFailureStepBlocks().stream()
                .filter(block -> projection.outcome().stream().noneMatch(
                        outcome -> outcome.value().sourceDecisionId().equals(
                                block.value().stepBlockId())))
                .filter(block -> projection.reviews().stream().noneMatch(
                        review -> "MODEL_FAILURE_STEP_BLOCK".equals(
                                review.value().reviewObjectType())
                                && block.value().stepBlockId().equals(
                                review.value().reviewObjectId())))
                .filter(block -> projection.proposals().stream().noneMatch(
                        proposal -> proposal.context() != null
                                && "MODEL_CALL_FAILED_REVIEW".equals(
                                proposal.invocation().callReason())
                                && (proposal.latest().stateKind()
                                == ChainProposalState.ACCEPTED
                                || proposal.latest().stateKind()
                                == ChainProposalState
                                .REPLACED_BY_OFFICIAL_RESULT)
                                && block.value().contextRevisionId().equals(
                                proposal.context()
                                        .parentContextRevisionId())))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
    }

    private static FailureSource failureSource(
            ProductChainRecoverySource.RoleProjection projection,
            ProductChainRecoverySource.ModelFailureProjection failure) {
        if (failure.invocation().role() != ChainRole.ANSWER) {
            return new FailureSource(
                    "MODEL_CALL_FAILED", failure.invocation().invocationId());
        }
        if (projection.outcome().isPresent()) {
            return new FailureSource("TASK_OUTCOME",
                    projection.outcome().orElseThrow().value().outcomeId());
        }
        List<ProductChainRecoverySource.PendingProjection> pending = projection
                .pending().stream().filter(value ->
                        value.status() == ChainPendingItemStatus.PENDING)
                .toList();
        if (pending.size() == 1) {
            return new FailureSource(
                    "PENDING_ITEM", pending.get(0).item().gapId());
        }
        var route = latest(projection.routes());
        if (route != null) {
            return new FailureSource(
                    "ROUTE_DECISION", route.value().routeDecisionId());
        }
        throw new IllegalStateException(
                "Answer model failure lacks one formal delivery source");
    }

    public static String modelFailureDeliveryId(String invocationId) {
        return "delivery.model-failure." + sha256(invocationId);
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords.PlanBindingRecord>
            latestPlanForInstruction(
            List<ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .PlanBindingRecord>> values,
            String instructionId) {
        return values.stream().filter(value -> instructionId == null
                        || instructionId.equals(value.value().instructionId()))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
    }

    private static ProductChainRecoverySource.Sequenced<
            io.paperagent.v2.chain.ChainPersistenceRecords.RouteDecisionRecord>
            latestRouteForInstruction(
            List<ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .RouteDecisionRecord>> values,
            String instructionId) {
        return values.stream().filter(value -> instructionId == null
                        || instructionId.equals(value.value().instructionId()))
                .max(Comparator.comparingLong(
                        ProductChainRecoverySource.Sequenced
                                ::authoritySequence)).orElse(null);
    }

    private static Model model(
            ChainRole role, ChainWorkState state,
            String authorityType, String authorityRef) {
        return new Model(new ChainRecoveryRuntime.NextDirective(
                role, state, authorityType, authorityRef));
    }

    public sealed interface Selection permits Model,
            MechanicalDelivery, MechanicalFinalization, MechanicalProposal,
            MechanicalPermission, MechanicalModelFailure,
            MechanicalContextFailure,
            ControlWait {
    }

    public record Model(ChainRecoveryRuntime.NextDirective directive)
            implements Selection {
        public Model {
            Objects.requireNonNull(directive, "directive");
        }
    }

    public record MechanicalFinalization(
            String readinessId, long authoritySequence) implements Selection {
        public MechanicalFinalization {
            required(readinessId, "readinessId");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    public record MechanicalDelivery(
            String deliveryId, long authoritySequence) implements Selection {
        public MechanicalDelivery {
            required(deliveryId, "deliveryId");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    public record MechanicalPermission(
            String permissionDecisionId,
            long authoritySequence) implements Selection {
        public MechanicalPermission {
            required(permissionDecisionId, "permissionDecisionId");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    public record MechanicalModelFailure(
            String invocationId,
            ChainRole failedRole,
            String sourceAuthorityType,
            String sourceAuthorityRef) implements Selection {
        public MechanicalModelFailure {
            required(invocationId, "invocationId");
            Objects.requireNonNull(failedRole, "failedRole");
            required(sourceAuthorityType, "sourceAuthorityType");
            required(sourceAuthorityRef, "sourceAuthorityRef");
        }
    }

    public record MechanicalContextFailure(
            String contextBuildFailureId,
            ChainRole failedRole) implements Selection {
        public MechanicalContextFailure {
            required(contextBuildFailureId, "contextBuildFailureId");
            Objects.requireNonNull(failedRole, "failedRole");
            if (failedRole == ChainRole.EXECUTOR) {
                throw new IllegalArgumentException(
                        "Executor Context failure requires Reflector");
            }
        }
    }

    private record FailureSource(String type, String ref) {
        private FailureSource {
            required(type, "type");
            required(ref, "ref");
        }
    }

    public record MechanicalProposal(
            String proposalId,
            ChainRole role,
            ChainProposalKind proposalKind,
            String acceptedStateEventId) implements Selection {
        public MechanicalProposal {
            required(proposalId, "proposalId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(proposalKind, "proposalKind");
            required(acceptedStateEventId, "acceptedStateEventId");
        }
    }

    public record ControlWait(
            WaitKind kind,
            String authorityType,
            String authorityRef) implements Selection {
        public ControlWait {
            Objects.requireNonNull(kind, "kind");
            required(authorityType, "authorityType");
            required(authorityRef, "authorityRef");
        }
    }

    public enum WaitKind {
        AMBIGUOUS_PENDING_ITEM,
        TASK_OUTCOME_REQUIRED,
        PENDING_ITEM_REQUIRED,
        ACCEPTED_RESULT_REQUIRED,
        READINESS_REQUIRED,
        STEP_ACTIVATION_REQUIRED,
        NEXT_STEP_OR_READINESS_REQUIRED,
        STEP_AUTHORITY_REQUIRED,
        PERMISSION_DECISION_REQUIRED,
        DELIVERY_TERMINAL
    }

    private enum SourceKind {
        ROUTE {
            @Override
            String ref(io.paperagent.v2.chain.ChainPersistenceRecords
                               .DeliveryRecord delivery) {
                return delivery.routeDecisionId();
            }
        },
        GAP {
            @Override
            String ref(io.paperagent.v2.chain.ChainPersistenceRecords
                               .DeliveryRecord delivery) {
                return delivery.gapId();
            }
        },
        DECISION {
            @Override
            String ref(io.paperagent.v2.chain.ChainPersistenceRecords
                               .DeliveryRecord delivery) {
                return delivery.decisionId();
            }
        };

        abstract String ref(io.paperagent.v2.chain.ChainPersistenceRecords
                                    .DeliveryRecord delivery);
    }

    private record DeliveryProjection(
            List<ProductChainRecoverySource.Sequenced<
                    io.paperagent.v2.chain.ChainPersistenceRecords
                            .DeliveryRecord>> terminal,
            Selection nextRecovery) {
        private DeliveryProjection {
            terminal = List.copyOf(terminal);
        }
    }

    static final class NonModelSelection extends RuntimeException {
        private final ChainRecoveryRuntime.RecoverySnapshot snapshot;
        private final Selection selection;

        NonModelSelection(
                ChainRecoveryRuntime.RecoverySnapshot snapshot,
                Selection selection) {
            super("mechanical recovery directive", null, false, false);
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.selection = Objects.requireNonNull(selection, "selection");
        }

        ChainRecoveryRuntime.RecoverySnapshot snapshot() {
            return snapshot;
        }

        Selection selection() {
            return selection;
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
