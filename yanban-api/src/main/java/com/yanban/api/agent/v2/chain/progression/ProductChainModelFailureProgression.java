package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.api.ProductChainAnswerDeliveryProgression;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainNextRoleSelector;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.DefaultChainContextManager;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.ChainModelAuthorityBindingRepairException;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.review.ChainReviewRuntime;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded terminal successors for an exhausted model invocation lineage. */
@Component
public final class ProductChainModelFailureProgression {
    static final String ERROR_CODE = "MODEL_CALL_FAILED";
    static final String ANSWER_ERROR_CODE =
            "CHAIN_ANSWER_MODEL_CALL_FAILED";

    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFinalizationRepositoryAdapter finalization;
    private final ProductChainCompletedOutcomeAdapter outcomes;
    private final PlatformTransactionManager transactions;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainContextBuildFailureAuthority contextFailures;
    private final ProductChainAnswerDeliveryProgression answer;

    public ProductChainModelFailureProgression(
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainCompletedOutcomeAdapter outcomes,
            PlatformTransactionManager transactions,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            NamedParameterJdbcTemplate jdbc,
            ProductChainContextBuildFailureAuthority contextFailures,
            ProductChainAnswerDeliveryProgression answer) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.contextSources = Objects.requireNonNull(
                contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(
                modelCallIdentity, "modelCallIdentity");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.contextFailures = Objects.requireNonNull(
                contextFailures, "contextFailures");
        this.answer = Objects.requireNonNull(answer, "answer");
    }

    /** Resolves a rejected/stale formal-block review back to its exact source. */
    Optional<ChainRecoveryRuntime.NextDirective> formalRetryDirective(
            String taskId, String proposalStateEventId) {
        var state = models.findProposalStateEvent(proposalStateEventId)
                .orElseThrow(() -> failure(
                        "CHAIN_FORMAL_BLOCK_RETRY_STATE_MISSING"));
        var proposal = models.findProposal(state.proposalId())
                .orElseThrow(() -> failure(
                        "CHAIN_FORMAL_BLOCK_RETRY_PROPOSAL_MISSING"));
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_FORMAL_BLOCK_RETRY_INVOCATION_MISSING"));
        if (!"MODEL_CALL_FAILED_REVIEW".equals(invocation.callReason())
                && !"CONTEXT_BUILD_FAILURE_REVIEW".equals(
                invocation.callReason())
                && !"ACTION_FAILURE_REVIEW".equals(
                invocation.callReason())) {
            return Optional.empty();
        }
        require(state.taskId().equals(taskId)
                        && proposal.taskId().equals(taskId)
                        && invocation.taskId().equals(taskId)
                        && state.proposalId().equals(proposal.proposalId())
                        && proposal.invocationId().equals(
                        invocation.invocationId())
                        && proposal.role() == ChainRole.REFLECTOR
                        && invocation.role() == ChainRole.REFLECTOR
                        && invocation.workState()
                        == ChainWorkState.AWAITING_REVIEW,
                "CHAIN_FORMAL_BLOCK_RETRY_IDENTITY_INVALID");
        List<ChainPersistenceRecords.ProposalStateEventRecord> terminal =
                terminalRejectedState(taskId, proposal);
        require(terminal.size() == 1
                        && terminal.get(0).equals(state),
                "CHAIN_FORMAL_BLOCK_RETRY_STATE_INVALID");
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_FORMAL_BLOCK_RETRY_CONTEXT_MISSING"));
        require(invocation.contextRevisionId().equals(
                        context.contextRevisionId())
                        && invocation.completionToken().equals(
                        context.completionToken())
                        && invocation.callReason().equals(
                        context.callReason()),
                "CHAIN_FORMAL_BLOCK_RETRY_CONTEXT_INVALID");
        FormalBlockSource source = formalBlockForReviewContext(
                taskId, context);
        return Optional.of(new ChainRecoveryRuntime.NextDirective(
                ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW,
                source.sourceType(), source.sourceRef()));
    }

