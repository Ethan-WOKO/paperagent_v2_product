package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextCommand;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextComposer;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainAuthorityTime;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPlanBindingWriter;
import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.route.ChainPlanCommitRuntime;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.step.ChainStepRuntime;
import io.paperagent.v2.chain.step.ChainStepStateMachine;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.chain.transition.ChainApplicabilityRuntime;
import io.paperagent.v2.chain.transition.ChainApplicabilityAuthorityPort;
import io.paperagent.v2.chain.route.ChainRouteRuntime;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextCompositionOutcome;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextLeaseAttempt;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextReady;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Completes the initial PERSISTENT_PLAN -> PLAN_CHANGE cut.  This class is a
 * transition driver only: stable Plan, execution-start, and Step facts are
 * delegated to their existing narrow authorities.
 */
@Component
public final class ProductChainPlanTransitionDriver {
    private final ChainFoundationRepository foundations;
    private final ChainModelRepository models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ChainFinalizationRepository finalization;
    private final ProductChainPlanCommitAdapter plans;
    private final ProductChainStepAuthorityAdapter steps;
    private final ProductChainExecutionStartAdapter executionStarts;
    private final ProductChainContextRepositoryAdapter contexts;
    private final AuthenticatedAgentTurnPlanExecutionContextComposer executionContexts;
    private final LeaseRepository leases;
    private final ChainPlanCommitRuntime planRuntime;

