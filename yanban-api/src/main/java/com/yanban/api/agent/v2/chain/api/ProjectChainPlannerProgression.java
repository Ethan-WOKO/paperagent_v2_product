package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainAuthorityTime;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.DefaultChainContextManager;
import io.paperagent.v2.chain.instruction.ChainInstructionDispositionRuntime;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.ChainRoleOutputDecoder;
import io.paperagent.v2.chain.route.ChainRouteRuntime;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.GapValidation;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpointResolver;
import com.yanban.api.settings.UserSettingsService;
import io.paperagent.v2.chain.ChainPendingItemWriter;
import io.paperagent.v2.chain.ChainRouteDecisionWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Product bridge that turns one accepted Planner proposal into one formal cut. */
@Service
public final class ProjectChainPlannerProgression {
    private static final System.Logger LOG = System.getLogger(
            ProjectChainPlannerProgression.class.getName());
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProjectService projects;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final PlatformTransactionManager transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final io.paperagent.v2.chain.ChainFinalizationRepository finalization;
    private final ProductChainPlanTransitionDriver planTransitions;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;

    public ProjectChainPlannerProgression(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations,
            ProjectService projects,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            PlatformTransactionManager transactions,
            NamedParameterJdbcTemplate jdbc,
            io.paperagent.v2.chain.ChainFinalizationRepository finalization,
            ProductChainPlanTransitionDriver planTransitions,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity) {
        this.contexts = contexts;
        this.models = models;
        this.workflow = workflow;
        this.foundations = foundations;
        this.projects = projects;
        this.settings = settings;
        this.provider = provider;
        this.transactions = transactions;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.planTransitions = Objects.requireNonNull(planTransitions,
                "planTransitions");
        this.contextSources = Objects.requireNonNull(
                contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(
                modelCallIdentity, "modelCallIdentity");
    }

    public ProgressionResult advance(
            AgentSession session, ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body, ChainInstructionRelationValue relation,
            Instant now) {
        return advanceInternal(session, task, instruction, body, relation,
                null, null, null, null, now);
    }

    /** Invokes one new Planner revision turn from an exact recovered authority. */
    public ProgressionResult advanceRevision(
            AgentSession session, ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body,
            ChainPersistenceRecords.PlanBindingRecord currentPlan,
            RevisionCandidate currentCandidate,
            String sourceAuthorityType,
            String sourceAuthorityRef,
            Instant now) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        if (!currentPlan.taskId().equals(task.taskId())
                || sourceAuthorityType == null
                || sourceAuthorityType.isBlank()
                || sourceAuthorityRef == null
                || sourceAuthorityRef.isBlank()) {
            throw new IllegalArgumentException(
                    "Planner revision source identity is invalid");
        }
        return advanceInternal(session, task, instruction, body,
                ChainInstructionRelationValue.INITIAL, currentPlan,
                currentCandidate, sourceAuthorityType, sourceAuthorityRef, now);
    }

    /** Continues a persistent route that has no formal Plan binding yet. */
    public ProgressionResult advancePersistentPlan(
            AgentSession session, ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body, String routeDecisionId, Instant now) {
        if (routeDecisionId == null || routeDecisionId.isBlank()) {
            throw new IllegalArgumentException(
                    "routeDecisionId must not be blank");
        }
        return advanceInternal(session, task, instruction, body,
                ChainInstructionRelationValue.INITIAL, null, null,
                "ROUTE_DECISION", routeDecisionId, now);
    }

