package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainPlanTransitionDriver;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoverySource;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Executes exactly one model action selected from a frozen recovery cut. */
@Component
public final class ProductChainModelProgression
        implements ProductChainTaskProgressionAdapter.ModelProgression {
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final ProjectChainPlannerProgression planner;
    private final ProductChainPlanTransitionDriver planTransitions;
    private final ProductChainExecutorProgression executor;
    private final ProductChainAnswerDeliveryProgression answer;
    private final ProductChainPendingItemModelInvoker pendingValidation;
    private final ProductChainFinalizationFailureProgression
            finalizationFailures;
    private final ProductChainStepBlockedProgression stepBlocked;
    private final ProductChainModelFailureProgression modelFailures;
    private final FrozenInputPort frozenInputs;
    private final Clock clock;

    @Autowired
    public ProductChainModelProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ProjectChainPlannerProgression planner,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemModelInvoker pendingValidation,
            ProductChainFinalizationFailureProgression
                    finalizationFailures,
            ProductChainStepBlockedProgression stepBlocked,
            ProductChainModelFailureProgression modelFailures) {
        this(foundations, sessions, messages, planner, planTransitions,
                executor, answer, pendingValidation, finalizationFailures,
                stepBlocked, modelFailures,
                ProductChainRecoverySource::frozenModelInput,
                Clock.systemUTC());
    }

    ProductChainModelProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ProjectChainPlannerProgression planner,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            FrozenInputPort frozenInputs,
            Clock clock) {
        this(foundations, sessions, messages, planner, planTransitions,
                executor, answer, null, null, null, null, frozenInputs, clock);
    }

    ProductChainModelProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ProjectChainPlannerProgression planner,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemModelInvoker pendingValidation,
            FrozenInputPort frozenInputs,
            Clock clock) {
        this(foundations, sessions, messages, planner, planTransitions,
                executor, answer, pendingValidation, null, null, null, frozenInputs,
                clock);
    }

    ProductChainModelProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ProjectChainPlannerProgression planner,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemModelInvoker pendingValidation,
            ProductChainFinalizationFailureProgression finalizationFailures,
            FrozenInputPort frozenInputs,
            Clock clock) {
        this(foundations, sessions, messages, planner, planTransitions,
                executor, answer, pendingValidation, finalizationFailures,
                null, null, frozenInputs, clock);
    }

    ProductChainModelProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ProjectChainPlannerProgression planner,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemModelInvoker pendingValidation,
            ProductChainFinalizationFailureProgression
                    finalizationFailures,
            ProductChainStepBlockedProgression stepBlocked,
            FrozenInputPort frozenInputs,
            Clock clock) {
        this(foundations, sessions, messages, planner, planTransitions,
                executor, answer, pendingValidation, finalizationFailures,
                stepBlocked, null, frozenInputs, clock);
    }

    ProductChainModelProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ProjectChainPlannerProgression planner,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainExecutorProgression executor,
            ProductChainAnswerDeliveryProgression answer,
            ProductChainPendingItemModelInvoker pendingValidation,
            ProductChainFinalizationFailureProgression
                    finalizationFailures,
            ProductChainStepBlockedProgression stepBlocked,
            ProductChainModelFailureProgression modelFailures,
            FrozenInputPort frozenInputs,
            Clock clock) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.planTransitions = Objects.requireNonNull(
                planTransitions, "planTransitions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.answer = Objects.requireNonNull(answer, "answer");
        this.pendingValidation = pendingValidation;
        this.finalizationFailures = finalizationFailures;
        this.stepBlocked = stepBlocked;
        this.modelFailures = modelFailures;
        this.frozenInputs = Objects.requireNonNull(
                frozenInputs, "frozenInputs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProductChainTaskProgressionAdapter.ActionReceipt advance(
            ProductChainTaskProgressionAdapter.ModelCommand command) {
        Objects.requireNonNull(command, "command");
        ChainRecoveryRuntime.NextDirective directive = command.directive();
        ChainRecoveryRuntime.NextDirective formalRetry = null;
        if (directive.role() == ChainRole.REFLECTOR
                && directive.workState() == ChainWorkState.AWAITING_REVIEW
                && "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType())
                && modelFailures != null) {
            formalRetry = modelFailures.formalRetryDirective(
                    command.taskId(), directive.sourceAuthorityRef())
                    .orElse(null);
        }
        if (formalRetry != null) {
            var selectedInstruction = ProductChainNextRoleSelector
                    .currentModelInstruction(command.snapshot());
            var selectedTask = foundations.findTask(command.taskId())
                    .orElseThrow(() -> failure("CHAIN_MODEL_TASK_MISSING"));
            verifyInstruction(selectedTask, selectedInstruction);
            var selectedSession = sessions.findByIdAndUserId(
                            selectedTask.sessionId(), selectedTask.userId())
                    .filter(value -> Objects.equals(value.getProjectId(),
                            selectedTask.projectId()))
                    .orElseThrow(() -> failure(
                            "CHAIN_MODEL_SESSION_IDENTITY_INVALID"));
            if ("MODEL_FAILURE_STEP_BLOCK".equals(
                    formalRetry.sourceAuthorityType())) {
                modelFailures.invokeExecutorReview(
                        selectedSession, selectedTask,
                        selectedInstruction, formalRetry, clock.instant());
            } else if ("ACTION_RECEIPT_STEP_BLOCK".equals(
                    formalRetry.sourceAuthorityType())) {
                modelFailures.invokeActionFailureReview(
                        selectedSession, selectedTask,
                        selectedInstruction, formalRetry, clock.instant());
            } else {
                modelFailures.invokeContextReview(
                        selectedSession, selectedTask,
                        selectedInstruction, formalRetry, clock.instant());
            }
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction.model(
                            directive));
        }
        if (directive.role() == ChainRole.REFLECTOR
                && directive.workState() == ChainWorkState.AWAITING_REVIEW
                && ("MODEL_FAILURE_STEP_BLOCK".equals(
                directive.sourceAuthorityType())
                || "ACTION_RECEIPT_STEP_BLOCK".equals(
                directive.sourceAuthorityType())
                || "CONTEXT_BUILD_FAILURE".equals(
                directive.sourceAuthorityType()))) {
            if (modelFailures == null) {
                throw failure("CHAIN_MODEL_FAILURE_REVIEW_OWNER_MISSING");
            }
            var selectedInstruction = ProductChainNextRoleSelector
                    .currentModelInstruction(command.snapshot());
            var selectedTask = foundations.findTask(command.taskId())
                    .orElseThrow(() -> failure("CHAIN_MODEL_TASK_MISSING"));
            verifyInstruction(selectedTask, selectedInstruction);
            var selectedSession = sessions.findByIdAndUserId(
                            selectedTask.sessionId(), selectedTask.userId())
                    .filter(value -> Objects.equals(value.getProjectId(),
                            selectedTask.projectId()))
                    .orElseThrow(() -> failure(
                            "CHAIN_MODEL_SESSION_IDENTITY_INVALID"));
            if ("MODEL_FAILURE_STEP_BLOCK".equals(
                    directive.sourceAuthorityType())) {
                modelFailures.invokeExecutorReview(
                        selectedSession, selectedTask,
                        selectedInstruction, directive, clock.instant());
            } else if ("ACTION_RECEIPT_STEP_BLOCK".equals(
                    directive.sourceAuthorityType())) {
                modelFailures.invokeActionFailureReview(
                        selectedSession, selectedTask,
                        selectedInstruction, directive, clock.instant());
            } else {
                modelFailures.invokeContextReview(
                        selectedSession, selectedTask,
                        selectedInstruction, directive, clock.instant());
            }
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction.model(
                            directive));
        }
        if (directive.role() == ChainRole.REFLECTOR
                && directive.workState() == ChainWorkState.AWAITING_REVIEW
                && "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType())
                && stepBlocked != null
                && stepBlocked.handles(command.taskId(),
                directive.sourceAuthorityRef())) {
            var selectedInstruction = ProductChainNextRoleSelector
                    .currentModelInstruction(command.snapshot());
            var selectedTask = foundations.findTask(command.taskId())
                    .orElseThrow(() -> failure("CHAIN_MODEL_TASK_MISSING"));
            verifyInstruction(selectedTask, selectedInstruction);
            var selectedSession = sessions.findByIdAndUserId(
                            selectedTask.sessionId(), selectedTask.userId())
                    .filter(value -> Objects.equals(value.getProjectId(),
                            selectedTask.projectId()))
                    .orElseThrow(() -> failure(
                            "CHAIN_MODEL_SESSION_IDENTITY_INVALID"));
            stepBlocked.invoke(selectedSession, selectedTask,
                    selectedInstruction, directive, clock.instant());
            return new ProductChainTaskProgressionAdapter.ActionReceipt(
                    ProductChainTaskProgressionAdapter.SelectedAction.model(
                            directive));
        }
        ProductChainRecoverySource.FrozenModelInput frozen = frozenInputs.read(
                command.snapshot(), directive);
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.taskId())
                .filter(value -> value.taskId().equals(command.taskId()))
                .orElseThrow(() -> failure("CHAIN_MODEL_TASK_MISSING"));
        ChainPersistenceRecords.InstructionRecord instruction =
                frozen.instruction();
        verifyInstruction(task, instruction);
        AgentSession session = sessions.findByIdAndUserId(
                        task.sessionId(), task.userId())
                .filter(value -> Objects.equals(value.getProjectId(),
                        task.projectId()))
                .orElseThrow(() -> failure(
                        "CHAIN_MODEL_SESSION_IDENTITY_INVALID"));
        dispatch(directive, session, task, instruction,
                frozen.planBinding(), frozen.step(), frozen.candidate(),
                frozen.routeDecisionId(), frozen.candidateResultId(),
                frozen.pendingGapId(), frozen.explicitReplacementReintake());
        return new ProductChainTaskProgressionAdapter.ActionReceipt(
                ProductChainTaskProgressionAdapter.SelectedAction.model(
                        directive));
    }

    private void dispatch(
            ChainRecoveryRuntime.NextDirective directive,
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ProductChainRecoverySource.FrozenStepInput step,
            ProductChainRecoverySource.FrozenCandidateInput candidate,
            String routeDecisionId,
            String candidateResultId,
            String pendingGapId,
            boolean explicitReplacementReintake) {
        if (directive.role() == ChainRole.REFLECTOR
                && directive.workState() == ChainWorkState.AWAITING_REVIEW
                && ("FINALIZATION_CHECK".equals(
                directive.sourceAuthorityType())
                || "PUBLISH_FAILURE".equals(
                directive.sourceAuthorityType()))) {
            if (finalizationFailures == null) {
                throw failure(
                        "CHAIN_FINALIZATION_FAILURE_MODEL_OWNER_MISSING");
            }
            finalizationFailures.invoke(session, task, instruction,
                    directive, clock.instant());
            return;
        }
        if (directive.workState()
                == ChainWorkState.VALIDATING_PENDING_ITEM) {
            if (pendingValidation == null) {
                throw failure("CHAIN_PENDING_ITEM_MODEL_ENTRY_MISSING: "
                        + directive.role());
            }
            boolean retry = "PROPOSAL_STATE".equals(
                    directive.sourceAuthorityType());
            if (!retry && !"PENDING_ITEM".equals(
                    directive.sourceAuthorityType())) {
                throw failure(
                        "CHAIN_PENDING_ITEM_MODEL_SOURCE_INVALID");
            }
            pendingValidation.invoke(session, task, instruction, binding,
                    step, candidate, directive.role(),
                    retry ? pendingGapId
                            : directive.sourceAuthorityRef(), clock.instant());
            return;
        }
        if (directive.role() == ChainRole.PLANNER) {
            advancePlanner(directive, session, task, instruction,
                    instructionBody(task, instruction),
                    binding, candidate, explicitReplacementReintake);
            return;
        }
        if (directive.role() == ChainRole.EXECUTOR
                && directive.workState() == ChainWorkState.EXECUTING) {
            executor.advance(session, task, instruction,
                    instructionBody(task, instruction),
                    transition(task, binding), clock.instant());
            return;
        }
        if (directive.role() == ChainRole.ANSWER
                && directive.workState()
                == ChainWorkState.DIRECT_ANSWERING
                && ("ROUTE_DECISION".equals(
                directive.sourceAuthorityType())
                || "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType()))) {
            String routeRef = "PROPOSAL_STATE".equals(
                    directive.sourceAuthorityType())
                    ? routeDecisionId : directive.sourceAuthorityRef();
            if (routeRef == null) {
                throw failure("CHAIN_MODEL_DIRECT_ROUTE_MISSING");
            }
            answer.invokeDirectAnswer(session, task, instruction,
                    routeRef, clock.instant());
            return;
        }
        if (directive.role() == ChainRole.ANSWER
                && (directive.workState() == ChainWorkState.WAITING_USER
                || directive.workState()
                == ChainWorkState.WAITING_PERMISSION)
                && ("PENDING_ITEM".equals(
                directive.sourceAuthorityType())
                || "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType()))) {
            String gapRef = "PROPOSAL_STATE".equals(
                    directive.sourceAuthorityType())
                    ? pendingGapId
                    : directive.sourceAuthorityRef();
            if (gapRef == null) {
                throw failure("CHAIN_MODEL_PENDING_ITEM_MISSING");
            }
            answer.invokePendingItemAnswer(session, task, instruction,
                    gapRef, clock.instant());
            return;
        }
        if (directive.role() == ChainRole.REFLECTOR
                && directive.workState() == ChainWorkState.AWAITING_REVIEW) {
            String candidateRef = "CANDIDATE_STEP_RESULT".equals(
                    directive.sourceAuthorityType())
                    ? directive.sourceAuthorityRef() : candidateResultId;
            if (candidateRef == null) {
                throw failure("CHAIN_MODEL_CANDIDATE_RESULT_MISSING");
            }
            executor.invokeReflectorReview(session, task, instruction,
                    transition(task, binding),
                    candidateRef, clock.instant());
            return;
        }
        if (directive.role() == ChainRole.ANSWER
                && (directive.workState() == ChainWorkState.DELIVERING
                || directive.workState() == ChainWorkState.TERMINAL)
                && ("TASK_OUTCOME".equals(
                directive.sourceAuthorityType())
                || "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType()))) {
            answer.advancePersistentAnswer(session, task, instruction,
                    clock.instant());
            return;
        }
        throw failure("CHAIN_MODEL_ENTRY_MISSING: " + directive.role()
                + "/" + directive.workState());
    }

    private void advancePlanner(
            ChainRecoveryRuntime.NextDirective directive,
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ProductChainRecoverySource.FrozenCandidateInput candidate,
            boolean explicitReplacementReintake) {
        if (directive.workState()
                == ChainWorkState.CLASSIFYING_INSTRUCTION
                && ("INSTRUCTION".equals(
                directive.sourceAuthorityType())
                || "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType()))) {
            planner.advance(session, task, instruction, body,
                    plannerRelation(instruction.relationKind(),
                            explicitReplacementReintake),
                    clock.instant());
            return;
        }
        if (directive.workState() == ChainWorkState.PLANNING
                && "TASK".equals(directive.sourceAuthorityType())) {
            planner.advance(session, task, instruction, body,
                    plannerRelation(instruction.relationKind(),
                            explicitReplacementReintake),
                    clock.instant());
            return;
        }
        if (directive.workState() == ChainWorkState.PLANNING
                && binding != null) {
            planner.advanceRevision(session, task, instruction, body,
                    binding, revisionCandidate(candidate),
                    directive.sourceAuthorityType(),
                    directive.sourceAuthorityRef(), clock.instant());
            return;
        }
        if (directive.workState() == ChainWorkState.PLANNING
                && "ROUTE_DECISION".equals(
                directive.sourceAuthorityType())) {
            planner.advancePersistentPlan(session, task, instruction, body,
                    directive.sourceAuthorityRef(), clock.instant());
            return;
        }
        if (directive.workState() == ChainWorkState.PLANNING
                && "PROPOSAL_STATE".equals(
                directive.sourceAuthorityType())) {
            planner.advance(session, task, instruction, body,
                    plannerRelation(instruction.relationKind(),
                            explicitReplacementReintake),
                    clock.instant());
            return;
        }
        throw failure("CHAIN_PLANNER_RECOVERY_ENTRY_MISSING: "
                + directive.sourceAuthorityType());
    }

    private static ProjectChainPlannerProgression.RevisionCandidate
            revisionCandidate(
                    ProductChainRecoverySource.FrozenCandidateInput candidate) {
        return candidate == null ? null
                : new ProjectChainPlannerProgression.RevisionCandidate(
                candidate.workspaceId(), candidate.artifactId(),
                candidate.candidateFingerprint());
    }

    private ProductChainPlanTransitionDriver.Result transition(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        if (binding == null || !task.taskId().equals(binding.taskId())) {
            throw failure("CHAIN_MODEL_PLAN_BINDING_MISSING");
        }
        return planTransitions.recoverCompletedBinding(
                task.taskId(), binding.planBindingId());
    }

    private String instructionBody(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction) {
        if (instruction.messageId() == null) {
            throw failure("CHAIN_MODEL_INSTRUCTION_BODY_MISSING");
        }
        var message = messages.findById(instruction.messageId())
                .filter(value -> Objects.equals(value.getSessionId(),
                        task.sessionId()))
                .filter(value -> Objects.equals(value.getUserId(),
                        task.userId()))
                .orElseThrow(() -> failure(
                        "CHAIN_MODEL_INSTRUCTION_MESSAGE_INVALID"));
        String body = Objects.toString(message.getContent(), "");
        if (!sha256(body).equals(instruction.bodySha256())) {
            throw failure("CHAIN_MODEL_INSTRUCTION_BODY_DIGEST_INVALID");
        }
        return body;
    }

    private void verifyInstruction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction) {
        if (instruction.sessionId() != task.sessionId()) {
            throw failure("CHAIN_MODEL_INSTRUCTION_IDENTITY_INVALID");
        }
    }

    private static ProjectChainPlannerProgression.ChainInstructionRelationValue
            plannerRelation(
                    ChainInstructionRelation relation,
                    boolean explicitReplacementReintake) {
        return switch (relation) {
            case INITIAL -> ProjectChainPlannerProgression
                    .ChainInstructionRelationValue.INITIAL;
            case REPLACEMENT -> {
                if (!explicitReplacementReintake) {
                    throw failure(
                            "CHAIN_PLANNER_REPLACEMENT_AUTHORITY_MISSING");
                }
                yield ProjectChainPlannerProgression
                        .ChainInstructionRelationValue.INITIAL;
            }
            case SUPPLEMENT -> ProjectChainPlannerProgression
                    .ChainInstructionRelationValue.SUPPLEMENT;
            case CORRECTION -> ProjectChainPlannerProgression
                    .ChainInstructionRelationValue.CORRECTION;
            case CANCEL, ANSWER_TO_PENDING_ITEM -> throw failure(
                    "CHAIN_PLANNER_INSTRUCTION_RELATION_UNSUPPORTED: "
                            + relation);
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    @FunctionalInterface
    interface FrozenInputPort {
        ProductChainRecoverySource.FrozenModelInput read(
                ChainRecoveryRuntime.RecoverySnapshot snapshot,
                ChainRecoveryRuntime.NextDirective directive);
    }
}