    public ProductChainPlanTransitionDriver(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainPlanCommitAdapter plans,
            ProductChainStepAuthorityAdapter steps,
            ProductChainExecutionStartAdapter executionStarts,
            ProductChainContextRepositoryAdapter contexts,
            AuthenticatedAgentTurnPlanExecutionContextComposer executionContexts,
            LeaseRepository leases) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.executionStarts = Objects.requireNonNull(executionStarts,
                "executionStarts");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.executionContexts = Objects.requireNonNull(executionContexts,
                "executionContexts");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.planRuntime = new ChainPlanCommitRuntime(
                foundations, models, workflow, workflow, plans,
                new ChainInstructionStateReader(foundations, workflow,
                        finalization),
                (taskId, proposalId, type, ref) -> { });
    }

    public Result commitInitial(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            PlannerPayload.PersistentPlan payload,
            String routeDecisionId,
            Instant committedAt) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(payload, "payload");
        routeDecisionId = required(routeDecisionId, "routeDecisionId");
        Objects.requireNonNull(committedAt, "committedAt");
        String digest = sha256(task.taskId() + "\0" + instruction.instructionId()
                + "\0" + proposal.proposalId() + "\0" + routeDecisionId
                + "\0" + proposal.payload().sha256());
        final String stableRouteDecisionId = routeDecisionId;
        String bindingEventId = "plan-binding-authority." + digest;
        Holder holder = new Holder();
        ChainCompositeTransitionRuntime transitions =
                new ChainCompositeTransitionRuntime(workflow, workflow,
                        query -> verifyAuthority(query, holder));
        ChainCompositeTransitionRuntime.RecoveryOutcome outcome =
                transitions.resume(new ChainCompositeTransitionRuntime
                        .TransitionRequest(
                                io.paperagent.v2.chain.ChainTransitionType.PLAN_CHANGE,
                                task.taskId(), stableRouteDecisionId, digest,
                                ChainCompositeTransitionRuntime.Branch.STANDARD,
                                committedAt), command -> commitStage(command,
                                        task, instruction, proposal, payload,
                                        stableRouteDecisionId, bindingEventId,
                                        committedAt, holder));
        if (!outcome.complete()) {
            throw failure("CHAIN_PLAN_CHANGE_INCOMPLETE");
        }
        ChainPersistenceRecords.TransitionStageRecord complete = workflow
                .findTransitionStages(outcome.transition().transitionId()).stream()
                .filter(value -> value.stageCode()
                        == io.paperagent.v2.chain.ChainTransitionStage.COMPLETE)
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_PLAN_CHANGE_COMPLETE_STAGE_MISSING"));
        return new Result(outcome.transition().transitionId(),
                bindingFor(outcome.transition().transitionId()).orElse(null),
                complete.eventId(), holder.stepEvent);
    }

    /** Commits a Planner PLAN_REVISION through the existing PLAN_CHANGE owner. */
    public Result commitRevision(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            PlannerPayload.PlanRevision payload,
            Instant committedAt,
            ChainRouteRuntime.ProposalOfficialBinder proposalBinder) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(committedAt, "committedAt");
        Objects.requireNonNull(proposalBinder, "proposalBinder");
        String digest = sha256(task.taskId() + "\0"
                + instruction.instructionId() + "\0"
                + proposal.proposalId() + "\0"
                + payload.triggerDecisionOrGapRef() + "\0"
                + payload.oldRevisionRef() + "\0"
                + proposal.payload().sha256());
        String bindingEventId = "plan-binding-authority." + digest;
        Holder holder = new Holder();
        ChainPlanCommitRuntime revisions = new ChainPlanCommitRuntime(
                foundations, models, workflow, workflow, plans,
                new ChainInstructionStateReader(foundations, workflow,
                        finalization), proposalBinder);
        ChainCompositeTransitionRuntime transitions =
                new ChainCompositeTransitionRuntime(workflow, workflow,
                        query -> verifyAuthority(query, holder));
        ChainCompositeTransitionRuntime.RecoveryOutcome outcome =
                transitions.resume(new ChainCompositeTransitionRuntime
                        .TransitionRequest(
                        io.paperagent.v2.chain.ChainTransitionType.PLAN_CHANGE,
                        task.taskId(), payload.triggerDecisionOrGapRef(),
                        digest,
                        ChainCompositeTransitionRuntime.Branch.STANDARD,
                        committedAt), command -> commitRevisionStage(
                        command, task, instruction, proposal, payload,
                        bindingEventId, committedAt, revisions, holder));
        if (!outcome.complete()) {
            throw failure("CHAIN_PLAN_REVISION_CHANGE_INCOMPLETE");
        }
        var complete = outcome.committedStages().stream()
                .filter(value -> value.stageCode()
                        == io.paperagent.v2.chain.ChainTransitionStage.COMPLETE)
                .findFirst().orElseThrow(() -> failure(
                        "CHAIN_PLAN_REVISION_COMPLETE_STAGE_MISSING"));
        return new Result(outcome.transition().transitionId(),
                bindingFor(outcome.transition().transitionId()).orElse(null),
                complete.eventId(), holder.stepEvent);
    }

    /** Validates dynamic active-Step identity before a revision proposal lands. */
    void validateRevisionDraft(
            ChainPersistenceRecords.PlanBindingRecord currentPlan,
            PlannerPayload.PlanRevision payload) {
        plans.validateActiveStepReplacementIdentity(currentPlan, payload);
    }

    /**
     * Reads the sole completed PLAN_CHANGE result for a Task. This recovery
     * path is read-only: it never recreates a Plan, Step, stage, or binding.
     */
    public Result recoverCompleted(String taskId) {
        taskId = required(taskId, "taskId");
        final String stableTaskId = taskId;
        foundations.findTask(stableTaskId)
                .filter(value -> value.taskId().equals(stableTaskId))
                .orElseThrow(() -> failure("CHAIN_PLAN_RECOVERY_TASK_MISSING"));
        List<RecoveredResult> completed = new java.util.ArrayList<>();
        for (var binding : workflow.findPlanBindings(stableTaskId)) {
            if (!binding.taskId().equals(stableTaskId)) {
                throw failure("CHAIN_PLAN_RECOVERY_CROSS_TASK_BINDING");
            }
            RecoveredResult result = completedResult(stableTaskId, binding);
            if (result != null) {
                completed.add(result);
            }
        }
        RecoveredResult recovered = exactlyOne(completed,
                "CHAIN_PLAN_RECOVERY_RESULT_NOT_UNIQUE");
        return new Result(recovered.transition().transitionId(),
                recovered.binding(), recovered.complete().eventId(),
                recovered.firstStep());
    }

    /** Recovers one exact completed PLAN_CHANGE by its formal PlanBinding. */
    public Result recoverCompletedBinding(
            String taskId, String planBindingId) {
        taskId = required(taskId, "taskId");
        planBindingId = required(planBindingId, "planBindingId");
        final String stableTaskId = taskId;
        final String stableBindingId = planBindingId;
        var binding = exactlyOne(workflow.findPlanBindings(taskId).stream()
                        .filter(value -> value.planBindingId().equals(
                                stableBindingId))
                        .toList(),
                "CHAIN_PLAN_RECOVERY_BINDING_NOT_UNIQUE");
        RecoveredResult recovered = completedResult(stableTaskId, binding);
        if (recovered == null) {
            throw failure("CHAIN_PLAN_RECOVERY_TRANSITION_INCOMPLETE");
        }
        return new Result(recovered.transition().transitionId(),
                recovered.binding(), recovered.complete().eventId(),
                recovered.firstStep());
    }

    private RecoveredResult completedResult(
            String taskId,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        if (binding.planBindingId() == null
                || binding.planBindingId().isBlank()
                || binding.transitionId() == null
                || binding.transitionId().isBlank()
                || binding.planRevisionId() == null
                || binding.planRevisionId().isBlank()) {
            throw failure("CHAIN_PLAN_RECOVERY_BINDING_IDENTITY_INVALID");
        }
        var transition = workflow.findTransition(binding.transitionId())
                .orElseThrow(() -> failure(
                        "CHAIN_PLAN_RECOVERY_TRANSITION_MISSING"));
        if (!transition.taskId().equals(taskId)
                || transition.transitionType()
                != io.paperagent.v2.chain.ChainTransitionType.PLAN_CHANGE
                || !Objects.equals(binding.transitionId(),
                transition.transitionId())) {
            throw failure("CHAIN_PLAN_RECOVERY_TRANSITION_IDENTITY_INVALID");
        }
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .toList();
        List<io.paperagent.v2.chain.ChainTransitionStage> prefix =
                new java.util.ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            if (!stage.taskId().equals(taskId)
                    || !stage.transitionId().equals(
                    transition.transitionId())
                    || stage.stageOrdinal() != index) {
                throw failure("CHAIN_PLAN_RECOVERY_STAGE_IDENTITY_INVALID");
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_PLAN_RECOVERY_STAGE_PREFIX_INVALID");
            }
            prefix.add(stage.stageCode());
        }
        if (!transition.transitionType().isCompleteSequence(prefix)) {
            return null;
        }
        requireNoAuthority(stages.get(0),
                "CHAIN_PLAN_RECOVERY_OPEN_AUTHORITY_INVALID");
        requireSuccessor(stages.get(1), "PLAN_BINDING",
                binding.planBindingId(),
                "CHAIN_PLAN_RECOVERY_BINDING_STAGE_INVALID");
        requireOptionalSuccessor(stages.get(2), "RESULT_APPLICABILITY",
                "CHAIN_PLAN_RECOVERY_APPLICABILITY_STAGE_INVALID");
        requireOptionalSuccessor(stages.get(3), "STEP_EVENT",
                "CHAIN_PLAN_RECOVERY_SUPERSEDE_STAGE_INVALID");
        requireSuccessor(stages.get(4), "STEP_EVENT", null,
                "CHAIN_PLAN_RECOVERY_ACTIVATION_STAGE_INVALID");
        requireNoAuthority(stages.get(5),
                "CHAIN_PLAN_RECOVERY_COMPLETE_AUTHORITY_INVALID");
        List<ChainStepAuthorityPort.StepEvent> activationEvents = steps
                .findStepEvents(taskId, binding.planRevisionId()).stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .toList();
        List<ChainStepAuthorityPort.StepEvent> sourceEvents = activationEvents
                .stream()
                .filter(value -> value.command().taskId().equals(taskId)
                        && value.command().planRevisionId().equals(
                        binding.planRevisionId())
                        && value.command().transitionId().equals(
                        transition.transitionId())
                        && value.command().sourceDecisionId().equals(
                        transition.sourceDecisionId()))
                .toList();
        var first = exactlyOne(sourceEvents,
                "CHAIN_PLAN_RECOVERY_FIRST_STEP_NOT_UNIQUE");
        long firstActivationSequence = activationEvents.stream()
                .mapToLong(ChainStepAuthorityPort.StepEvent::authoritySequence)
                .min().orElseThrow(() -> failure(
                        "CHAIN_PLAN_RECOVERY_FIRST_STEP_MISSING"));
        if (first.authoritySequence() != firstActivationSequence
                || !first.command().eventId().equals(
                first.command().activationEventId())
                || !stages.get(4).successorAuthorityRef().equals(
                first.command().eventId())) {
            throw failure("CHAIN_PLAN_RECOVERY_FIRST_STEP_IDENTITY_INVALID");
        }
        return new RecoveredResult(
                transition, binding, stages.get(5), first);
    }

    private static void requireNoAuthority(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String errorCode) {
        if (stage.predecessorAuthorityType() != null
                || stage.predecessorAuthorityRef() != null
                || stage.successorAuthorityType() != null
                || stage.successorAuthorityRef() != null) {
            throw failure(errorCode);
        }
    }

    private static void requireSuccessor(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String type,
            String expectedRef,
            String errorCode) {
        if (stage.predecessorAuthorityType() != null
                || stage.predecessorAuthorityRef() != null
                || !type.equals(stage.successorAuthorityType())
                || stage.successorAuthorityRef() == null
                || (expectedRef != null
                && !expectedRef.equals(stage.successorAuthorityRef()))) {
            throw failure(errorCode);
        }
    }

    private static void requireOptionalSuccessor(
            ChainPersistenceRecords.TransitionStageRecord stage,
            String type,
            String errorCode) {
        boolean empty = stage.predecessorAuthorityType() == null
                && stage.predecessorAuthorityRef() == null
                && stage.successorAuthorityType() == null
                && stage.successorAuthorityRef() == null;
        boolean successor = stage.predecessorAuthorityType() == null
                && stage.predecessorAuthorityRef() == null
                && type.equals(stage.successorAuthorityType())
                && stage.successorAuthorityRef() != null;
        if (!empty && !successor) {
            throw failure(errorCode);
        }
    }

    /**
     * Reuses a successor already committed by this owner after a crash before
     * the corresponding transition-stage marker was appended.
     */
    public ChainCompositeTransitionRuntime.StageCommitResult
            recoverCommittedStage(
                    ChainCompositeTransitionRuntime.StageCommand command) {
        Objects.requireNonNull(command, "command");
        var transition = command.transition();
        if (transition.transitionType()
                != io.paperagent.v2.chain.ChainTransitionType.PLAN_CHANGE) {
            throw failure("CHAIN_PLAN_RECOVERY_TRANSITION_TYPE_INVALID");
        }
        return switch (command.stage()) {
            case TASKFRAME_PLAN_COMMITTED -> {
                var binding = recoveryBinding(transition.transitionId());
                yield ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("PLAN_BINDING", binding.planBindingId());
            }
            case APPLICABILITY_COMMITTED -> {
                List<ChainPersistenceRecords.ResultApplicabilityRecord>
                        decisions = workflow.findApplicabilityDecisions(
                                transition.taskId()).stream()
                        .filter(value -> value.sourceType()
                                == ChainApplicability.SourceType.PLAN_REVISION)
                        .filter(value -> value.sourceDecisionId().equals(
                                transition.transitionId()))
                        .sorted(Comparator.comparing(
                                ChainPersistenceRecords
                                        .ResultApplicabilityRecord
                                        ::applicabilityId))
                        .toList();
                yield decisions.isEmpty()
                        ? ChainCompositeTransitionRuntime.StageCommitResult
                        .none()
                        : ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("RESULT_APPLICABILITY",
                                decisions.get(0).applicabilityId());
            }
            case OLD_STEP_SUPERSEDED_OR_NONE -> {
                List<ChainStepAuthorityPort.StepEvent> superseded = workflow
                        .findPlanBindings(transition.taskId()).stream()
                        .flatMap(value -> steps.findStepEvents(
                                transition.taskId(),
                                value.planRevisionId()).stream())
                        .filter(value -> value.command().transitionId()
                                .equals(transition.transitionId()))
                        .filter(value -> value.command().eventKind()
                                == ChainStepAuthorityPort.StepEventKind
                                .SUPERSEDED_BY_REPLAN)
                        .toList();
                if (superseded.size() > 1) {
                    throw failure(
                            "CHAIN_PLAN_SUPERSEDE_AUTHORITY_AMBIGUOUS");
                }
                yield superseded.isEmpty()
                        ? ChainCompositeTransitionRuntime.StageCommitResult
                        .none()
                        : ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("STEP_EVENT", superseded.get(0)
                                .command().eventId());
            }
            case NEW_STEP_ACTIVATED -> {
                var binding = recoveryBinding(transition.transitionId());
                List<ChainStepAuthorityPort.StepEvent> events = steps
                        .findStepEvents(transition.taskId(),
                                binding.planRevisionId()).stream()
                        .filter(value -> value.command().eventKind()
                                == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                        .filter(value -> transition.transitionId().equals(
                                value.command().transitionId()))
                        .filter(value -> transition.sourceDecisionId().equals(
                                value.command().sourceDecisionId()))
                        .toList();
                if (events.size() > 1) {
                    throw failure(
                            "CHAIN_PLAN_FIRST_STEP_AUTHORITY_MISSING");
                }
                ChainStepAuthorityPort.StepEvent event;
                if (events.size() == 1) {
                    event = events.get(0);
                } else {
                    if (binding.planRevisionNumber() == 1L) {
                        executionStarts.ensureStarted(binding);
                        var task = foundations.findTask(transition.taskId())
                                .orElseThrow(() -> failure(
                                        "CHAIN_PLAN_RECOVERY_TASK_MISSING"));
                        composeExecutionContext(task, binding);
                    }
                    var activation = stepRuntime().activateNext(
                            transition.taskId(), binding.planRevisionId(),
                            transition.sourceDecisionId(),
                            transition.transitionId(),
                            ChainAuthorityTime.normalize(
                                    transition.createdAt()));
                    if (activation.kind()
                            != ChainStepStateMachine.ActivationKind.ACTIVATED
                            || activation.append() == null) {
                        throw failure(
                                "CHAIN_PLAN_FIRST_STEP_AUTHORITY_MISSING");
                    }
                    event = activation.append().value();
                }
                yield ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("STEP_EVENT", event.command().eventId());
            }
            case OPEN, COMPLETE -> throw failure(
                    "CHAIN_PLAN_RECOVERY_STAGE_NOT_COMMITTED");
            default -> throw failure(
                    "CHAIN_PLAN_RECOVERY_STAGE_UNSUPPORTED");
        };
    }

    private ChainPersistenceRecords.PlanBindingRecord recoveryBinding(
            String transitionId) {
        var transition = workflow.findTransition(transitionId)
                .orElseThrow(() -> failure(
                        "CHAIN_PLAN_CHANGE_NOT_FOUND"));
        List<ChainPersistenceRecords.PlanBindingRecord> bindings = workflow
                .findPlanBindings(transition.taskId()).stream()
                .filter(value -> transitionId.equals(value.transitionId()))
                .toList();
        return exactlyOne(bindings,
                "CHAIN_PLAN_BINDING_AUTHORITY_MISSING");
    }

    private static <T> T exactlyOne(List<T> values, String errorCode) {
        if (values.size() != 1) {
            throw failure(errorCode);
        }
        return values.get(0);
    }

    private ChainCompositeTransitionRuntime.StageCommitResult commitStage(
            ChainCompositeTransitionRuntime.StageCommand command,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            PlannerPayload.PersistentPlan payload,
            String routeDecisionId,
            String bindingEventId,
            Instant committedAt,
            Holder holder) {
        switch (command.stage()) {
            case TASKFRAME_PLAN_COMMITTED -> {
                ChainPersistenceRecords.PlanBindingRecord binding = planRuntime
                        .commitPersistent(new ChainPlanCommitRuntime.CommitRequest(
                                task.taskId(), instruction.instructionId(),
                                proposal.proposalId(), bindingEventId,
                                command.transition().transitionId(), committedAt),
                                payload);
                holder.binding = binding;
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("PLAN_BINDING", binding.planBindingId());
            }
            case APPLICABILITY_COMMITTED, OLD_STEP_SUPERSEDED_OR_NONE -> {
                return ChainCompositeTransitionRuntime.StageCommitResult.none();
            }
            case NEW_STEP_ACTIVATED -> {
                ChainPersistenceRecords.PlanBindingRecord binding = holder.binding;
                if (binding == null) {
                    binding = bindingFor(command.transition().transitionId())
                            .orElseThrow(() -> failure(
                                    "CHAIN_PLAN_BINDING_NOT_FOUND"));
                    holder.binding = binding;
                }
                executionStarts.ensureStarted(binding);
                composeExecutionContext(task, binding);
                // Formal sequence, not fabricated wall-clock offsets, orders
                // execution start and Step activation.
                Instant stepActivatedAt = ChainAuthorityTime.normalize(
                        command.transition().createdAt());
                ChainStepRuntime stepRuntime = new ChainStepRuntime(
                        new ChainStepStateMachine(steps, workflow, foundations,
                                models, contexts), workflow, finalization,
                        fact -> {
                            throw new IllegalStateException(
                                    "CHAIN_PLAN_READINESS_WRITER_NOT_APPLICABLE");
                        }, query -> {
                            throw new IllegalStateException(
                                "CHAIN_PLAN_READINESS_GATE_NOT_APPLICABLE");
                        }, query -> { });
                ChainStepStateMachine.ActivationOutcome activation =
                        stepRuntime.activateNext(
                                task.taskId(), binding.planRevisionId(),
                                routeDecisionId,
                                command.transition().transitionId(),
                                        stepActivatedAt);
                if (activation.kind()
                        != ChainStepStateMachine.ActivationKind.ACTIVATED
                        || activation.append() == null) {
                    throw failure("CHAIN_PLAN_FIRST_STEP_NOT_ACTIVATED");
                }
                holder.stepEvent = activation.append().value();
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("STEP_EVENT",
                                activation.append().value().command().eventId());
            }
            case OPEN, COMPLETE ->
                    throw failure("CHAIN_PLAN_CHANGE_STAGE_NOT_COMMITTED");
            default -> throw failure("CHAIN_PLAN_CHANGE_STAGE_UNSUPPORTED");
        }
    }

    private ChainCompositeTransitionRuntime.StageCommitResult
            commitRevisionStage(
                    ChainCompositeTransitionRuntime.StageCommand command,
                    ChainPersistenceRecords.TaskRecord task,
                    ChainPersistenceRecords.InstructionRecord instruction,
                    ChainPersistenceRecords.ModelProposalRecord proposal,
                    PlannerPayload.PlanRevision payload,
                    String bindingEventId,
                    Instant committedAt,
                    ChainPlanCommitRuntime revisions,
                    Holder holder) {
        switch (command.stage()) {
            case TASKFRAME_PLAN_COMMITTED -> {
                var binding = revisions.commitRevision(
                        new ChainPlanCommitRuntime.CommitRequest(
                                task.taskId(), instruction.instructionId(),
                                proposal.proposalId(), bindingEventId,
                                command.transition().transitionId(),
                                committedAt), payload);
                holder.binding = binding;
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("PLAN_BINDING",
                                binding.planBindingId());
            }
            case APPLICABILITY_COMMITTED -> {
                var binding = revisionBinding(command, holder);
                if (payload.applicability().isEmpty()) {
                    return ChainCompositeTransitionRuntime.StageCommitResult
                            .none();
                }
                var plan = steps.findPlan(task.taskId(),
                                binding.planRevisionId())
                        .orElseThrow(() -> failure(
                                "CHAIN_PLAN_REVISION_SNAPSHOT_MISSING"));
                ChainApplicabilityRuntime runtime =
                        new ChainApplicabilityRuntime(workflow, workflow,
                                query -> new ChainApplicabilityAuthorityPort
                                        .SourceAuthority(
                                        ChainApplicability.SourceType
                                                .PLAN_REVISION,
                                        command.transition().transitionId(),
                                        query.targetIdentity(),
                                        command.transition().transitionId(),
                                        true));
                String first = null;
                for (var suggestion : payload.applicability()) {
                    var identity = new ChainApplicability.Identity(
                            suggestion.acceptedResultId(),
                            ChainApplicability.SourceType.PLAN_REVISION,
                            command.transition().transitionId(),
                            binding.taskFrameId(), binding.planId(),
                            binding.planRevisionId(),
                            plan.targetCandidateKey(),
                            binding.instructionId());
                    var applied = runtime.commit(
                            new ChainApplicabilityRuntime.CommitRequest(
                                    task.taskId(), identity,
                                    suggestion.outcome(),
                                    suggestion.reason(), committedAt));
                    if (first == null) {
                        first = applied.fact().applicabilityId();
                    }
                }
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("RESULT_APPLICABILITY", first);
            }
            case OLD_STEP_SUPERSEDED_OR_NONE -> {
                var previous = new ChainStepStateMachine(
                        steps, workflow, foundations, models, contexts)
                        .derive(task.taskId(), payload.oldRevisionRef());
                if (previous.activeStep().isEmpty()) {
                    return ChainCompositeTransitionRuntime.StageCommitResult
                            .none();
                }
                var active = previous.activeStep().orElseThrow();
                var runtime = stepRuntime();
                var event = runtime.supersedeForReplan(
                        new ChainStepStateMachine.StepTerminalCommand(
                                task.taskId(), payload.oldRevisionRef(),
                                active.stepId(), active.activationEventId(),
                                payload.triggerDecisionOrGapRef(),
                                command.transition().transitionId(),
                                committedAt)).value();
                holder.stepEventRefs.add(event.command().eventId());
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("STEP_EVENT",
                                event.command().eventId());
            }
            case NEW_STEP_ACTIVATED -> {
                var binding = revisionBinding(command, holder);
                var activation = stepRuntime().activateNext(
                        task.taskId(), binding.planRevisionId(),
                        payload.triggerDecisionOrGapRef(),
                        command.transition().transitionId(),
                        ChainAuthorityTime.normalize(committedAt));
                if (activation.kind()
                        != ChainStepStateMachine.ActivationKind.ACTIVATED
                        || activation.append() == null) {
                    throw failure("CHAIN_PLAN_REVISION_STEP_NOT_ACTIVATED");
                }
                holder.stepEvent = activation.append().value();
                holder.stepEventRefs.add(
                        holder.stepEvent.command().eventId());
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("STEP_EVENT", holder.stepEvent.command()
                                .eventId());
            }
            case OPEN, COMPLETE -> throw failure(
                    "CHAIN_PLAN_REVISION_STAGE_NOT_COMMITTED");
            default -> throw failure(
                    "CHAIN_PLAN_REVISION_STAGE_UNSUPPORTED");
        }
    }

    private ChainPersistenceRecords.PlanBindingRecord revisionBinding(
            ChainCompositeTransitionRuntime.StageCommand command,
            Holder holder) {
        if (holder.binding == null) {
            holder.binding = bindingFor(command.transition().transitionId())
                    .orElseThrow(() -> failure(
                            "CHAIN_PLAN_REVISION_BINDING_NOT_FOUND"));
        }
        return holder.binding;
    }

    private ChainStepRuntime stepRuntime() {
        return new ChainStepRuntime(
                new ChainStepStateMachine(steps, workflow, foundations,
                        models, contexts), workflow, finalization,
                fact -> { throw failure(
                        "CHAIN_PLAN_READINESS_WRITER_NOT_APPLICABLE"); },
                query -> { throw failure(
                        "CHAIN_PLAN_READINESS_GATE_NOT_APPLICABLE"); },
                query -> { });
    }

    private void composeExecutionContext(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        PlanId planId = new PlanId(binding.planId());
        var lease = leases.find(planId);
        if (lease == null || lease.outcome() != PersistenceOutcome.FOUND
                || lease.failure().isPresent() || lease.value().isEmpty()) {
            throw failure("CHAIN_EXECUTION_CONTEXT_LEASE_NOT_FOUND");
        }
        var authority = lease.value().orElseThrow();
        PlanExecutionContextCompositionOutcome outcome = executionContexts.compose(
                task.userId(), task.turnId(),
                new AuthenticatedAgentTurnPlanExecutionContextCommand(
                        Optional.of(new PlanExecutionContextLeaseAttempt(
                                authority.ownerId(), authority.leaseToken(),
                                authority.expiresAt()))));
        if (!(outcome instanceof PlanExecutionContextReady ready)
                || !ready.planId().equals(planId)) {
            throw failure("CHAIN_EXECUTION_CONTEXT_NOT_READY");
        }
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyAuthority(
            ChainCompositeTransitionRuntime.StageAuthorityQuery query,
            Holder holder) {
        var stage = query.stage();
        if (stage.stageCode()
                == io.paperagent.v2.chain.ChainTransitionStage.APPLICABILITY_COMMITTED
                && stage.successorAuthorityType() == null) {
            return ChainCompositeTransitionRuntime.AuthorityVerification
                    .verifiedEmpty();
        }
        if ("PLAN_BINDING".equals(stage.successorAuthorityType())) {
            boolean found = workflow.findPlanBindings(query.transition().taskId())
                    .stream().anyMatch(value ->
                            value.planBindingId().equals(
                                    stage.successorAuthorityRef())
                                    && query.transition().transitionId().equals(
                                    value.transitionId()));
            return found
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : unverified("CHAIN_PLAN_BINDING_AUTHORITY_MISSING");
        }
        if ("STEP_EVENT".equals(stage.successorAuthorityType())) {
            boolean found = holder.stepEventRefs.contains(
                    stage.successorAuthorityRef())
                    || holder.stepEvent != null
                    && holder.stepEvent.command().eventId().equals(
                    stage.successorAuthorityRef());
            if (!found) {
                found = workflow.findPlanBindings(query.transition().taskId())
                        .stream()
                        .filter(value -> query.transition().transitionId()
                                .equals(value.transitionId()))
                        .anyMatch(value -> steps.findStepEvents(
                                query.transition().taskId(),
                                value.planRevisionId()).stream().anyMatch(event ->
                                event.command().eventId().equals(
                                        stage.successorAuthorityRef())));
            }
            return found
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : unverified("CHAIN_STEP_EVENT_AUTHORITY_MISSING");
        }
        if ("RESULT_APPLICABILITY".equals(
                stage.successorAuthorityType())) {
            boolean found = workflow.findApplicabilityDecisions(
                            query.transition().taskId()).stream()
                    .anyMatch(value -> value.applicabilityId().equals(
                            stage.successorAuthorityRef())
                            && value.sourceDecisionId().equals(
                            query.transition().transitionId()));
            return found
                    ? ChainCompositeTransitionRuntime.AuthorityVerification
                    .verified()
                    : unverified(
                            "CHAIN_APPLICABILITY_AUTHORITY_MISSING");
        }
        return ChainCompositeTransitionRuntime.AuthorityVerification.verified();
    }

    private Optional<ChainPersistenceRecords.PlanBindingRecord> bindingFor(
            String transitionId) {
        return workflow.findPlanBindings(taskIdForTransition(transitionId)).stream()
                .filter(value -> value.transitionId().equals(transitionId))
                .max(Comparator.comparing(ChainPersistenceRecords.PlanBindingRecord
                        ::createdAt));
    }

    private String taskIdForTransition(String transitionId) {
        return workflow.findTransition(transitionId)
                .orElseThrow(() -> failure("CHAIN_PLAN_CHANGE_NOT_FOUND"))
                .taskId();
    }

    private static ChainCompositeTransitionRuntime.AuthorityVerification unverified(
            String code) {
        throw failure(code);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static final class Holder {
        private ChainPersistenceRecords.PlanBindingRecord binding;
        private ChainStepAuthorityPort.StepEvent stepEvent;
        private final java.util.Set<String> stepEventRefs =
                new java.util.HashSet<>();
    }

    private record RecoveredResult(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainPersistenceRecords.TransitionStageRecord complete,
            ChainStepAuthorityPort.StepEvent firstStep) {
    }

    public record Result(
            String transitionId,
            ChainPersistenceRecords.PlanBindingRecord planBinding,
            String completeEventId,
            ChainStepAuthorityPort.StepEvent firstStepEvent) {
        public Result {
            required(transitionId, "transitionId");
            Objects.requireNonNull(planBinding, "planBinding");
            required(completeEventId, "completeEventId");
        }
    }
}