    private ProgressionResult advanceInternal(
            AgentSession session, ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body, ChainInstructionRelationValue relation,
            ChainPersistenceRecords.PlanBindingRecord currentPlan,
            RevisionCandidate currentCandidate,
            String sourceAuthorityType,
            String sourceAuthorityRef,
            Instant now) {
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) -> {
            var output = new io.paperagent.v2.chain.model
                    .StrictChainProviderOutputParser()
                    .parse(raw, role, state, gap);
            if (currentPlan != null
                    && output.payload()
                    instanceof PlannerPayload.PlanRevision revision) {
                planTransitions.validateRevisionDraft(currentPlan, revision);
            }
            return output;
        };
        ProductChainModelEndpointResolver endpoint = request -> {
            UserSettingsService.ModelEndpoint value = settings.resolveModelEndpoint(
                    task.userId(), session.getModelProviderSnapshot(),
                    session.getModelSnapshot());
            return new ProductChainModelEndpoint(value.providerKey(), value.modelName(),
                    value.apiKey(), value.apiUrl());
        };
        var source = contextSources.source();
        var manager = new DefaultChainContextManager(contexts, contexts, source);
        ChainRole role = relation == ChainInstructionRelationValue.SUPPLEMENT
                || relation == ChainInstructionRelationValue.CORRECTION
                ? ChainRole.PLANNER : ChainRole.PLANNER;
        ChainWorkState state = relation == ChainInstructionRelationValue.SUPPLEMENT
                || relation == ChainInstructionRelationValue.CORRECTION
                ? ChainWorkState.CLASSIFYING_INSTRUCTION : ChainWorkState.PLANNING;
        String callReason = currentPlan != null ? "PLAN_REVISION"
                : sourceAuthorityType != null ? "PERSISTENT_PLAN"
                : state == ChainWorkState.CLASSIFYING_INSTRUCTION
                ? "USER_INSTRUCTION_DISPOSITION" : "INITIAL_INTAKE";
        String identitySeed = task.taskId() + "\0"
                + instruction.instructionId() + "\0" + callReason;
        if (sourceAuthorityType != null) {
            identitySeed += "\0" + sourceAuthorityType + "\0"
                    + sourceAuthorityRef;
        }
        String contextId = identity("context", identitySeed);
        String invocationId = identity("invocation", identitySeed);
        ProductChainModelCallIdentity.Binding callIdentity =
                modelCallIdentity.bind(
                        task.taskId(), contextId, invocationId);
        String version = task.initialProjectVersion();
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        callIdentity.contextRevisionId(), task.taskId(),
                        callIdentity.parentContextRevisionId(), role, state,
                        callReason,
                        instruction.instructionId(),
                        currentPlan == null ? null : currentPlan.taskFrameId(),
                        currentPlan == null ? null : currentPlan.planId(),
                        currentPlan == null ? null
                                : currentPlan.planRevisionId(),
                        currentPlan == null ? null
                                : (long) currentPlan.planRevisionNumber(), null,
                        null, task.projectId(), version,
                        currentCandidate == null ? null
                                : currentCandidate.workspaceId(),
                        currentCandidate == null ? null
                                : currentCandidate.artifactId(),
                        currentCandidate == null ? null
                                : currentCandidate.candidateFingerprint(),
                        null, null, null, "chain-product-projector-v1", "v1",
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId()).policyVersion(),
                        ChainContextRevisionStatus.BUILDING,
                        0, null, null, null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context.ChainContextFreezeRequest(
                        building, ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete)) {
            throw new IllegalStateException("Planner context input is blocked");
        }
        var endpointValue = settings.resolveModelEndpoint(task.userId(),
                session.getModelProviderSnapshot(), session.getModelSnapshot());
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(models, models, models,
                        transactions),
                new ProductChainChatModelAdapter(provider, endpoint), decoder);
        ChainModelProtocolOutcome outcome = protocol.invoke(
                new ChainModelProtocolRequest(task.taskId(),
                        callIdentity.invocationId(), callIdentity.contextRevisionId(),
                        frozen.context().revision().completionToken(), role, state,
                        callReason, endpointValue.providerKey(), endpointValue.modelName(),
                        callIdentity.invocationOrdinal(), null, now));
        if (!(outcome instanceof ChainModelProtocolOutcome.ProposalReady ready)) {
            throw new IllegalStateException("Planner model call failed");
        }
        var proposal = ready.proposal();
        var typed = decodePayload(proposal, state);
        var admission = new ProductChainProposalAdmissionAdapter(
                jdbc, transactions, models, models);
        admission.admit(new io.paperagent.v2.chain.model.ChainProposalAdmissionService.AdmissionRequest(
                proposal.proposalId(), task.taskId(), identity("proposal-accepted", proposal.proposalId()),
                true, null, proposal.payload().sha256(), now));
        return commitFormal(task, instruction, proposal, typed,
                now, admission).progression();
    }

    /**
     * Consumes one already persisted Planner proposal without invoking a
     * model. The proposal must still be bound to the current task Instruction
     * and its original COMPLETE ContextRevision.
     */
    public OfficialSuccessor commitAcceptedProposal(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant committedAt) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        proposalId = required(proposalId, "proposalId");
        Objects.requireNonNull(committedAt, "committedAt");
        verifyTaskAndCurrentInstruction(task, instruction);
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(proposalId)
                .orElseThrow(() -> failure(
                        "CHAIN_PLANNER_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_PLANNER_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_PLANNER_CONTEXT_MISSING"));
        verifyProposalLineage(
                task, instruction, proposal, invocation, context);
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedStatePrefix(task.taskId(), proposal.proposalId());
        PlannerPayload typed = decodeAcceptedPayload(
                task, instruction, proposal, invocation,
                states.get(states.size() - 1));
        if (typed instanceof PlannerPayload.PlanningBlocked) {
            throw failure(
                    "CHAIN_PLANNER_PLANNING_BLOCKED_CONSUMER_MISSING");
        }
        if (typed instanceof PlannerPayload.PlanRevision revision
                && states.size() == 2) {
            var bound = states.get(1);
            if (!"PLAN_BINDING".equals(
                    bound.officialAuthorityType())) {
                throw failure(
                        "CHAIN_PLANNER_PLAN_REVISION_BINDING_INVALID");
            }
            var recovered = planTransitions.recoverCompletedBinding(
                    task.taskId(), bound.officialAuthorityRef());
            verifyOfficialBinding(task.taskId(), proposal.proposalId(),
                    "PLAN_BINDING", recovered.planBinding()
                            .planBindingId());
            Instant executionAt = recovered.planBinding().createdAt();
            return new OfficialSuccessor(
                    "PLAN_BINDING",
                    recovered.planBinding().planBindingId(),
                    new ProgressionResult(false,
                            recovered.completeEventId(),
                            new PersistentExecutionCut(
                                    revision.newRevisionDraft().steps().size(),
                                    recovered, executionAt)));
        }
        Instant ownerTime = states.size() == 2
                ? states.get(1).committedAt() : committedAt;
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        FormalCommit committed = commitFormal(
                task, instruction, proposal, typed, ownerTime, admission);
        verifyOfficialBinding(
                task.taskId(), proposal.proposalId(),
                committed.authorityType(), committed.authorityRef());
        return new OfficialSuccessor(
                committed.authorityType(), committed.authorityRef(),
                committed.progression());
    }

    private FormalCommit commitFormal(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            PlannerPayload typed, Instant now,
            ProductChainProposalAdmissionAdapter admission) {
        var binder = (io.paperagent.v2.chain.route.ChainRouteRuntime.ProposalOfficialBinder)
                (taskId, proposalId, type, ref) -> admission.replaceByOfficialResult(
                        new io.paperagent.v2.chain.model.ChainProposalAdmissionService.OfficialReplacement(
                                proposalId, taskId, identity("proposal-bound", ref),
                                ChainPersistenceRecords.ProposalOfficialAuthorityType.valueOf(type),
                                ref, null, proposal.payload().sha256(), now));
        var common = new ChainRouteRuntime.CommonRequest(task.taskId(), instruction.instructionId(),
                proposal.proposalId(), identity("route-event", proposal.proposalId()), now);
        if (typed instanceof PlannerPayload.DirectRoute direct) {
            ChainPersistenceRecords.RouteDecisionRecord decision = new ChainRouteRuntime(models, workflow, (ChainRouteDecisionWriter) workflow,
                    new ChainInstructionStateReader(foundations, workflow, finalization), binder)
                    .commitDirect(new ChainRouteRuntime.InitialRouteRequest(common), direct);
            return new FormalCommit(
                    new ProgressionResult(false, decision.eventId()),
                    "ROUTE_DECISION", decision.routeDecisionId());
        }
        if (typed instanceof PlannerPayload.PersistentPlan persistent) {
            LOG.log(System.Logger.Level.INFO,
                    "persistent chain plan accepted taskId={0} proposalId={1} stepCount={2}",
                    task.taskId(), proposal.proposalId(),
                    persistent.initialPlan().steps().size());
            ChainPersistenceRecords.RouteDecisionRecord decision = new ChainRouteRuntime(models, workflow, (ChainRouteDecisionWriter) workflow,
                    new ChainInstructionStateReader(foundations, workflow, finalization), binder)
                    .commitPersistent(new ChainRouteRuntime.InitialRouteRequest(common), persistent);
            // Product persistence owns the audit clock for Task/Instruction
            // rows.  The request timestamp can therefore precede the
            // persisted instruction timestamp by a few microseconds.  Use a
            // monotonic hand-off time for Plan/Step activation so the stable
            // checkpoint validator never observes a time regression.
            Instant executionAt = ChainAuthorityTime.atOrAfter(
                    now, instruction.createdAt());
            ProductChainPlanTransitionDriver.Result transition = planTransitions
                    .commitInitial(task, instruction, proposal, persistent,
                            decision.routeDecisionId(), executionAt);
            return new FormalCommit(
                    new ProgressionResult(false, transition.completeEventId(),
                            new PersistentExecutionCut(
                                    persistent.initialPlan().steps().size(),
                                    transition, executionAt)),
                    "ROUTE_DECISION", decision.routeDecisionId());
        }
        if (typed instanceof PlannerPayload.UserInstructionDisposition disposition) {
            var runtime = new io.paperagent.v2.chain.instruction.ChainInstructionDispositionRuntime(
                    models, (io.paperagent.v2.chain.ChainInstructionDispositionWriter) workflow,
                    (taskId, proposalId, type, ref) -> admission.replaceByOfficialResult(
                            new io.paperagent.v2.chain.model.ChainProposalAdmissionService.OfficialReplacement(
                                    proposalId, taskId, identity("proposal-bound", ref),
                                    ChainPersistenceRecords.ProposalOfficialAuthorityType.INSTRUCTION_DISPOSITION,
                                    ref, null, proposal.payload().sha256(), now)));
            var dispositionResult = runtime.commit(new io.paperagent.v2.chain.instruction.ChainInstructionDispositionRuntime.CommitRequest(
                    task.taskId(), proposal.proposalId(), instruction.instructionId(),
                    identity("disposition-event", proposal.proposalId()), disposition, now));
            return new FormalCommit(new ProgressionResult(
                    disposition.boundaryChanged(),
                    dispositionResult.disposition().eventId()),
                    "INSTRUCTION_DISPOSITION",
                    dispositionResult.disposition().dispositionId());
        }
        if (typed instanceof PlannerPayload.NeedUserInput need) {
            var pending = openGap(
                    task, instruction, proposal, need, now, admission);
            return new FormalCommit(
                    new ProgressionResult(false, pending.eventId()),
                    "PENDING_ITEM", pending.gapId());
        }
        if (typed instanceof PlannerPayload.NeedPermission need) {
            var pending = openPermission(
                    task, instruction, proposal, need, now, admission);
            return new FormalCommit(
                    new ProgressionResult(false, pending.eventId()),
                    "PENDING_ITEM", pending.gapId());
        }
        if (typed instanceof PlannerPayload.PlanRevision revision) {
            Instant executionAt = ChainAuthorityTime.atOrAfter(
                    now, instruction.createdAt());
            ProductChainPlanTransitionDriver.Result transition =
                    planTransitions.commitRevision(
                            task, instruction, proposal, revision,
                            executionAt, binder);
            return new FormalCommit(
                    new ProgressionResult(false,
                            transition.completeEventId(),
                            new PersistentExecutionCut(
                                    revision.newRevisionDraft().steps().size(),
                                    transition, executionAt)),
                    "PLAN_BINDING",
                    transition.planBinding().planBindingId());
        }
        if (typed instanceof PlannerPayload.PlanningBlocked) {
            throw failure(
                    "CHAIN_PLANNER_PLANNING_BLOCKED_CONSUMER_MISSING");
        }
        throw failure("CHAIN_PLANNER_PROPOSAL_CONSUMER_MISSING");
    }

    private void verifyTaskAndCurrentInstruction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction) {
        ChainPersistenceRecords.TaskRecord storedTask = foundations
                .findTask(task.taskId())
                .orElseThrow(() -> failure(
                        "CHAIN_PLANNER_TASK_MISSING"));
        ChainPersistenceRecords.InstructionRecord storedInstruction =
                foundations.findInstruction(instruction.instructionId())
                        .orElseThrow(() -> failure(
                                "CHAIN_PLANNER_INSTRUCTION_MISSING"));
        if (!sameTaskIdentity(storedTask, task)
                || storedTask.nextEventSequence()
                < task.nextEventSequence()
                || !storedInstruction.equals(instruction)
                || task.sessionId() != instruction.sessionId()) {
            throw failure("CHAIN_PLANNER_TASK_INSTRUCTION_MISMATCH");
        }
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                foundations.findTaskInstructions(task.taskId(), Long.MAX_VALUE)
                        .stream().sorted(Comparator.comparingLong(
                                ChainPersistenceRecords
                                        .TaskInstructionBindingRecord
                                        ::taskInstructionSequence))
                        .toList();
        if (bindings.isEmpty()) {
            throw failure("CHAIN_PLANNER_INSTRUCTION_BINDING_MISSING");
        }
        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index);
            if (!binding.taskId().equals(task.taskId())
                    || binding.taskInstructionSequence() != index + 1L) {
                throw failure(
                        "CHAIN_PLANNER_INSTRUCTION_BINDING_PREFIX_INVALID");
            }
        }
        if (!bindings.get(bindings.size() - 1).instructionId()
                .equals(instruction.instructionId())) {
            throw failure("CHAIN_PLANNER_INSTRUCTION_NOT_CURRENT");
        }
    }

    private void verifyProposalLineage(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        var byInvocation = models.findProposalByInvocation(
                        invocation.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_PLANNER_INVOCATION_PROPOSAL_MISSING"));
        boolean allowedState = invocation.workState()
                == ChainWorkState.PLANNING
                || invocation.workState()
                == ChainWorkState.CLASSIFYING_INSTRUCTION
                || invocation.workState()
                == ChainWorkState.VALIDATING_PENDING_ITEM;
        if (!proposal.equals(byInvocation)
                || !proposal.taskId().equals(task.taskId())
                || !proposal.invocationId().equals(
                invocation.invocationId())
                || proposal.role() != ChainRole.PLANNER
                || proposal.proposalKind().role() != ChainRole.PLANNER
                || !invocation.taskId().equals(task.taskId())
                || invocation.role() != ChainRole.PLANNER
                || !allowedState
                || !invocation.runtimePolicyVersion().equals(
                context.runtimePolicyVersion())
                || !context.contextRevisionId().equals(
                invocation.contextRevisionId())
                || !context.taskId().equals(task.taskId())
                || context.role() != invocation.role()
                || context.workState() != invocation.workState()
                || !context.callReason().equals(invocation.callReason())
                || !context.instructionId().equals(
                instruction.instructionId())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !Objects.equals(context.completionToken(),
                invocation.completionToken())
                || !context.runtimePolicyVersion().equals(
                invocation.runtimePolicyVersion())
                || !Objects.equals(context.projectId(), task.projectId())
                || !Objects.equals(context.projectVersion(),
                task.initialProjectVersion())) {
            throw failure("CHAIN_PLANNER_PROPOSAL_LINEAGE_INVALID");
        }
        verifyCanonical(proposal.payload(),
                "CHAIN_PLANNER_PROPOSAL_PAYLOAD_INVALID");
        verifyCanonical(proposal.sourceRefs(),
                "CHAIN_PLANNER_PROPOSAL_SOURCE_REFS_INVALID");
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            acceptedStatePrefix(String taskId, String proposalId) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposalId).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence))
                .toList();
        if (states.isEmpty() || states.size() > 2) {
            throw failure("CHAIN_PLANNER_PROPOSAL_STATE_INVALID");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposalId)
                    || state.stateSequence() != index + 1L) {
                throw failure("CHAIN_PLANNER_PROPOSAL_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_PLANNER_PROPOSAL_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw failure("CHAIN_PLANNER_PROPOSAL_NOT_ACCEPTED");
        }
        if (states.size() == 2) {
            try {
                ChainPersistenceRecords.ProposalOfficialAuthorityType.valueOf(
                        states.get(1).officialAuthorityType());
            } catch (IllegalArgumentException invalid) {
                throw failure(
                        "CHAIN_PLANNER_PROPOSAL_OFFICIAL_TYPE_INVALID");
            }
        }
        return states;
    }

    private PlannerPayload decodeAcceptedPayload(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ProposalStateEventRecord currentState) {
        if (invocation.workState()
                != ChainWorkState.VALIDATING_PENDING_ITEM) {
            return decodePayload(proposal, invocation.workState(), null);
        }
        List<ValidationPayload> matches = new ArrayList<>();
        for (var item : workflow.findPendingItems(task.taskId())) {
            if (item.taskId().equals(task.taskId())
                    && item.validationRole() == ChainRole.PLANNER) {
                try {
                    PlannerPayload payload = decodePayload(
                            proposal, invocation.workState(), item.gapId());
                    if (payload.gapValidation() != null
                            && payload.gapValidation().gapId()
                            .equals(item.gapId())) {
                        matches.add(new ValidationPayload(item, payload));
                    }
                } catch (io.paperagent.v2.chain.model
                        .ChainProviderProtocolException ignored) {
                    // This typed proposal targets another PendingItem.
                }
            }
        }
        if (matches.size() != 1) {
            throw failure("CHAIN_PLANNER_GAP_VALIDATION_NOT_UNIQUE");
        }
        ValidationPayload match = matches.get(0);
        List<ChainPersistenceRecords.PendingItemEventRecord> events = workflow
                .findPendingItemEvents(match.item().gapId());
        if (events.isEmpty()) {
            throw failure("CHAIN_PLANNER_GAP_RESPONSE_MISSING");
        }
        var response = events.get(events.size() - 1);
        var accepted = new ChainPendingItemRuntime.AcceptedGapValidation(
                proposal, currentState, invocation, match.payload());
        ChainPendingItemRuntime.validateGapProposalAuthority(
                match.item(), response.eventKind(), response.responseRound(),
                response.answerInstructionId(), accepted);
        if (response.eventKind()
                != io.paperagent.v2.chain.ChainPendingItemStatus
                .RESPONSE_RECEIVED
                || !Objects.equals(response.answerInstructionId(),
                instruction.instructionId())) {
            throw failure("CHAIN_PLANNER_GAP_RESPONSE_INSTRUCTION_INVALID");
        }
        if (match.payload().gapValidation().outcome()
                != GapValidation.Outcome.RESOLVED) {
            throw failure("CHAIN_PLANNER_GAP_NORMAL_SUCCESSOR_NOT_READY");
        }
        return match.payload();
    }

    private void verifyOfficialBinding(
            String taskId,
            String proposalId,
            String authorityType,
            String authorityRef) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedStatePrefix(taskId, proposalId);
        if (states.size() != 2
                || !authorityType.equals(
                states.get(1).officialAuthorityType())
                || !authorityRef.equals(
                states.get(1).officialAuthorityRef())) {
            throw failure("CHAIN_PLANNER_OFFICIAL_SUCCESSOR_MISMATCH");
        }
    }

    private static void verifyCanonical(
            ChainPersistenceRecords.CanonicalJson value,
            String errorCode) {
        if (!sha256(value.json()).equals(value.sha256())) {
            throw failure(errorCode);
        }
    }

    private static boolean sameTaskIdentity(
            ChainPersistenceRecords.TaskRecord stored,
            ChainPersistenceRecords.TaskRecord requested) {
        return stored.taskId().equals(requested.taskId())
                && stored.createdByCommandId().equals(
                requested.createdByCommandId())
                && stored.sourceInstructionId().equals(
                requested.sourceInstructionId())
                && Objects.equals(stored.predecessorTaskId(),
                requested.predecessorTaskId())
                && stored.userId() == requested.userId()
                && stored.sessionId() == requested.sessionId()
                && stored.turnId() == requested.turnId()
                && Objects.equals(stored.requestMessageId(),
                requested.requestMessageId())
                && stored.rootClientRequestId().equals(
                requested.rootClientRequestId())
                && stored.rootRequestSha256().equals(
                requested.rootRequestSha256())
                && Objects.equals(stored.projectId(), requested.projectId())
                && Objects.equals(stored.initialProjectVersion(),
                requested.initialProjectVersion())
                && stored.createdAt().equals(requested.createdAt());
    }

    private ChainPersistenceRecords.PendingItemRecord openGap(ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            PlannerPayload.NeedUserInput value, Instant now,
            ProductChainProposalAdmissionAdapter admission) {
        var runtime = pendingRuntime(task, proposal, admission, now,
                new ChainPendingItemRuntime.PendingProposal(task.taskId(), proposal.proposalId(),
                        proposal.proposalKind(), currentState(proposal),
                        ChainPendingItemType.USER_INFORMATION, value.missingFields(), null,
                        value.exactQuestion(), value.expectedFormat(), value.validationRole(),
                        value.resumeRole(), value.resumePosition(),
                        sha256(task.initialProjectVersion())));
        return runtime.open(new ChainPendingItemRuntime.OpenRequest(
                task.taskId(), identity("pending-event", proposal.proposalId()),
                proposal.proposalId(), now));
    }

    private ChainPersistenceRecords.PendingItemRecord openPermission(ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            PlannerPayload.NeedPermission value, Instant now,
            ProductChainProposalAdmissionAdapter admission) {
        var runtime = pendingRuntime(task, proposal, admission, now,
                new ChainPendingItemRuntime.PendingProposal(task.taskId(), proposal.proposalId(),
                        proposal.proposalKind(), currentState(proposal),
                        ChainPendingItemType.PERMISSION, List.of(), value.scope(), value.purpose(),
                        value.lowerPrivilegeAlternative(), ChainRole.PLANNER, ChainRole.PLANNER,
                        value.reintakePosition(), sha256(task.initialProjectVersion())));
        return runtime.open(new ChainPendingItemRuntime.OpenRequest(
                task.taskId(), identity("pending-event", proposal.proposalId()),
                proposal.proposalId(), now));
    }

    private ChainPendingItemRuntime pendingRuntime(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ProductChainProposalAdmissionAdapter admission,
            Instant committedAt,
            ChainPendingItemRuntime.PendingProposal pending) {
        return new ChainPendingItemRuntime(workflow, foundations,
                (io.paperagent.v2.chain.ChainPendingItemWriter) workflow,
                id -> pending,
                id -> { throw new IllegalStateException("pending validation is not in this turn"); },
                new ChainPendingItemRuntime.NormalSuccessorPort() {
                    public ChainPendingItemRuntime.OfficialSuccessor commit(ChainPendingItemRuntime.NormalSuccessorRequest r) { throw new IllegalStateException("successor is not in intake"); }
                    public java.util.Optional<ChainPendingItemRuntime.OfficialSuccessor> findCommitted(String t, String tr) { return java.util.Optional.empty(); }
                },
                new ChainPendingItemRuntime.PermissionDecisionSource() {
                    public java.util.Optional<ChainPersistenceRecords.PermissionDecisionRecord> find(String t, String g, String d) { return java.util.Optional.empty(); }
                    public java.util.Optional<ChainPersistenceRecords.PermissionDecisionRecord> findLatest(String t, String g) { return java.util.Optional.empty(); }
                },
                (taskId, proposalId, type, ref) -> admission.replaceByOfficialResult(
                        new io.paperagent.v2.chain.model.ChainProposalAdmissionService.OfficialReplacement(
                                proposalId, taskId, identity("proposal-bound", ref),
                                ChainPersistenceRecords.ProposalOfficialAuthorityType.PENDING_ITEM,
                                ref, null, proposal.payload().sha256(),
                                committedAt)));
    }

    private ChainPersistenceRecords.ProposalStateEventRecord currentState(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        return models.findProposalStateEvents(proposal.proposalId()).stream()
                .max(java.util.Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord::stateSequence))
                .orElseThrow(() -> new IllegalStateException("admitted proposal has no state"));
    }

    private static PlannerPayload decodePayload(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainWorkState state) {
        return decodePayload(proposal, state, null);
    }

    private static PlannerPayload decodePayload(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainWorkState state,
            String boundGapId) {
        String raw = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        return (PlannerPayload) new io.paperagent.v2.chain.model.StrictChainProviderOutputParser()
                .parse(raw, ChainRole.PLANNER, state, boundGapId).payload();
    }

    private static String identity(String prefix, String value) {
        try {
            return prefix + "." + java.util.HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(
            List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"').append(values.get(index).replace("\\", "\\\\")
                    .replace("\"", "\\\"")).append('"');
        }
        String encoded = json.append(']').toString();
        return new ChainPersistenceRecords.CanonicalJson(1, sha256(encoded), encoded);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private record FormalCommit(
            ProgressionResult progression,
            String authorityType,
            String authorityRef) {
        private FormalCommit {
            Objects.requireNonNull(progression, "progression");
            authorityType = required(authorityType, "authorityType");
            authorityRef = required(authorityRef, "authorityRef");
        }
    }

    private record ValidationPayload(
            ChainPersistenceRecords.PendingItemRecord item,
            PlannerPayload payload) {
        private ValidationPayload {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(payload, "payload");
        }
    }

    /** Exact formal authority produced by consuming one Planner proposal. */
    public record OfficialSuccessor(
            String authorityType,
            String authorityRef,
            ProgressionResult progression) {
        public OfficialSuccessor {
            authorityType = required(authorityType, "authorityType");
            authorityRef = required(authorityRef, "authorityRef");
            Objects.requireNonNull(progression, "progression");
        }
    }

    public record ProgressionResult(
            boolean boundaryChanged,
            String formalEventId,
            PersistentExecutionCut persistentExecution) {
        public ProgressionResult(boolean boundaryChanged,
                                 String formalEventId) {
            this(boundaryChanged, formalEventId, null);
        }

        public ProgressionResult {
            if (formalEventId == null || formalEventId.isBlank()) {
                throw new IllegalArgumentException(
                        "formalEventId must not be blank");
            }
            if (boundaryChanged && persistentExecution != null) {
                throw new IllegalArgumentException(
                        "boundary change cannot expose persistent execution");
            }
        }
    }

    /** Formal Plan cut required by a later progression driver. */
    public record PersistentExecutionCut(
            int stepCount,
            ProductChainPlanTransitionDriver.Result transition,
            Instant executionAt) {
        public PersistentExecutionCut {
            if (stepCount < 1) {
                throw new IllegalArgumentException(
                        "stepCount must be positive");
            }
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(executionAt, "executionAt");
        }
    }

    /** Candidate identity already frozen by recovery for a Plan revision. */
    public record RevisionCandidate(
            String workspaceId, long artifactId,
            String candidateFingerprint) {
        public RevisionCandidate {
            workspaceId = required(workspaceId, "workspaceId");
            if (artifactId < 1) {
                throw new IllegalArgumentException(
                        "artifactId must be positive");
            }
            candidateFingerprint = required(
                    candidateFingerprint, "candidateFingerprint");
        }
    }

    public enum ChainInstructionRelationValue { INITIAL, SUPPLEMENT, CORRECTION }
}