    public String invokeExecutorReview(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainRecoveryRuntime.NextDirective directive,
            Instant now) {
        Instant committedAt = Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS);
        require(directive.role() == ChainRole.REFLECTOR
                        && directive.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && "MODEL_FAILURE_STEP_BLOCK".equals(
                        directive.sourceAuthorityType()),
                "CHAIN_MODEL_FAILURE_REVIEW_DIRECTIVE_INVALID");
        StepBlockSource blocked = stepBlockSource(
                task.taskId(), directive.sourceAuthorityRef());
        require(instruction.instructionId().equals(
                        blocked.predecessor().instructionId()),
                "CHAIN_EXECUTOR_MODEL_FAILURE_SOURCE_INVALID");
        return invokeFormalReview(
                session, task, instruction, blocked, committedAt);
    }

    public String invokeContextReview(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainRecoveryRuntime.NextDirective directive,
            Instant now) {
        Instant committedAt = Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS);
        require(directive.role() == ChainRole.REFLECTOR
                        && directive.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && "CONTEXT_BUILD_FAILURE".equals(
                        directive.sourceAuthorityType()),
                "CHAIN_CONTEXT_FAILURE_REVIEW_DIRECTIVE_INVALID");
        var authority = contextFailures.read(
                task.taskId(), directive.sourceAuthorityRef());
        require(authority.context().role() == ChainRole.EXECUTOR
                        && instruction.instructionId().equals(
                        authority.failure().instructionId())
                        && !authority.successorContextPresent(),
                "CHAIN_CONTEXT_FAILURE_REVIEW_SOURCE_INVALID");
        return invokeFormalReview(session, task, instruction,
                contextBlockSource(authority), committedAt);
    }

    public String invokeActionFailureReview(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainRecoveryRuntime.NextDirective directive,
            Instant now) {
        Instant committedAt = Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS);
        require(directive.role() == ChainRole.REFLECTOR
                        && directive.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && "ACTION_RECEIPT_STEP_BLOCK".equals(
                        directive.sourceAuthorityType()),
                "CHAIN_ACTION_FAILURE_REVIEW_DIRECTIVE_INVALID");
        ActionBlockSource blocked = actionBlockSource(
                task.taskId(), directive.sourceAuthorityRef());
        require(instruction.instructionId().equals(
                        blocked.block().instructionId()),
                "CHAIN_ACTION_FAILURE_REVIEW_SOURCE_INVALID");
        return invokeFormalReview(
                session, task, instruction, blocked, committedAt);
    }

    private String invokeFormalReview(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            FormalBlockSource blocked,
            Instant now) {
        String contextId = blocked.reviewContextId();
        ProductChainModelCallIdentity.Binding identity = modelCallIdentity
                .bind(task.taskId(), contextId,
                        id("invocation", contextId));
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        var predecessor = blocked.predecessor();
        var building = new ChainPersistenceRecords.ContextRevisionRecord(
                identity.contextRevisionId(), task.taskId(),
                identity.parentContextRevisionId(), ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW,
                blocked.callReason(), instruction.instructionId(),
                predecessor.taskFrameId(), predecessor.planId(),
                predecessor.planRevisionId(),
                predecessor.planRevisionNumber(), predecessor.stepId(),
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
                new io.paperagent.v2.chain.context
                        .ChainContextFreezeRequest(building,
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw failure("CHAIN_FORMAL_FAILURE_REVIEW_CONTEXT_BLOCKED");
        }
        UserSettingsService.ModelEndpoint endpoint = settings
                .resolveModelEndpoint(task.userId(),
                        session.getModelProviderSnapshot(),
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
                    ProviderRoleOutput output =
                            new StrictChainProviderOutputParser().parse(
                                    raw, role, state, gap);
                    validateReviewPayload(
                            (ReflectorPayload) output.payload(), blocked);
                    return output;
                });
        ChainModelProtocolOutcome result = protocol.invoke(
                new ChainModelProtocolRequest(task.taskId(),
                        identity.invocationId(), identity.contextRevisionId(),
                        complete.context().revision().completionToken(),
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW,
                        blocked.callReason(), endpoint.providerKey(),
                        endpoint.modelName(), identity.invocationOrdinal(),
                        null, now));
        if (!(result instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().role() != ChainRole.REFLECTOR) {
            throw failure("CHAIN_FORMAL_FAILURE_REVIEW_PROPOSAL_MISSING");
        }
        validateReviewPayload(decode(ready.proposal()), blocked);
        new ProductChainProposalAdmissionAdapter(
                jdbc, transactions, models, models).admit(
                new ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), task.taskId(),
                        id("formal-failure-review-accepted",
                                ready.proposal().proposalId()),
                        true, null, ready.proposal().payload().sha256(), now));
        return ready.proposal().proposalId();
    }

    public boolean handlesReviewProposal(
            String taskId, String proposalId) {
        var proposal = models.findProposal(proposalId).orElse(null);
        if (proposal == null || !proposal.taskId().equals(taskId)
                || proposal.role() != ChainRole.REFLECTOR) return false;
        return models.findInvocation(proposal.invocationId())
                .filter(value -> value.taskId().equals(taskId))
                .filter(value -> "MODEL_CALL_FAILED_REVIEW".equals(
                        value.callReason())
                        || "CONTEXT_BUILD_FAILURE_REVIEW".equals(
                        value.callReason())
                        || "ACTION_FAILURE_REVIEW".equals(
                        value.callReason())).isPresent();
    }

    public ChainReviewRuntime.CommitResult consumeReview(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant now) {
        Instant committedAt = Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS);
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(ignored -> consumeReviewInTransaction(
                        task, instruction, proposalId, committedAt)));
    }

    private ChainReviewRuntime.CommitResult consumeReviewInTransaction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant now) {
        var proposal = models.findProposal(proposalId).orElseThrow(() ->
                failure("CHAIN_MODEL_FAILURE_REVIEW_PROPOSAL_MISSING"));
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_MODEL_FAILURE_REVIEW_INVOCATION_MISSING"));
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId()).orElseThrow(() ->
                failure("CHAIN_MODEL_FAILURE_REVIEW_CONTEXT_MISSING"));
        FormalBlockSource source = formalBlockForReviewContext(
                task.taskId(), context);
        validateReviewIdentity(task, instruction, proposal, invocation,
                context, source);
        ReflectorPayload payload = decode(proposal);
        validateReviewPayload(payload, source);
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        require(!states.isEmpty() && states.size() <= 2
                        && states.get(0).stateKind()
                        == ChainProposalState.ACCEPTED,
                "CHAIN_MODEL_FAILURE_REVIEW_STATE_INVALID");
        ChainReviewRuntime.CommitResult committed;
        if (states.size() == 2) {
            var bound = states.get(1);
            require(bound.stateKind()
                            == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                            && "REVIEW_DECISION".equals(
                            bound.officialAuthorityType()),
                    "CHAIN_MODEL_FAILURE_REVIEW_BOUND_ELSEWHERE");
            List<ChainPersistenceRecords.ReviewDecisionRecord> decisions =
                    workflow.findReviewDecisions(task.taskId()).stream()
                            .filter(value -> value.reviewDecisionId().equals(
                                    bound.officialAuthorityRef()))
                            .filter(value -> value.proposalId().equals(
                                    proposal.proposalId()))
                            .filter(value -> source.sourceType().equals(
                                    value.reviewObjectType()))
                            .filter(value -> value.reviewObjectId().equals(
                                    source.sourceRef()))
                            .filter(value -> value.versionFenceSha256().equals(
                                    source.versionFence()))
                            .toList();
            require(decisions.size() == 1,
                    "CHAIN_MODEL_FAILURE_REVIEW_REPLAY_INVALID");
            committed = new ChainReviewRuntime.CommitResult(
                    decisions.get(0), true, successor(payload));
        } else {
            ProductChainProposalAdmissionAdapter admission =
                    new ProductChainProposalAdmissionAdapter(
                            jdbc, transactions, models, models);
            ChainReviewRuntime runtime = new ChainReviewRuntime(
                    workflow, workflow,
                    ignored -> new ChainReviewRuntime.FormalReviewProposal(
                            proposal, states.get(0), payload,
                            source.versionFence()),
                    (ignoredTask, ignoredProposal, type, ref) ->
                            admission.replaceByOfficialResult(
                                    new ChainProposalAdmissionService
                                            .OfficialReplacement(
                                            proposal.proposalId(),
                                            task.taskId(),
                                            id("model-failure-review-bound",
                                                    ref),
                                            ChainPersistenceRecords
                                                    .ProposalOfficialAuthorityType
                                                    .REVIEW_DECISION,
                                            ref, null,
                                            proposal.payload().sha256(),
                                            now)));
            committed = runtime.commit(new ChainReviewRuntime.CommitRequest(
                    task.taskId(), proposal.proposalId(),
                    id("model-failure-review-event",
                            proposal.proposalId()),
                    source.sourceType(), source.sourceRef(), now));
        }
        if (payload instanceof ReflectorPayload.TaskFailed failed) {
            commitReviewFailedOutcome(task, instruction, source,
                    committed.decision(), failed, now);
        }
        return committed;
    }

    private void commitReviewFailedOutcome(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            FormalBlockSource source,
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            ReflectorPayload.TaskFailed payload,
            Instant now) {
        var context = source.predecessor();
        var draft = new ChainTaskOutcomeRuntime.OutcomeDraft(
                task.taskId(), id("task-outcome-event",
                source.sourceRef()), instruction.commandId(),
                instruction.instructionId(), context.taskFrameId(),
                context.planId(), context.planRevisionId(), canonicalArray(),
                canonicalArray(), context.candidateArtifactId(),
                Objects.toString(context.candidateFingerprint(),
                        ChainIdentity.NONE),
                Objects.toString(context.validationId(), ChainIdentity.NONE),
                null, null, null, null,
                canonicalArray(payload.unfinishedOrSkippedItems()),
                canonicalArray(payload.review().knownLimitations()),
                canonicalArray(payload.finalization().residualRisks()), now);
        outcomes.commit(new ChainTaskOutcomeRuntime.Failed(
                        draft, source.sourceRef(),
                        source.failureCategory(), source.failureCode()),
                new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
                    @Override public void verifyFailed(
                            ChainTaskOutcomeRuntime.Failed command) {
                        FormalBlockSource exact = reloadFormalSource(
                                task.taskId(), source);
                        require(sameFormalSource(exact, source)
                                        && decision.reviewObjectType().equals(
                                        source.sourceType())
                                        && decision.reviewObjectId().equals(
                                        source.sourceRef())
                                        && command.formalFailureSourceId()
                                        .equals(source.sourceRef())
                                        && command.failureCategory().equals(
                                        source.failureCategory())
                                        && command.failureCode().equals(
                                        source.failureCode())
                                        && decision.decisionKind()
                                        == ChainProposalKind
                                        .REFLECTOR_TASK_FAILED,
                                "CHAIN_MODEL_FAILURE_REVIEW_OUTCOME_INVALID");
                    }
                    @Override public void verifyCompleted(
                            ChainTaskOutcomeRuntime.Completed value) {
                        throw failure("CHAIN_MODEL_FAILURE_OUTCOME_KIND_INVALID");
                    }
                    @Override public void verifyCancelled(
                            ChainTaskOutcomeRuntime.Cancelled value) {
                        throw failure("CHAIN_MODEL_FAILURE_OUTCOME_KIND_INVALID");
                    }
                    @Override public void verifySuperseded(
                            ChainTaskOutcomeRuntime.Superseded value) {
                        throw failure("CHAIN_MODEL_FAILURE_OUTCOME_KIND_INVALID");
                    }
                });
    }

    public void advance(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainNextRoleSelector.MechanicalModelFailure selected,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(selected, "selected");
        Instant committedAt = Objects.requireNonNull(now, "now")
                .truncatedTo(ChronoUnit.MICROS);
        Failure source = failure(task.taskId(), selected.invocationId());
        require(source.invocation().role() == selected.failedRole(),
                "CHAIN_MODEL_FAILURE_SELECTION_INVALID");
        if (source.invocation().role() == ChainRole.EXECUTOR) {
            appendStepBlock(task, instruction, source, committedAt);
        } else if (source.invocation().role() == ChainRole.ANSWER) {
            new TransactionTemplate(transactions).executeWithoutResult(
                    ignored -> appendFailedDelivery(task, instruction,
                            selected, source, committedAt));
        } else {
            commitFailedOutcome(task, instruction, selected, source,
                    committedAt);
        }
    }

    private ChainPersistenceRecords.ModelFailureStepBlockRecord
            appendStepBlock(
                    ChainPersistenceRecords.TaskRecord task,
                    ChainPersistenceRecords.InstructionRecord instruction,
                    Failure source,
                    Instant now) {
        var context = source.context();
        require(context.taskFrameId() != null && context.planId() != null
                        && context.planRevisionId() != null
                        && context.planRevisionNumber() != null
                        && context.stepId() != null
                        && context.activationEventId() != null
                        && context.instructionId().equals(
                        instruction.instructionId()),
                "CHAIN_MODEL_FAILURE_STEP_IDENTITY_MISSING");
        String attemptRef = source.invocation().invocationId() + "#"
                + source.attempts().get(source.attempts().size() - 1)
                .attemptNo();
        String fence = stepBlockFence(source, attemptRef);
        String blockId = id("model-failure-step-block",
                source.invocation().invocationId());
        var requested = new ChainPersistenceRecords
                .ModelFailureStepBlockRecord(
                blockId, task.taskId(), id("model-failure-step-block-event",
                source.invocation().invocationId()),
                source.invocation().invocationId(),
                context.contextRevisionId(), instruction.instructionId(),
                context.taskFrameId(), context.planId(),
                context.planRevisionId(), context.planRevisionNumber(),
                context.stepId(), context.activationEventId(), attemptRef,
                "MODEL", ERROR_CODE, fence, now);
        var existing = workflow.findModelFailureStepBlocks(task.taskId())
                .stream().filter(value -> value.invocationId().equals(
                        source.invocation().invocationId())).toList();
        if (!existing.isEmpty()) {
            require(existing.size() == 1
                            && sameStepBlock(existing.get(0), requested),
                    "CHAIN_MODEL_FAILURE_STEP_BLOCK_REPLAY_INVALID");
            return existing.get(0);
        }
        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                requested.eventId(), requested.taskId(),
                "MODEL_FAILURE_STEP_BLOCK", null, fence, now);
        var appended = workflow.appendModelFailureStepBlock(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        event, requested));
        require(sameStepBlock(appended.fact(), requested),
                "CHAIN_MODEL_FAILURE_STEP_BLOCK_APPEND_INVALID");
        return appended.fact();
    }

    private void commitFailedOutcome(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainNextRoleSelector.MechanicalModelFailure selected,
            Failure source,
            Instant now) {
        var context = source.context();
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        task.taskId(), id("task-outcome-event",
                        source.invocation().invocationId()),
                        instruction.commandId(), instruction.instructionId(),
                        context.taskFrameId(), context.planId(),
                        context.planRevisionId(), canonicalArray(),
                        canonicalArray(), context.candidateArtifactId(),
                        Objects.toString(context.candidateFingerprint(),
                                ChainIdentity.NONE),
                        Objects.toString(context.validationId(),
                                ChainIdentity.NONE),
                        null, null, null, null, canonicalArray(),
                        canonicalArray(), canonicalArray(), now);
        outcomes.commit(new ChainTaskOutcomeRuntime.Failed(
                        draft, source.invocation().invocationId(),
                        "MODEL", ERROR_CODE),
                new FailureVerifier(task.taskId(), selected, source));
    }

    private void appendFailedDelivery(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainNextRoleSelector.MechanicalModelFailure selected,
            Failure source,
            Instant now) {
        require(source.invocation().role() == ChainRole.ANSWER,
                "CHAIN_ANSWER_MODEL_FAILURE_SOURCE_INVALID");
        String type = selected.sourceAuthorityType();
        String ref = selected.sourceAuthorityRef();
        require(formalSourceExists(task.taskId(), type, ref),
                "CHAIN_ANSWER_MODEL_FAILURE_DELIVERY_SOURCE_INVALID");
        if ("TASK_OUTCOME".equals(type)) {
            answer.deliverOutcomeFallback(
                    task, instruction, ref, ANSWER_ERROR_CODE, now);
            return;
        }
        String deliveryId = ProductChainNextRoleSelector
                .modelFailureDeliveryId(source.invocation().invocationId());
        String eventId = id("delivery-event", deliveryId);
        ChainPersistenceRecords.DeliveryRecord delivery =
                new ChainPersistenceRecords.DeliveryRecord(
                        deliveryId, task.taskId(), eventId,
                        instruction.commandId(),
                        "ROUTE_DECISION".equals(type) ? ref : null,
                        "TASK_OUTCOME".equals(type) ? ref : null,
                        "PENDING_ITEM".equals(type) ? ref : null,
                        null, null, null, now);
        appendDelivery(delivery, sha256(type + "\0" + ref + "\0"
                + source.invocation().invocationId()));
        appendDeliveryEvent(delivery, 1L, 1,
                ChainDeliveryStatus.DELIVERY_FAILED,
                ANSWER_ERROR_CODE,
                source.invocation().runtimePolicyVersion(), now);
    }

    private boolean formalSourceExists(
            String taskId, String type, String ref) {
        return switch (type) {
            case "TASK_OUTCOME" -> finalization.findTaskOutcome(taskId)
                    .filter(value -> value.outcomeId().equals(ref)).isPresent();
            case "PENDING_ITEM" -> workflow.findPendingItems(taskId).stream()
                    .anyMatch(value -> value.gapId().equals(ref));
            case "ROUTE_DECISION" -> workflow.findRouteDecisions(taskId)
                    .stream().anyMatch(value ->
                            value.routeDecisionId().equals(ref));
            default -> false;
        };
    }

    private void appendDelivery(
            ChainPersistenceRecords.DeliveryRecord delivery,
            String sourceDigest) {
        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                delivery.eventId(), delivery.taskId(), "DELIVERY", null,
                sourceDigest, delivery.createdAt());
        var result = finalization.appendDelivery(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        event, delivery));
        require(result.fact().equals(delivery)
                        && result.event().eventId().equals(event.eventId()),
                "CHAIN_ANSWER_MODEL_FAILURE_DELIVERY_REPLAY_INVALID");
    }

    private void appendDeliveryEvent(
            ChainPersistenceRecords.DeliveryRecord delivery,
            long sequence,
            int attempt,
            ChainDeliveryStatus status,
            String errorCode,
            String runtimePolicyVersion,
            Instant now) {
        String eventId = id("delivery-state",
                delivery.deliveryId() + "\0" + sequence);
        var record = new ChainPersistenceRecords.DeliveryEventRecord(
                delivery.deliveryId(), sequence, delivery.taskId(), eventId,
                status, attempt, errorCode,
                ChainRuntimePolicy.requireVersion(runtimePolicyVersion)
                        .policyVersion(), now);
        var event = new ChainPersistenceRecords.AuthorityEventRequest(
                eventId, delivery.taskId(), "DELIVERY_" + status.name(),
                null, sha256(delivery.deliveryId() + "\0" + status
                + "\0" + attempt), now);
        var result = finalization.appendDeliveryEvent(
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        event, record));
        require(result.fact().equals(record),
                "CHAIN_ANSWER_MODEL_FAILURE_EVENT_REPLAY_INVALID");
    }

    private Failure failure(String taskId, String invocationId) {
        var invocation = models.findInvocation(invocationId).orElseThrow(
                () -> failure("CHAIN_MODEL_FAILURE_INVOCATION_MISSING"));
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId()).orElseThrow(
                () -> failure("CHAIN_MODEL_FAILURE_CONTEXT_MISSING"));
        List<ChainPersistenceRecords.ModelInvocationRecord> lineage = models
                .findInvocationsByContextRevisionId(taskId,
                        context.contextRevisionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ModelInvocationRecord
                                ::invocationOrdinal)).toList();
        List<ChainPersistenceRecords.ProviderAttemptRecord> attempts = models
                .findProviderAttempts(invocation.invocationId());
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                invocation.runtimePolicyVersion());
        require(invocation.taskId().equals(taskId)
                        && context.taskId().equals(taskId)
                        && context.contextRevisionId().equals(
                        invocation.contextRevisionId())
                        && context.role() == invocation.role()
                        && context.workState() == invocation.workState()
                        && context.callReason().equals(invocation.callReason())
                        && Objects.equals(context.completionToken(),
                        invocation.completionToken())
                        && lineage.size() == runtimePolicy
                        .modelInvocationsPerContextTotal()
                        && lineage.get(lineage.size() - 1).equals(invocation)
                        && attempts.size() == runtimePolicy
                        .providerAttemptsTotal()
                        && models.findProposalByInvocation(invocationId)
                        .isEmpty(),
                "CHAIN_MODEL_FAILURE_SOURCE_INVALID");
        for (var value : lineage) {
            require(value.taskId().equals(taskId)
                            && value.contextRevisionId().equals(
                            context.contextRevisionId())
                            && value.completionToken().equals(
                            invocation.completionToken())
                            && value.role() == invocation.role()
                            && value.workState() == invocation.workState()
                            && value.callReason().equals(
                            invocation.callReason())
                            && value.provider().equals(invocation.provider())
                            && value.model().equals(invocation.model())
                            && value.runtimePolicyVersion().equals(
                            invocation.runtimePolicyVersion())
                            && models.findProposalByInvocation(
                            value.invocationId()).isEmpty()
                            && failedAttempts(taskId, value).size()
                            == runtimePolicy.providerAttemptsTotal(),
                    "CHAIN_MODEL_FAILURE_LINEAGE_INVALID");
        }
        return new Failure(context, invocation,
                failedAttempts(taskId, invocation));
    }

    private List<ChainPersistenceRecords.ProviderAttemptRecord>
            failedAttempts(
                    String taskId,
                    ChainPersistenceRecords.ModelInvocationRecord invocation) {
        List<ChainPersistenceRecords.ProviderAttemptRecord> attempts = models
                .findProviderAttempts(invocation.invocationId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ProviderAttemptRecord
                                ::attemptNo)).toList();
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            require(attempt.taskId().equals(taskId)
                            && attempt.invocationId().equals(
                            invocation.invocationId())
                            && attempt.attemptNo() == index + 1
                            && failed(attempt),
                    "CHAIN_MODEL_FAILURE_ATTEMPT_PREFIX_INVALID");
        }
        return attempts;
    }

    private StepBlockSource stepBlockSource(
            String taskId, String stepBlockId) {
        List<ChainPersistenceRecords.ModelFailureStepBlockRecord> matches =
                workflow.findModelFailureStepBlocks(taskId).stream()
                        .filter(value -> value.stepBlockId().equals(
                                stepBlockId)).toList();
        require(matches.size() == 1,
                "CHAIN_MODEL_FAILURE_STEP_BLOCK_MISSING");
        var block = matches.get(0);
        Failure source = failure(taskId, block.invocationId());
        String attemptRef = block.invocationId() + "#"
                + source.attempts().get(source.attempts().size() - 1)
                .attemptNo();
        var expected = new ChainPersistenceRecords
                .ModelFailureStepBlockRecord(
                block.stepBlockId(), block.taskId(), block.eventId(),
                block.invocationId(), source.context().contextRevisionId(),
                source.context().instructionId(),
                source.context().taskFrameId(), source.context().planId(),
                source.context().planRevisionId(),
                source.context().planRevisionNumber(),
                source.context().stepId(),
                source.context().activationEventId(), attemptRef,
                "MODEL", ERROR_CODE, stepBlockFence(source, attemptRef),
                block.createdAt());
        require(sameStepBlock(block, expected),
                "CHAIN_MODEL_FAILURE_STEP_BLOCK_SOURCE_INVALID");
        return new StepBlockSource(block, source);
    }

    private ActionBlockSource actionBlockSource(
            String taskId, String stepBlockId) {
        List<ChainPersistenceRecords.ActionReceiptStepBlockRecord> matches =
                workflow.findActionReceiptStepBlocks(taskId).stream()
                        .filter(value -> value.stepBlockId().equals(
                                stepBlockId)).toList();
        require(matches.size() == 1,
                "CHAIN_ACTION_FAILURE_STEP_BLOCK_MISSING");
        var block = matches.get(0);
        var proposal = models.findProposal(block.repairProposalId())
                .orElseThrow(() -> failure(
                        "CHAIN_ACTION_FAILURE_REPAIR_PROPOSAL_MISSING"));
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_ACTION_FAILURE_REPAIR_INVOCATION_MISSING"));
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_ACTION_FAILURE_REPAIR_CONTEXT_MISSING"));
        require(block.taskId().equals(taskId)
                        && proposal.taskId().equals(taskId)
                        && proposal.role() == ChainRole.EXECUTOR
                        && (proposal.proposalKind()
                        == ChainProposalKind.EXECUTOR_TOOL_ACTION
                        || proposal.proposalKind()
                        == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE)
                        && proposal.payload().sha256().equals(
                        block.repairProposalSignatureSha256())
                        && invocation.taskId().equals(taskId)
                        && invocation.role() == ChainRole.EXECUTOR
                        && invocation.contextRevisionId().equals(
                        context.contextRevisionId())
                        && context.contextRevisionId().equals(
                        block.repairContextRevisionId())
                        && context.status()
                        == ChainContextRevisionStatus.COMPLETE
                        && context.instructionId().equals(
                        block.instructionId())
                        && Objects.equals(context.taskFrameId(),
                        block.taskFrameId())
                        && Objects.equals(context.planId(), block.planId())
                        && Objects.equals(context.planRevisionId(),
                        block.planRevisionId())
                        && Objects.equals(context.planRevisionNumber(),
                        block.planRevisionNumber())
                        && Objects.equals(context.stepId(), block.stepId())
                        && Objects.equals(context.activationEventId(),
                        block.activationEventId()),
                "CHAIN_ACTION_FAILURE_STEP_BLOCK_SOURCE_INVALID");
        return new ActionBlockSource(block, context);
    }

    private FormalBlockSource formalBlockForReviewContext(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        requireReviewContext(taskId, context);
        List<ChainPersistenceRecords.ContextRevisionRecord> reverse =
                new ArrayList<>();
        Set<String> visited = new HashSet<>();
        var cursor = context;
        FormalBlockSource source;
        while (true) {
            require(visited.add(cursor.contextRevisionId()),
                    "CHAIN_FORMAL_BLOCK_REVIEW_CONTEXT_CYCLE");
            reverse.add(cursor);
            String parentId = cursor.parentContextRevisionId();
            require(parentId != null,
                    "CHAIN_FORMAL_BLOCK_REVIEW_CONTEXT_SOURCE_INVALID");
            source = directFormalSource(taskId, cursor.callReason(), parentId);
            if (source != null) break;
            var parent = contexts.findContextRevision(parentId)
                    .orElseThrow(() -> failure(
                            "CHAIN_FORMAL_BLOCK_REVIEW_PARENT_MISSING"));
            requireReviewContext(taskId, parent);
            require(sameReviewBoundary(parent, cursor),
                    "CHAIN_FORMAL_BLOCK_REVIEW_BOUNDARY_CHANGED");
            cursor = parent;
        }
        Collections.reverse(reverse);
        var root = reverse.get(0);
        require(root.contextRevisionId().equals(source.reviewContextId())
                        && root.parentContextRevisionId().equals(
                        source.predecessor().contextRevisionId()),
                "CHAIN_FORMAL_BLOCK_REVIEW_ROOT_INVALID");
        for (int index = 1; index < reverse.size(); index++) {
            var parent = reverse.get(index - 1);
            var child = reverse.get(index);
            String expected = rejectedRetryContextId(
                    taskId, source.reviewContextId(), parent);
            require(child.contextRevisionId().equals(expected)
                            && child.parentContextRevisionId().equals(
                            parent.contextRevisionId()),
                    "CHAIN_FORMAL_BLOCK_REVIEW_RETRY_LINEAGE_INVALID");
        }
        return source;
    }

    private FormalBlockSource directFormalSource(
            String taskId, String callReason, String parentContextId) {
        if ("MODEL_CALL_FAILED_REVIEW".equals(callReason)) {
            List<ChainPersistenceRecords.ModelFailureStepBlockRecord> matches =
                    workflow.findModelFailureStepBlocks(taskId).stream()
                            .filter(value -> value.contextRevisionId().equals(
                                    parentContextId)).toList();
            if (matches.isEmpty()) return null;
            require(matches.size() == 1,
                    "CHAIN_MODEL_FAILURE_REVIEW_CONTEXT_SOURCE_INVALID");
            return stepBlockSource(taskId, matches.get(0).stepBlockId());
        }
        if ("ACTION_FAILURE_REVIEW".equals(callReason)) {
            List<ChainPersistenceRecords.ActionReceiptStepBlockRecord>
                    matches = workflow.findActionReceiptStepBlocks(taskId)
                    .stream().filter(value -> models.findProposal(
                            value.repairProposalId()).stream().anyMatch(
                            proposal -> models.findInvocation(
                                    proposal.invocationId()).stream().anyMatch(
                                    invocation -> invocation.contextRevisionId()
                                            .equals(parentContextId))))
                    .toList();
            if (matches.isEmpty()) return null;
            require(matches.size() == 1,
                    "CHAIN_ACTION_FAILURE_REVIEW_CONTEXT_SOURCE_INVALID");
            return actionBlockSource(taskId,
                    matches.get(0).stepBlockId());
        }
        require("CONTEXT_BUILD_FAILURE_REVIEW".equals(callReason),
                "CHAIN_FORMAL_BLOCK_REVIEW_CONTEXT_SOURCE_INVALID");
        var failure = contexts.findContextBuildFailure(parentContextId)
                .orElse(null);
        return failure == null ? null : contextBlockSource(
                contextFailures.read(
                        taskId, failure.contextBuildFailureId()));
    }

    private void requireReviewContext(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        require(context.taskId().equals(taskId)
                        && context.status()
                        == ChainContextRevisionStatus.COMPLETE
                        && context.role() == ChainRole.REFLECTOR
                        && context.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && ("MODEL_CALL_FAILED_REVIEW".equals(
                        context.callReason())
                        || "CONTEXT_BUILD_FAILURE_REVIEW".equals(
                        context.callReason())
                        || "ACTION_FAILURE_REVIEW".equals(
                        context.callReason())),
                "CHAIN_FORMAL_BLOCK_REVIEW_CONTEXT_INVALID");
    }

    private static boolean sameReviewBoundary(
            ChainPersistenceRecords.ContextRevisionRecord left,
            ChainPersistenceRecords.ContextRevisionRecord right) {
        return left.taskId().equals(right.taskId())
                && left.role() == right.role()
                && left.workState() == right.workState()
                && left.callReason().equals(right.callReason())
                && left.instructionId().equals(right.instructionId())
                && Objects.equals(left.taskFrameId(), right.taskFrameId())
                && Objects.equals(left.planId(), right.planId())
                && Objects.equals(left.planRevisionId(),
                right.planRevisionId())
                && Objects.equals(left.planRevisionNumber(),
                right.planRevisionNumber())
                && Objects.equals(left.stepId(), right.stepId())
                && Objects.equals(left.activationEventId(),
                right.activationEventId())
                && Objects.equals(left.projectId(), right.projectId())
                && Objects.equals(left.projectVersion(),
                right.projectVersion())
                && Objects.equals(left.workspaceId(), right.workspaceId())
                && Objects.equals(left.candidateArtifactId(),
                right.candidateArtifactId())
                && Objects.equals(left.candidateFingerprint(),
                right.candidateFingerprint())
                && Objects.equals(left.validationId(), right.validationId())
                && Objects.equals(left.validationRequestDigest(),
                right.validationRequestDigest())
                && Objects.equals(left.validationReceiptDigest(),
                right.validationReceiptDigest())
                && left.projectorSetVersion().equals(
                right.projectorSetVersion())
                && left.paginationVersion().equals(right.paginationVersion())
                && left.runtimePolicyVersion().equals(
                right.runtimePolicyVersion());
    }

    private String rejectedRetryContextId(
            String taskId,
            String rootContextId,
            ChainPersistenceRecords.ContextRevisionRecord parent) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> rejected =
                new ArrayList<>();
        for (var invocation : models.findInvocationsByContextRevisionId(
                taskId, parent.contextRevisionId())) {
            require(invocation.taskId().equals(taskId)
                            && invocation.contextRevisionId().equals(
                            parent.contextRevisionId()),
                    "CHAIN_FORMAL_BLOCK_REVIEW_INVOCATION_INVALID");
            if (invocation.role() != ChainRole.REFLECTOR
                    || invocation.workState()
                    != ChainWorkState.AWAITING_REVIEW
                    || !invocation.callReason().equals(parent.callReason())
                    || !invocation.completionToken().equals(
                    parent.completionToken())) {
                continue;
            }
            var proposal = models.findProposalByInvocation(
                    invocation.invocationId()).orElse(null);
            if (proposal == null) continue;
            require(proposal.taskId().equals(taskId)
                            && proposal.invocationId().equals(
                            invocation.invocationId())
                            && proposal.role() == ChainRole.REFLECTOR,
                    "CHAIN_FORMAL_BLOCK_REVIEW_PROPOSAL_INVALID");
            rejected.addAll(terminalRejectedState(taskId, proposal));
        }
        require(rejected.size() == 1,
                "CHAIN_FORMAL_BLOCK_REVIEW_REJECTED_SOURCE_INVALID");
        return retryContextIdentity(rootContextId,
                rejected.get(0).eventId());
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            terminalRejectedState(
                    String taskId,
                    ChainPersistenceRecords.ModelProposalRecord proposal) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposal.proposalId()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            require(state.taskId().equals(taskId)
                            && state.proposalId().equals(
                            proposal.proposalId())
                            && state.stateSequence() == index + 1L,
                    "CHAIN_FORMAL_BLOCK_REVIEW_PROPOSAL_STATE_INVALID");
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure(
                        "CHAIN_FORMAL_BLOCK_REVIEW_PROPOSAL_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        if (states.isEmpty()) return List.of();
        var latest = states.get(states.size() - 1);
        return latest.stateKind() == ChainProposalState.REJECTED
                || latest.stateKind() == ChainProposalState.STALE
                ? List.of(latest) : List.of();
    }

    private static String retryContextIdentity(
            String rootContextId, String proposalStateEventId) {
        return id("context", "proposal-retry\0" + rootContextId + "\0"
                + proposalStateEventId);
    }

    private ContextBlockSource contextBlockSource(
            ProductChainContextBuildFailureAuthority.Source source) {
        var context = source.context();
        var failure = source.failure();
        String fence = sha256(failure.contextBuildFailureId() + "\0"
                + context.contextRevisionId() + "\0"
                + context.instructionId() + "\0" + context.taskFrameId()
                + "\0" + context.planId() + "\0"
                + context.planRevisionId() + "\0"
                + context.planRevisionNumber() + "\0" + context.stepId()
                + "\0" + context.activationEventId());
        return new ContextBlockSource(source, fence);
    }

    private FormalBlockSource reloadFormalSource(
            String taskId, FormalBlockSource source) {
        if (source instanceof StepBlockSource value) {
            return stepBlockSource(taskId, value.block().stepBlockId());
        }
        if (source instanceof ActionBlockSource value) {
            return actionBlockSource(taskId, value.block().stepBlockId());
        }
        return contextBlockSource(contextFailures.read(
                taskId, source.sourceRef()));
    }

    private static boolean sameFormalSource(
            FormalBlockSource left, FormalBlockSource right) {
        return left.sourceType().equals(right.sourceType())
                && left.sourceRef().equals(right.sourceRef())
                && left.predecessor().equals(right.predecessor())
                && left.versionFence().equals(right.versionFence())
                && left.failureCategory().equals(right.failureCategory())
                && left.failureCode().equals(right.failureCode());
    }

    private void validateReviewIdentity(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context,
            FormalBlockSource source) {
        FormalBlockSource resolved = formalBlockForReviewContext(
                task.taskId(), context);
        require(sameFormalSource(resolved, source)
                        && proposal.taskId().equals(task.taskId())
                        && proposal.role() == ChainRole.REFLECTOR
                        && proposal.proposalKind().role()
                        == ChainRole.REFLECTOR
                        && proposal.invocationId().equals(
                        invocation.invocationId())
                        && invocation.taskId().equals(task.taskId())
                        && invocation.role() == ChainRole.REFLECTOR
                        && invocation.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && source.callReason().equals(invocation.callReason())
                        && invocation.contextRevisionId().equals(
                        context.contextRevisionId())
                        && invocation.completionToken().equals(
                        context.completionToken())
                        && context.status()
                        == ChainContextRevisionStatus.COMPLETE
                        && context.instructionId().equals(
                        instruction.instructionId())
                        && Objects.equals(context.taskFrameId(),
                        source.predecessor().taskFrameId())
                        && Objects.equals(context.planId(),
                        source.predecessor().planId())
                        && Objects.equals(context.planRevisionId(),
                        source.predecessor().planRevisionId())
                        && Objects.equals(context.planRevisionNumber(),
                        source.predecessor().planRevisionNumber())
                        && Objects.equals(context.stepId(),
                        source.predecessor().stepId())
                        && Objects.equals(context.activationEventId(),
                        source.predecessor().activationEventId()),
                "CHAIN_MODEL_FAILURE_REVIEW_IDENTITY_INVALID");
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

    private static void validateReviewPayload(
            ReflectorPayload payload, FormalBlockSource source) {
        require(payload.kind() == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_TASK_FAILED,
                "CHAIN_MODEL_FAILURE_REVIEW_KIND_INVALID; only "
                        + "REPLAN_REQUIRED or TASK_FAILED may review a "
                        + "model failure step block");
        requireFormalFailureReviewBinding(payload, source.sourceRef(),
                source.directFactRefs());
        if (payload instanceof ReflectorPayload.TaskFailed failed) {
            require(failed.failureFactRefs().contains(
                            source.sourceRef())
                            && failed.failureCategory().equals(
                            source.failureCategory()),
                    "CHAIN_MODEL_FAILURE_TASK_FAILED_BINDING_INVALID; "
                            + "expected failureFactRefs to contain sourceRef="
                            + source.sourceRef() + " and failureCategory="
                            + source.failureCategory());
        }
    }

    static void requireFormalFailureReviewBinding(
            ReflectorPayload payload, String reviewedObjectRef,
            List<String> directFactRefs) {
        if (payload.review().reviewedObjectRefs().contains(reviewedObjectRef)
                && payload.review().directFactRefs().containsAll(
                directFactRefs)) {
            return;
        }
        throw new ChainModelAuthorityBindingRepairException(
                "CHAIN_MODEL_FAILURE_REVIEW_FACT_REF_MISSING",
                "review.reviewedObjectRefs", reviewedObjectRef,
                "review.directFactRefs", directFactRefs);
    }

    private static ChainReviewRuntime.SuccessorRequirement successor(
            ReflectorPayload payload) {
        return payload.kind()
                == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                ? ChainReviewRuntime.SuccessorRequirement.PLAN_REVISION
                : ChainReviewRuntime.SuccessorRequirement
                .FAILED_TASK_OUTCOME;
    }

    private static boolean failed(
            ChainPersistenceRecords.ProviderAttemptRecord attempt) {
        boolean statuses = attempt.schemaValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.NOT_RUN
                && attempt.proposalValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.NOT_RUN
                || attempt.schemaValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.FAILED
                && attempt.proposalValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.NOT_RUN
                || attempt.schemaValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.PASSED
                && attempt.proposalValidationStatus()
                == ChainPersistenceRecords.ValidationStatus.FAILED;
        return statuses && attempt.errorCode() != null
                && !attempt.errorCode().isBlank();
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray() {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256("[]"), "[]");
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(
            List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"').append(values.get(index)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r"))
                    .append('"');
        }
        String body = json.append(']').toString();
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(body), body);
    }

    private static String stepBlockFence(
            Failure source, String attemptRef) {
        var context = source.context();
        return sha256(source.invocation().invocationId() + "\0"
                + context.contextRevisionId() + "\0"
                + context.instructionId() + "\0" + context.taskFrameId()
                + "\0" + context.planId() + "\0"
                + context.planRevisionId() + "\0"
                + context.planRevisionNumber() + "\0" + context.stepId()
                + "\0" + context.activationEventId() + "\0" + attemptRef);
    }

    private static boolean sameStepBlock(
            ChainPersistenceRecords.ModelFailureStepBlockRecord left,
            ChainPersistenceRecords.ModelFailureStepBlockRecord right) {
        return left.stepBlockId().equals(right.stepBlockId())
                && left.taskId().equals(right.taskId())
                && left.eventId().equals(right.eventId())
                && left.invocationId().equals(right.invocationId())
                && left.contextRevisionId().equals(right.contextRevisionId())
                && left.instructionId().equals(right.instructionId())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.planRevisionNumber() == right.planRevisionNumber()
                && left.stepId().equals(right.stepId())
                && left.activationEventId().equals(right.activationEventId())
                && left.lastProviderAttemptRef().equals(
                right.lastProviderAttemptRef())
                && left.failureCategory().equals(right.failureCategory())
                && left.failureCode().equals(right.failureCode())
                && left.versionFenceSha256().equals(
                right.versionFenceSha256());
    }

    private static String id(String kind, String source) {
        return kind + "." + sha256(kind + "\0" + source);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw failure(code);
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private record Failure(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts) {
        private Failure {
            attempts = List.copyOf(attempts);
        }
    }

    private sealed interface FormalBlockSource permits StepBlockSource,
            ContextBlockSource, ActionBlockSource {
        String sourceType();
        String sourceRef();
        ChainPersistenceRecords.ContextRevisionRecord predecessor();
        String versionFence();
        String failureCategory();
        String failureCode();
        List<String> directFactRefs();
        String reviewContextId();
        String callReason();
    }

    private record StepBlockSource(
            ChainPersistenceRecords.ModelFailureStepBlockRecord block,
            Failure failure) implements FormalBlockSource {
        @Override public String sourceType() {
            return "MODEL_FAILURE_STEP_BLOCK";
        }
        @Override public String sourceRef() { return block.stepBlockId(); }
        @Override public ChainPersistenceRecords.ContextRevisionRecord
                predecessor() { return failure.context(); }
        @Override public String versionFence() {
            return block.versionFenceSha256();
        }
        @Override public String failureCategory() {
            return block.failureCategory();
        }
        @Override public String failureCode() { return block.failureCode(); }
        @Override public List<String> directFactRefs() {
            return List.of(block.stepBlockId(), block.invocationId(),
                    block.lastProviderAttemptRef());
        }
        @Override public String reviewContextId() {
            return id("context-model-failure-review", block.stepBlockId());
        }
        @Override public String callReason() {
            return "MODEL_CALL_FAILED_REVIEW";
        }
    }

    private record ContextBlockSource(
            ProductChainContextBuildFailureAuthority.Source source,
            String versionFence) implements FormalBlockSource {
        @Override public String sourceType() {
            return "CONTEXT_BUILD_FAILURE";
        }
        @Override public String sourceRef() {
            return source.failure().contextBuildFailureId();
        }
        @Override public ChainPersistenceRecords.ContextRevisionRecord
                predecessor() { return source.context(); }
        @Override public String failureCategory() { return "CONTEXT"; }
        @Override public String failureCode() {
            return source.failure().errorCode();
        }
        @Override public List<String> directFactRefs() {
            return List.of(source.failure().contextBuildFailureId(),
                    source.context().contextRevisionId());
        }
        @Override public String reviewContextId() {
            return id("context-build-failure-review", sourceRef());
        }
        @Override public String callReason() {
            return "CONTEXT_BUILD_FAILURE_REVIEW";
        }
    }

    private record ActionBlockSource(
            ChainPersistenceRecords.ActionReceiptStepBlockRecord block,
            ChainPersistenceRecords.ContextRevisionRecord predecessor)
            implements FormalBlockSource {
        @Override public String sourceType() {
            return "ACTION_RECEIPT_STEP_BLOCK";
        }
        @Override public String sourceRef() { return block.stepBlockId(); }
        @Override public String versionFence() {
            return block.blockIdentityDigestSha256();
        }
        @Override public String failureCategory() {
            return block.failureCategory();
        }
        @Override public String failureCode() { return block.failureCode(); }
        @Override public List<String> directFactRefs() {
            return List.of(block.stepBlockId(), block.actionId(),
                    block.failureAuthorityRef(), block.repairProposalId());
        }
        @Override public String reviewContextId() {
            return id("context-action-failure-review",
                    block.stepBlockId());
        }
        @Override public String callReason() {
            return "ACTION_FAILURE_REVIEW";
        }
    }

    private final class FailureVerifier
            implements ChainTaskOutcomeRuntime.FormalSourceVerifier {
        private final String taskId;
        private final ProductChainNextRoleSelector.MechanicalModelFailure
                selected;
        private final Failure expected;

        private FailureVerifier(
                String taskId,
                ProductChainNextRoleSelector.MechanicalModelFailure selected,
                Failure expected) {
            this.taskId = taskId;
            this.selected = selected;
            this.expected = expected;
        }

        @Override public void verifyFailed(
                ChainTaskOutcomeRuntime.Failed command) {
            Failure exact = failure(taskId, selected.invocationId());
            require(exact.equals(expected)
                            && command.formalFailureSourceId().equals(
                            selected.invocationId())
                            && command.failureCategory().equals("MODEL")
                            && command.failureCode().equals(ERROR_CODE),
                    "CHAIN_MODEL_FAILURE_OUTCOME_SOURCE_INVALID");
        }

        @Override public void verifyCompleted(
                ChainTaskOutcomeRuntime.Completed command) {
            throw failure("CHAIN_MODEL_FAILURE_OUTCOME_KIND_INVALID");
        }

        @Override public void verifyCancelled(
                ChainTaskOutcomeRuntime.Cancelled command) {
            throw failure("CHAIN_MODEL_FAILURE_OUTCOME_KIND_INVALID");
        }

        @Override public void verifySuperseded(
                ChainTaskOutcomeRuntime.Superseded command) {
            throw failure("CHAIN_MODEL_FAILURE_OUTCOME_KIND_INVALID");
        }
    }
}
