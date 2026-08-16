package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.DefaultChainContextManager;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.review.ChainReviewRuntime;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Formal Reflector successor for one accepted Executor STEP_BLOCKED fact. */
@Component
public final class ProductChainStepBlockedProgression {
    public static final String CALL_REASON = "STEP_BLOCKED_REVIEW";

    private final ProductChainExecutorProgression executor;
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final PlatformTransactionManager transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainCompletedOutcomeAdapter outcomes;

    public ProductChainStepBlockedProgression(
            ProductChainExecutorProgression executor,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            PlatformTransactionManager transactions,
            NamedParameterJdbcTemplate jdbc,
            ProductChainCompletedOutcomeAdapter outcomes) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.contextSources = Objects.requireNonNull(contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(modelCallIdentity, "modelCallIdentity");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    public String invoke(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainRecoveryRuntime.NextDirective directive,
            Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(directive, "directive");
        Objects.requireNonNull(now, "now");
        require(directive.role() == ChainRole.REFLECTOR
                        && directive.workState() == ChainWorkState.AWAITING_REVIEW
                        && "PROPOSAL_STATE".equals(
                        directive.sourceAuthorityType()),
                "CHAIN_STEP_BLOCK_DIRECTIVE_INVALID");
        var source = source(task.taskId(), directive.sourceAuthorityRef());
        require(instruction.instructionId().equals(
                        source.executorContext().instructionId()),
                "CHAIN_STEP_BLOCK_INSTRUCTION_STALE");
        String contextId = contextId(task.taskId(), source.acceptedState().eventId());
        ProductChainModelCallIdentity.Binding identity = modelCallIdentity.bind(
                task.taskId(), contextId, invocationId(contextId));
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        var predecessor = source.executorContext();
        var binding = source.planBinding();
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        identity.contextRevisionId(), task.taskId(),
                        identity.parentContextRevisionId(), ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW, CALL_REASON,
                        instruction.instructionId(), binding.taskFrameId(),
                        binding.planId(), binding.planRevisionId(),
                        binding.planRevisionNumber(), predecessor.stepId(),
                        predecessor.activationEventId(), task.projectId(),
                        task.initialProjectVersion(), predecessor.workspaceId(),
                        predecessor.candidateArtifactId(),
                        predecessor.candidateFingerprint(),
                        predecessor.validationId(),
                        predecessor.validationRequestDigest(),
                        predecessor.validationReceiptDigest(),
                        predecessor.projectorSetVersion(),
                        predecessor.paginationVersion(),
                        predecessor.runtimePolicyVersion(),
                        ChainContextRevisionStatus.BUILDING, 0, null, null,
                        null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context.ChainContextFreezeRequest(
                        building, ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw failure("CHAIN_STEP_BLOCK_CONTEXT_BLOCKED");
        }
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                task.userId(), session.getModelProviderSnapshot(),
                session.getModelSnapshot());
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(
                        models, models, models, transactions),
                new ProductChainChatModelAdapter(provider, ignored ->
                        new ProductChainModelEndpoint(
                                endpoint.providerKey(), endpoint.modelName(),
                                endpoint.apiKey(), endpoint.apiUrl())),
                (raw, role, state, gap) -> {
                    ProviderRoleOutput output = new StrictChainProviderOutputParser()
                            .parse(raw, role, state, gap);
                    validatePayload((ReflectorPayload) output.payload(), source);
                    return output;
                });
        ChainModelProtocolOutcome result = protocol.invoke(
                new ChainModelProtocolRequest(
                        task.taskId(), identity.invocationId(),
                        identity.contextRevisionId(),
                        complete.context().revision().completionToken(),
                        ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                        CALL_REASON, endpoint.providerKey(), endpoint.modelName(),
                        identity.invocationOrdinal(), null, now));
        if (!(result instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().role() != ChainRole.REFLECTOR) {
            throw failure("CHAIN_STEP_BLOCK_REFLECTOR_PROPOSAL_MISSING");
        }
        ReflectorPayload payload = decode(ready.proposal());
        validatePayload(payload, source);
        new ProductChainProposalAdmissionAdapter(
                jdbc, transactions, models, models).admit(
                new ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), task.taskId(),
                        identity("step-block-reflector-accepted",
                                ready.proposal().proposalId()),
                        true, null, ready.proposal().payload().sha256(), now));
        return ready.proposal().proposalId();
    }

    /** True only when the selected exact event belongs to STEP_BLOCKED. */
    public boolean handles(String taskId, String acceptedEventId) {
        var state = models.findProposalStateEvent(acceptedEventId);
        if (state.isEmpty()) return false;
        var selected = state.orElseThrow();
        if (!selected.taskId().equals(taskId)
                || selected.stateSequence() != 1L
                || selected.stateKind() != ChainProposalState.ACCEPTED
                || selected.officialAuthorityType() != null
                || selected.officialAuthorityRef() != null) {
            return false;
        }
        var proposal = models.findProposal(selected.proposalId());
        if (proposal.isEmpty()
                || proposal.orElseThrow().proposalKind()
                != ChainProposalKind.EXECUTOR_STEP_BLOCKED) {
            return false;
        }
        if (!proposal.orElseThrow().taskId().equals(taskId)
                || proposal.orElseThrow().role() != ChainRole.EXECUTOR) {
            return false;
        }
        Objects.requireNonNull(source(taskId, acceptedEventId));
        return true;
    }

    public ChainReviewRuntime.CommitResult consume(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(now, "now");
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(ignored -> consumeInTransaction(
                        task, instruction, proposalId, now)));
    }

    private ChainReviewRuntime.CommitResult consumeInTransaction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant now) {
        var proposal = models.findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_BLOCK_REFLECTOR_PROPOSAL_MISSING"));
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_BLOCK_REFLECTOR_INVOCATION_MISSING"));
        var context = contexts.findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_BLOCK_REFLECTOR_CONTEXT_MISSING"));
        var source = sourceForContext(task.taskId(), context);
        validateInvocation(task, instruction, source, proposal, invocation,
                context);
        ReflectorPayload payload = decode(proposal);
        validatePayload(payload, source);
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedStates(task.taskId(), proposal.proposalId());
        ChainReviewRuntime.CommitResult committed;
        if (states.size() == 2) {
            committed = exactBoundReview(task.taskId(), proposal, payload,
                    source, states.get(1));
        } else {
            ProductChainProposalAdmissionAdapter admission =
                    new ProductChainProposalAdmissionAdapter(
                            jdbc, transactions, models, models);
            ChainReviewRuntime runtime = new ChainReviewRuntime(
                    workflow, workflow,
                    ignored -> new ChainReviewRuntime.FormalReviewProposal(
                            proposal, states.get(0), payload,
                            sourceFence(source)),
                    (ignoredTask, ignoredProposal, type, ref) ->
                            admission.replaceByOfficialResult(
                                    new ChainProposalAdmissionService
                                            .OfficialReplacement(
                                            proposal.proposalId(), task.taskId(),
                                            identity("step-block-review-bound", ref),
                                            ChainPersistenceRecords
                                                    .ProposalOfficialAuthorityType
                                                    .REVIEW_DECISION,
                                            ref, null,
                                            proposal.payload().sha256(), now)));
            committed = runtime.commit(new ChainReviewRuntime.CommitRequest(
                    task.taskId(), proposal.proposalId(),
                    identity("step-block-review-event", proposal.proposalId()),
                    "PROPOSAL_STATE", source.acceptedState().eventId(), now));
        }
        if (payload instanceof ReflectorPayload.NeedUserInput
                || payload instanceof ReflectorPayload.NeedPermission) {
            openPending(task, proposal, payload, source, committed.decision(),
                    now);
        } else if (payload instanceof ReflectorPayload.TaskFailed failed) {
            commitFailedOutcome(task, instruction, source,
                    committed.decision(), failed, now);
        }
        return committed;
    }

    private ChainPersistenceRecords.PendingItemRecord openPending(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ReflectorPayload payload,
            ProductChainExecutorProgression.AcceptedStepBlock source,
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            Instant now) {
        var current = acceptedStates(task.taskId(), proposal.proposalId());
        require(current.size() == 2
                        && current.get(1).officialAuthorityRef().equals(
                        decision.reviewDecisionId()),
                "CHAIN_STEP_BLOCK_PENDING_REVIEW_BINDING_INVALID");
        ChainPendingItemRuntime.PendingProposal pending;
        if (payload instanceof ReflectorPayload.NeedUserInput value) {
            pending = new ChainPendingItemRuntime.PendingProposal(
                    task.taskId(), proposal.proposalId(), proposal.proposalKind(),
                    current.get(1), ChainPendingItemType.USER_INFORMATION,
                    value.missingFields(), null, value.exactQuestion(),
                    value.expectedFormat(), value.validationRole(),
                    ChainRole.EXECUTOR, value.resumePosition(),
                    sourceFence(source));
        } else {
            ReflectorPayload.NeedPermission value =
                    (ReflectorPayload.NeedPermission) payload;
            pending = new ChainPendingItemRuntime.PendingProposal(
                    task.taskId(), proposal.proposalId(), proposal.proposalKind(),
                    current.get(1), ChainPendingItemType.PERMISSION, List.of(),
                    value.scope(), value.purpose(), "permission decision",
                    value.validationRole(), ChainRole.EXECUTOR,
                    value.newIntakePosition(), sourceFence(source));
        }
        ChainPendingItemRuntime runtime = new ChainPendingItemRuntime(
                workflow, foundations, workflow, ignored -> pending,
                ignored -> { throw failure(
                        "CHAIN_STEP_BLOCK_PENDING_VALIDATION_UNUSED"); },
                new ChainPendingItemRuntime.NormalSuccessorPort() {
                    @Override
                    public ChainPendingItemRuntime.OfficialSuccessor commit(
                            ChainPendingItemRuntime.NormalSuccessorRequest request) {
                        throw failure("CHAIN_STEP_BLOCK_PENDING_SUCCESSOR_UNUSED");
                    }

                    @Override
                    public java.util.Optional<ChainPendingItemRuntime
                            .OfficialSuccessor> findCommitted(
                            String taskId, String transitionId) {
                        return java.util.Optional.empty();
                    }
                },
                new ChainPendingItemRuntime.PermissionDecisionSource() {
                    @Override
                    public java.util.Optional<ChainPersistenceRecords
                            .PermissionDecisionRecord> find(
                            String taskId, String gapId, String decisionId) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.Optional<ChainPersistenceRecords
                            .PermissionDecisionRecord> findLatest(
                            String taskId, String gapId) {
                        return java.util.Optional.empty();
                    }
                },
                (taskId, proposalId, type, ref) -> {
                    throw failure("CHAIN_STEP_BLOCK_SECOND_PROPOSAL_BIND_FORBIDDEN");
                });
        return runtime.openFromReviewDecision(
                new ChainPendingItemRuntime.OpenRequest(
                        task.taskId(), proposal.proposalId(),
                        identity("step-block-pending-event",
                        decision.reviewDecisionId()), now),
                decision.reviewDecisionId());
    }

    private void commitFailedOutcome(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainExecutorProgression.AcceptedStepBlock source,
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            ReflectorPayload.TaskFailed payload,
            Instant now) {
        var binding = source.planBinding();
        var context = source.executorContext();
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        task.taskId(), identity("task-outcome",
                        task.taskId() + "\0" + source.acceptedState().eventId()),
                        instruction.commandId(), instruction.instructionId(),
                        binding.taskFrameId(), binding.planId(),
                        binding.planRevisionId(), canonicalArray(List.of()),
                        canonicalArray(List.of()), context.candidateArtifactId(),
                        Objects.toString(context.candidateFingerprint(),
                                ChainIdentity.NONE),
                        Objects.toString(context.validationId(),
                                ChainIdentity.NONE),
                        null, null, null, null,
                        canonicalArray(payload.unfinishedOrSkippedItems()),
                        canonicalArray(payload.review().knownLimitations()),
                        canonicalArray(payload.finalization().residualRisks()),
                        now);
        outcomes.commit(new ChainTaskOutcomeRuntime.Failed(
                        draft, source.acceptedState().eventId(),
                        source.payload().failureCategory(), "STEP_BLOCKED"),
                new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
                    @Override
                    public void verifyCompleted(
                            ChainTaskOutcomeRuntime.Completed value) {
                        throw failure("CHAIN_STEP_BLOCK_OUTCOME_KIND_INVALID");
                    }

                    @Override
                    public void verifyFailed(
                            ChainTaskOutcomeRuntime.Failed value) {
                        var exact = source(task.taskId(),
                                source.acceptedState().eventId());
                        require(exact.equals(source)
                                        && decision.taskId().equals(task.taskId())
                                        && decision.reviewObjectType().equals(
                                        "PROPOSAL_STATE")
                                        && decision.reviewObjectId().equals(
                                        source.acceptedState().eventId())
                                        && decision.decisionKind()
                                        == ChainProposalKind.REFLECTOR_TASK_FAILED
                                        && value.formalFailureSourceId().equals(
                                        source.acceptedState().eventId())
                                        && value.failureCategory().equals(
                                        source.payload().failureCategory())
                                        && value.failureCode().equals(
                                        "STEP_BLOCKED"),
                                "CHAIN_STEP_BLOCK_OUTCOME_SOURCE_INVALID");
                    }

                    @Override
                    public void verifyCancelled(
                            ChainTaskOutcomeRuntime.Cancelled value) {
                        throw failure("CHAIN_STEP_BLOCK_OUTCOME_KIND_INVALID");
                    }

                    @Override
                    public void verifySuperseded(
                            ChainTaskOutcomeRuntime.Superseded value) {
                        throw failure("CHAIN_STEP_BLOCK_OUTCOME_KIND_INVALID");
                    }
                });
    }

    private ProductChainExecutorProgression.AcceptedStepBlock source(
            String taskId, String acceptedEventId) {
        return executor.recoverAcceptedStepBlock(taskId, acceptedEventId);
    }

    private ProductChainExecutorProgression.AcceptedStepBlock sourceForContext(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        require(context.contextRevisionId().startsWith("context."),
                "CHAIN_STEP_BLOCK_CONTEXT_SOURCE_INVALID");
        long invocationCut = models.highestInvocationOrdinal(taskId);
        var invocations = models.findInvocations(taskId, invocationCut);
        validateInvocationPrefix(taskId, invocationCut, invocations);
        List<ProductChainExecutorProgression.AcceptedStepBlock> matches =
                invocations.stream()
                        .filter(value -> value.role() == ChainRole.EXECUTOR)
                        .map(value -> models.findProposalByInvocation(
                                value.invocationId()).orElse(null))
                        .filter(Objects::nonNull)
                        .filter(value -> value.proposalKind()
                                == ChainProposalKind.EXECUTOR_STEP_BLOCKED)
                        .flatMap(value -> models.findProposalStateEvents(
                                value.proposalId()).stream())
                        .filter(value -> value.stateSequence() == 1L
                                && value.stateKind()
                                == ChainProposalState.ACCEPTED)
                        .filter(value -> context.contextRevisionId().equals(
                                contextId(taskId, value.eventId())))
                        .map(value -> source(taskId, value.eventId()))
                        .toList();
        return exactlyOne(matches, "CHAIN_STEP_BLOCK_CONTEXT_SOURCE_INVALID");
    }

    private void validateInvocation(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainExecutorProgression.AcceptedStepBlock source,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        var predecessor = source.executorContext();
        var binding = source.planBinding();
        String expectedContext = contextId(
                task.taskId(), source.acceptedState().eventId());
        require(proposal.taskId().equals(task.taskId())
                        && proposal.role() == ChainRole.REFLECTOR
                        && proposal.proposalKind().role() == ChainRole.REFLECTOR
                        && proposal.invocationId().equals(invocation.invocationId())
                        && invocation.taskId().equals(task.taskId())
                        && invocation.role() == ChainRole.REFLECTOR
                        && invocation.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && CALL_REASON.equals(invocation.callReason())
                        && expectedContext.equals(invocation.contextRevisionId())
                        && invocation.invocationId().equals(
                                invocationId(expectedContext))
                        && context.contextRevisionId().equals(expectedContext)
                        && context.taskId().equals(task.taskId())
                        && context.status() == ChainContextRevisionStatus.COMPLETE
                        && context.role() == ChainRole.REFLECTOR
                        && context.workState() == ChainWorkState.AWAITING_REVIEW
                        && CALL_REASON.equals(context.callReason())
                        && context.instructionId().equals(
                                instruction.instructionId())
                        && Objects.equals(context.taskFrameId(),
                                binding.taskFrameId())
                        && Objects.equals(context.planId(), binding.planId())
                        && Objects.equals(context.planRevisionId(),
                                binding.planRevisionId())
                        && Objects.equals(context.planRevisionNumber(),
                                binding.planRevisionNumber())
                        && Objects.equals(context.stepId(), predecessor.stepId())
                        && Objects.equals(context.activationEventId(),
                                predecessor.activationEventId())
                        && proposal.bodyAuthorityType() == null
                        && proposal.bodyAuthorityRef() == null,
                "CHAIN_STEP_BLOCK_REFLECTOR_IDENTITY_INVALID");
    }

    static void validatePayload(
            ReflectorPayload payload,
            ProductChainExecutorProgression.AcceptedStepBlock source) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(source, "source");
        require(payload.kind() == ChainProposalKind.REFLECTOR_CONTINUE_STEP
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_NEED_USER_INPUT
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_NEED_PERMISSION
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_TASK_FAILED,
                "CHAIN_STEP_BLOCK_REFLECTOR_KIND_INVALID");
        String event = source.acceptedState().eventId();
        String error = source.payload().errorRef();
        require(payload.review().reviewedObjectRefs().contains(event)
                        && payload.review().directFactRefs().contains(event)
                        && payload.review().directFactRefs().contains(error),
                "CHAIN_STEP_BLOCK_REFLECTOR_FACT_REF_MISSING; expected "
                        + "reviewedObjectRefs to contain event=" + event
                        + " and directFactRefs to contain event=" + event
                        + " and errorRef=" + error);
        if (payload instanceof ReflectorPayload.ContinueStep continued) {
            require(continued.gapOrErrorRefs().contains(error),
                    "CHAIN_STEP_BLOCK_CONTINUATION_ERROR_REF_MISSING; "
                            + "expected gapOrErrorRefs to contain errorRef="
                            + error);
        }
        if (payload instanceof ReflectorPayload.TaskFailed failed) {
            require(failed.failureFactRefs().contains(event)
                            && failed.failureFactRefs().contains(error)
                            && failed.failureCategory().equals(
                            source.payload().failureCategory()),
                    "CHAIN_STEP_BLOCK_TASK_FAILED_BINDING_INVALID; expected "
                            + "failureFactRefs to contain event=" + event
                            + " and errorRef=" + error
                            + " and failureCategory="
                            + source.payload().failureCategory());
        }
    }

    private ChainReviewRuntime.CommitResult exactBoundReview(
            String taskId,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ReflectorPayload payload,
            ProductChainExecutorProgression.AcceptedStepBlock source,
            ChainPersistenceRecords.ProposalStateEventRecord bound) {
        require(bound.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                        && "REVIEW_DECISION".equals(
                        bound.officialAuthorityType()),
                "CHAIN_STEP_BLOCK_PROPOSAL_BOUND_ELSEWHERE");
        var decision = exactlyOne(
                workflow.findReviewDecisions(taskId).stream()
                        .filter(value -> value.reviewDecisionId().equals(
                                bound.officialAuthorityRef()))
                        .filter(value -> value.proposalId().equals(
                                proposal.proposalId()))
                        .filter(value -> value.reviewObjectType().equals(
                                "PROPOSAL_STATE"))
                        .filter(value -> value.reviewObjectId().equals(
                                source.acceptedState().eventId()))
                        .filter(value -> value.decisionKind()
                                == proposal.proposalKind())
                        .filter(value -> value.versionFenceSha256().equals(
                                sourceFence(source)))
                        .toList(),
                "CHAIN_STEP_BLOCK_BOUND_REVIEW_INVALID");
        return new ChainReviewRuntime.CommitResult(
                decision, true, successor(payload));
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            acceptedStates(String taskId, String proposalId) {
        var states = models.findProposalStateEvents(proposalId).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        require(!states.isEmpty() && states.size() <= 2,
                "CHAIN_STEP_BLOCK_REFLECTOR_STATE_INVALID");
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            require(state.taskId().equals(taskId)
                            && state.proposalId().equals(proposalId)
                            && state.stateSequence() == index + 1L,
                    "CHAIN_STEP_BLOCK_REFLECTOR_STATE_INVALID");
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_STEP_BLOCK_REFLECTOR_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        require(states.get(0).stateKind() == ChainProposalState.ACCEPTED,
                "CHAIN_STEP_BLOCK_REFLECTOR_NOT_ACCEPTED");
        return states;
    }

    private static ReflectorPayload decode(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        return (ReflectorPayload) new StrictChainProviderOutputParser().parse(
                encoded, ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, null).payload();
    }

    private static ChainReviewRuntime.SuccessorRequirement successor(
            ReflectorPayload payload) {
        return switch (payload.kind()) {
            case REFLECTOR_CONTINUE_STEP ->
                    ChainReviewRuntime.SuccessorRequirement.STEP_CONTINUATION;
            case REFLECTOR_REPLAN_REQUIRED ->
                    ChainReviewRuntime.SuccessorRequirement.PLAN_REVISION;
            case REFLECTOR_NEED_USER_INPUT ->
                    ChainReviewRuntime.SuccessorRequirement.USER_PENDING_ITEM;
            case REFLECTOR_NEED_PERMISSION ->
                    ChainReviewRuntime.SuccessorRequirement.PERMISSION_PENDING_ITEM;
            case REFLECTOR_TASK_FAILED ->
                    ChainReviewRuntime.SuccessorRequirement.FAILED_TASK_OUTCOME;
            default -> throw failure("CHAIN_STEP_BLOCK_REFLECTOR_KIND_INVALID");
        };
    }

    private static String sourceFence(
            ProductChainExecutorProgression.AcceptedStepBlock source) {
        return sha256(source.acceptedState().eventId() + "\0"
                + source.executorContext().requestDigest() + "\0"
                + source.proposal().payload().sha256());
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(
            List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"');
            String value = values.get(index);
            for (int cursor = 0; cursor < value.length(); cursor++) {
                char character = value.charAt(cursor);
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            json.append(String.format("\\u%04x",
                                    (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        }
        String encoded = json.append(']').toString();
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(encoded), encoded);
    }

    static void validateInvocationPrefix(
            String taskId, long invocationCut,
            List<ChainPersistenceRecords.ModelInvocationRecord> invocations) {
        require(invocationCut >= 0 && invocations.size() == invocationCut,
                "CHAIN_STEP_BLOCK_INVOCATION_PREFIX_INVALID");
        for (int index = 0; index < invocations.size(); index++) {
            require(invocations.get(index).taskId().equals(taskId)
                            && invocations.get(index).invocationOrdinal()
                            == index + 1L,
                    "CHAIN_STEP_BLOCK_INVOCATION_PREFIX_INVALID");
        }
    }

    private static String contextId(String taskId, String acceptedEventId) {
        return identity("context", taskId + "\0REFLECTOR\0" + CALL_REASON
                + "\0PROPOSAL_STATE\0" + acceptedEventId);
    }

    private static String invocationId(String contextId) {
        return identity("invocation", contextId);
    }

    private static <T> T exactlyOne(List<T> values, String code) {
        if (values.size() != 1) throw failure(code);
        return values.get(0);
    }

    private static String identity(String prefix, String material) {
        return prefix + "." + sha256(material);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw failure(code);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }
}
