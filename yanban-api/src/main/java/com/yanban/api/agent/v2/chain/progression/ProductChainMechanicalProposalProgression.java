package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainRole;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Consumes exactly the accepted proposal selected by durable recovery. */
@Component
public final class ProductChainMechanicalProposalProgression
        implements ProductChainTaskProgressionAdapter.ProposalProgression {
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProjectChainPlannerProgression planner;
    private final ProductChainExecutorProgression executor;
    private final ProductChainAnswerDeliveryProgression answer;
    private final ProductChainPendingItemValidationProgression pendingValidation;
    private final ProductChainFinalizationFailureProgression
            finalizationFailures;
    private final ProductChainStepBlockedProgression stepBlocked;
    private final ProductChainModelFailureProgression modelFailures;
    private final Clock clock;

    @Autowired
    public ProductChainMechanicalProposalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemValidationProgression pendingValidation,
            ProductChainFinalizationFailureProgression
                    finalizationFailures,
            ProductChainStepBlockedProgression stepBlocked,
            ProductChainModelFailureProgression modelFailures) {
        this(foundations, models, workflow, planner, executor, answer,
                pendingValidation, finalizationFailures, stepBlocked,
                modelFailures,
                Clock.systemUTC());
    }

    ProductChainMechanicalProposalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            Clock clock) {
        this(foundations, models, workflow, planner, executor, answer,
                null, null, null, null, clock);
    }

    ProductChainMechanicalProposalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemValidationProgression pendingValidation,
            Clock clock) {
        this(foundations, models, workflow, planner, executor, answer,
                pendingValidation, null, null, null, clock);
    }

    ProductChainMechanicalProposalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemValidationProgression pendingValidation,
            ProductChainFinalizationFailureProgression finalizationFailures,
            Clock clock) {
        this(foundations, models, workflow, planner, executor, answer,
                pendingValidation, finalizationFailures, null, null, clock);
    }

    ProductChainMechanicalProposalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemValidationProgression pendingValidation,
            ProductChainFinalizationFailureProgression
                    finalizationFailures,
            ProductChainStepBlockedProgression stepBlocked,
            Clock clock) {
        this(foundations, models, workflow, planner, executor, answer,
                pendingValidation, finalizationFailures, stepBlocked, null,
                clock);
    }

    ProductChainMechanicalProposalProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemValidationProgression pendingValidation,
            ProductChainFinalizationFailureProgression
                    finalizationFailures,
            ProductChainStepBlockedProgression stepBlocked,
            ProductChainModelFailureProgression modelFailures,
            Clock clock) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.answer = Objects.requireNonNull(answer, "answer");
        this.pendingValidation = pendingValidation;
        this.finalizationFailures = finalizationFailures;
        this.stepBlocked = stepBlocked;
        this.modelFailures = modelFailures;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProductChainTaskProgressionAdapter.ActionReceipt advance(
            ProductChainTaskProgressionAdapter.MechanicalCommand command) {
        Objects.requireNonNull(command, "command");
        if (!(command.selection() instanceof ProductChainNextRoleSelector
                .MechanicalProposal selected)) {
            throw failure("CHAIN_MECHANICAL_PROPOSAL_SELECTION_REQUIRED");
        }
        var task = foundations.findTask(command.taskId()).orElseThrow(
                () -> failure("CHAIN_MECHANICAL_PROPOSAL_TASK_MISSING"));
        var proposal = models.findProposal(selected.proposalId()).orElseThrow(
                () -> failure("CHAIN_MECHANICAL_PROPOSAL_MISSING"));
        if (!proposal.taskId().equals(task.taskId())
                || proposal.role() != selected.role()
                || proposal.proposalKind() != selected.proposalKind()
                || proposal.proposalKind().role() != proposal.role()) {
            throw failure("CHAIN_MECHANICAL_PROPOSAL_IDENTITY_INVALID");
        }
        verifyAcceptedSelection(task.taskId(), proposal.proposalId(),
                selected.acceptedStateEventId());
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_MECHANICAL_PROPOSAL_INVOCATION_MISSING"));
        if (invocation.workState()
                == io.paperagent.v2.chain.ChainWorkState
                .VALIDATING_PENDING_ITEM) {
            if (pendingValidation == null) {
                throw failure(
                        "CHAIN_PENDING_ITEM_VALIDATION_OWNER_MISSING");
            }
            pendingValidation.consume(task.taskId(), proposal.proposalId(),
                    clock.instant());
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction
                            .mechanical("MODEL_PROPOSAL",
                                    proposal.proposalId()));
        }
        var instruction = currentInstruction(task);
        if (proposal.role() == ChainRole.PLANNER) {
            planner.commitAcceptedProposal(task, instruction,
                    proposal.proposalId(), clock.instant());
        } else if (proposal.role() == ChainRole.EXECUTOR) {
            executor.consumeAcceptedProposal(task.taskId(),
                    proposal.proposalId(), clock.instant());
        } else if (proposal.role() == ChainRole.ANSWER) {
            if (proposal.proposalKind()
                    == ChainProposalKind.ANSWER_DIRECT_ANSWER
                    || proposal.proposalKind()
                    == ChainProposalKind.ANSWER_ESCALATE_TO_PERSISTENT) {
                var route = currentDirectRoute(task.taskId(),
                        instruction.instructionId());
                answer.consumeAcceptedDirect(task, instruction,
                        route.routeDecisionId(), proposal.proposalId(),
                        clock.instant());
            } else if (proposal.proposalKind()
                    == ChainProposalKind.ANSWER_FINAL_DELIVERY
                    || proposal.proposalKind()
                    == ChainProposalKind.ANSWER_STATUS_OR_FAILURE) {
                answer.consumeAcceptedPersistent(task, instruction,
                        proposal.proposalId(), clock.instant());
            } else if (proposal.proposalKind()
                    == ChainProposalKind.ANSWER_USER_QUESTION) {
                var pending = currentOpenPending(task.taskId());
                answer.consumeAcceptedPendingItem(task, instruction,
                        pending.gapId(), proposal.proposalId(),
                        clock.instant());
            } else {
                throw failure(
                        "CHAIN_MECHANICAL_ANSWER_PROPOSAL_KIND_INVALID");
            }
        } else if (proposal.role() == ChainRole.REFLECTOR) {
            if (ProductChainFinalizationFailureProgression.CALL_REASON.equals(
                    invocation.callReason())) {
                if (finalizationFailures == null) {
                    throw failure(
                            "CHAIN_FINALIZATION_FAILURE_PROPOSAL_OWNER_MISSING");
                }
                finalizationFailures.consume(task, instruction,
                        proposal.proposalId(), clock.instant());
            } else if (ProductChainStepBlockedProgression.CALL_REASON.equals(
                    invocation.callReason())) {
                if (stepBlocked == null) {
                    throw failure(
                            "CHAIN_STEP_BLOCK_PROPOSAL_OWNER_MISSING");
                }
                stepBlocked.consume(task, instruction,
                        proposal.proposalId(), clock.instant());
            } else if ("MODEL_CALL_FAILED_REVIEW".equals(
                    invocation.callReason())
                    || "CONTEXT_BUILD_FAILURE_REVIEW".equals(
                    invocation.callReason())
                    || "ACTION_FAILURE_REVIEW".equals(
                    invocation.callReason())) {
                if (modelFailures == null
                        || !modelFailures.handlesReviewProposal(
                        task.taskId(), proposal.proposalId())) {
                    throw failure(
                            "CHAIN_MODEL_FAILURE_PROPOSAL_OWNER_MISSING");
                }
                modelFailures.consumeReview(task, instruction,
                        proposal.proposalId(), clock.instant());
            } else {
                executor.consumeAcceptedReflectorProposal(task, instruction,
                        proposal.proposalId(), clock.instant());
            }
        } else {
            throw failure("CHAIN_MECHANICAL_PROPOSAL_OWNER_MISSING");
        }
        return new ProductChainTaskProgressionAdapter.ActionReceipt(
                ProductChainTaskProgressionAdapter.SelectedAction.mechanical(
                        "MODEL_PROPOSAL", proposal.proposalId()));
    }

    private void verifyAcceptedSelection(
            String taskId, String proposalId, String acceptedEventId) {
        var states = models.findProposalStateEvents(proposalId).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence))
                .toList();
        if (states.isEmpty() || states.size() > 2) {
            throw failure("CHAIN_MECHANICAL_PROPOSAL_STATE_INVALID");
        }
        java.util.List<ChainProposalState> prefix = new java.util.ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposalId)
                    || state.stateSequence() != index + 1L) {
                throw failure("CHAIN_MECHANICAL_PROPOSAL_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_MECHANICAL_PROPOSAL_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED
                || !states.get(0).eventId().equals(acceptedEventId)) {
            throw failure("CHAIN_MECHANICAL_PROPOSAL_SELECTION_STALE");
        }
    }

    private ChainPersistenceRecords.InstructionRecord currentInstruction(
            ChainPersistenceRecords.TaskRecord task) {
        var bindings = foundations.findTaskInstructions(
                        task.taskId(), task.nextEventSequence()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord
                                ::taskInstructionSequence))
                .toList();
        if (bindings.isEmpty()) {
            throw failure("CHAIN_MECHANICAL_INSTRUCTION_MISSING");
        }
        for (int index = 0; index < bindings.size(); index++) {
            if (!bindings.get(index).taskId().equals(task.taskId())
                    || bindings.get(index).taskInstructionSequence()
                    != index + 1L) {
                throw failure("CHAIN_MECHANICAL_INSTRUCTION_PREFIX_INVALID");
            }
        }
        var instruction = foundations.findInstruction(
                        bindings.get(bindings.size() - 1).instructionId())
                .orElseThrow(() -> failure(
                        "CHAIN_MECHANICAL_INSTRUCTION_MISSING"));
        if (instruction.sessionId() != task.sessionId()) {
            throw failure("CHAIN_MECHANICAL_INSTRUCTION_INVALID");
        }
        return instruction;
    }

    private ChainPersistenceRecords.RouteDecisionRecord currentDirectRoute(
            String taskId, String instructionId) {
        var routes = workflow.findRouteDecisions(taskId).stream()
                .filter(value -> value.instructionId().equals(instructionId))
                .filter(value -> value.decisionKind()
                        == ChainPersistenceRecords.RouteDecisionType.INITIAL)
                .filter(value -> value.route() == ChainExecutionMode.DIRECT)
                .toList();
        if (routes.size() != 1) {
            throw failure("CHAIN_MECHANICAL_DIRECT_ROUTE_INVALID");
        }
        return routes.get(0);
    }

    private ChainPersistenceRecords.PendingItemRecord currentOpenPending(
            String taskId) {
        List<ChainPersistenceRecords.PendingItemRecord> open = workflow
                .findPendingItems(taskId).stream().filter(item -> {
                    var events = workflow.findPendingItemEvents(
                            item.gapId()).stream()
                            .sorted(Comparator.comparingInt(
                                    ChainPersistenceRecords
                                            .PendingItemEventRecord
                                            ::responseRound))
                            .toList();
                    return events.isEmpty()
                            || events.get(events.size() - 1).eventKind()
                            == ChainPendingItemStatus.PENDING;
                }).toList();
        if (open.size() != 1) {
            throw failure("CHAIN_MECHANICAL_PENDING_ITEM_INVALID");
        }
        return open.get(0);
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
