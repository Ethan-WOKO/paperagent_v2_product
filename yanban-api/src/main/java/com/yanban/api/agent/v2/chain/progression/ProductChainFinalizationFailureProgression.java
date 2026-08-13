package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainFinalizationRecoverySource;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
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

/**
 * Reflects one exact formal FinalizationCheck or PublishFailure and commits
 * its existing ReviewDecision/TaskOutcome successors.  This owner never
 * interprets exception text and never substitutes a Candidate step review.
 */
@Component
public final class ProductChainFinalizationFailureProgression {
    static final String CALL_REASON = "FINALIZATION_FAILURE_REVIEW";

    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainFinalizationRepositoryAdapter finalization;
    private final ProductChainFinalizationRecoverySource recovery;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;
    private final ProductChainCompletedOutcomeAdapter outcomes;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final PlatformTransactionManager transactions;
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainFinalizationFailureProgression(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainFinalizationRecoverySource recovery,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity,
            ProductChainCompletedOutcomeAdapter outcomes,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            PlatformTransactionManager transactions,
            NamedParameterJdbcTemplate jdbc) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.contextSources = Objects.requireNonNull(contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(modelCallIdentity, "modelCallIdentity");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
                        && directive.workState() == ChainWorkState.AWAITING_REVIEW,
                "CHAIN_FINALIZATION_FAILURE_REFLECTOR_DIRECTIVE_INVALID");
        FailureSource source = exactSource(
                task.taskId(), directive.sourceAuthorityType(),
                directive.sourceAuthorityRef());
        require(instruction.instructionId().equals(
                        source.readiness().instructionId()),
                "CHAIN_FINALIZATION_FAILURE_INSTRUCTION_STALE");
        ChainPersistenceRecords.ContextRevisionRecord predecessor =
                predecessorContext(source);
        ChainPersistenceRecords.PlanBindingRecord binding = binding(source);
        String contextId = contextId(task.taskId(), source.type(), source.ref());
        ProductChainModelCallIdentity.Binding identity = modelCallIdentity.bind(
                task.taskId(), contextId, invocationId(contextId));
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
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
            throw failure("CHAIN_FINALIZATION_FAILURE_CONTEXT_BLOCKED");
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
            throw failure("CHAIN_FINALIZATION_FAILURE_PROPOSAL_MISSING");
        }
        ReflectorPayload payload = decode(ready.proposal());
        validatePayload(payload, source);
        new ProductChainProposalAdmissionAdapter(
                jdbc, transactions, models, models).admit(
                new ChainProposalAdmissionService.AdmissionRequest(
                        ready.proposal().proposalId(), task.taskId(),
                        identity("finalization-failure-proposal-accepted",
                                ready.proposal().proposalId()),
                        true, null, ready.proposal().payload().sha256(), now));
        return ready.proposal().proposalId();
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
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> failure(
                        "CHAIN_FINALIZATION_FAILURE_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_FINALIZATION_FAILURE_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_FINALIZATION_FAILURE_CONTEXT_MISSING"));
        FailureSource source = exactSourceForContext(task.taskId(), context);
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
                            source.readiness().readinessScopeKey()),
                    (ignoredTask, ignoredProposal, type, ref) ->
                            admission.replaceByOfficialResult(
                                    new ChainProposalAdmissionService
                                            .OfficialReplacement(
                                            proposal.proposalId(), task.taskId(),
                                            identity("finalization-failure-review-bound",
                                                    ref),
                                            ChainPersistenceRecords
                                                    .ProposalOfficialAuthorityType
                                                    .REVIEW_DECISION,
                                            ref, null,
                                            proposal.payload().sha256(), now)));
            committed = runtime.commit(new ChainReviewRuntime.CommitRequest(
                    task.taskId(), proposal.proposalId(),
                    identity("finalization-failure-review-event",
                            proposal.proposalId()),
                    source.type(), source.ref(), now));
        }
        if (payload instanceof ReflectorPayload.NeedPermission permission) {
            openPermissionPending(task, proposal, source,
                    committed.decision(), permission, now);
        } else if (payload instanceof ReflectorPayload.TaskFailed failed) {
            commitFailedOutcome(task, instruction, source, committed.decision(),
                    failed, now);
        }
        return committed;
    }

    ChainPersistenceRecords.PendingItemRecord openPermissionPending(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            FailureSource source,
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            ReflectorPayload.NeedPermission payload,
            Instant now) {
        String exactTaskId = task.taskId();
        String exactProposalId = proposal.proposalId();
        List<ChainPersistenceRecords.ProposalStateEventRecord> current =
                acceptedStates(exactTaskId, exactProposalId);
        require(current.size() == 2
                        && current.get(1).officialAuthorityRef().equals(
                        decision.reviewDecisionId()),
                "CHAIN_FINALIZATION_FAILURE_PENDING_REVIEW_BINDING_INVALID");
        var pending = new ChainPendingItemRuntime.PendingProposal(
                exactTaskId, exactProposalId, proposal.proposalKind(),
                current.get(1), ChainPendingItemType.PERMISSION, List.of(),
                payload.scope(), payload.purpose(), "permission decision",
                payload.validationRole(), ChainRole.PLANNER,
                payload.newIntakePosition(),
                source.readiness().readinessScopeKey());
        ChainPendingItemRuntime runtime = new ChainPendingItemRuntime(
                workflow, foundations, workflow, ignored -> pending,
                ignored -> {
                    throw failure(
                            "CHAIN_FINALIZATION_FAILURE_PENDING_VALIDATION_UNUSED");
                },
                new ChainPendingItemRuntime.NormalSuccessorPort() {
                    @Override
                    public ChainPendingItemRuntime.OfficialSuccessor commit(
                            ChainPendingItemRuntime.NormalSuccessorRequest request) {
                        throw failure(
                                "CHAIN_FINALIZATION_FAILURE_PENDING_SUCCESSOR_UNUSED");
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
                    throw failure(
                            "CHAIN_FINALIZATION_FAILURE_SECOND_BIND_FORBIDDEN");
                });
        return runtime.openFromReviewDecision(
                new ChainPendingItemRuntime.OpenRequest(
                        exactTaskId, exactProposalId, identity(
                        "finalization-failure-pending-event",
                        decision.reviewDecisionId()), now),
                decision.reviewDecisionId());
    }

    void commitFailedOutcome(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            FailureSource source,
            ChainPersistenceRecords.ReviewDecisionRecord decision,
            ReflectorPayload.TaskFailed payload,
            Instant now) {
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                source.readiness();
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        task.taskId(), "task-outcome." + sha256(
                        task.taskId() + "\0" + source.type() + "\0"
                                + source.ref()),
                        instruction.commandId(), instruction.instructionId(),
                        readiness.taskFrameId(), readiness.finalPlanId(),
                        readiness.finalPlanRevisionId(), readiness.coverage(),
                        readiness.acceptedSet(), readiness.artifactId(),
                        readiness.candidateKey(), readiness.validationId(),
                        null, null, null, null,
                        canonicalArray(payload.unfinishedOrSkippedItems()),
                        canonicalArray(payload.review().knownLimitations()),
                        canonicalArray(payload.finalization().residualRisks()), now);
        ChainTaskOutcomeRuntime.Failed command =
                new ChainTaskOutcomeRuntime.Failed(
                        draft, source.ref(), source.category(), source.code());
        outcomes.commit(command, new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
            @Override
            public void verifyCompleted(ChainTaskOutcomeRuntime.Completed value) {
                throw failure("CHAIN_FINALIZATION_FAILURE_OUTCOME_KIND_INVALID");
            }

            @Override
            public void verifyFailed(ChainTaskOutcomeRuntime.Failed value) {
                FailureSource exact = exactSource(
                        task.taskId(), source.type(), source.ref());
                require(exact.equals(source)
                                && value.formalFailureSourceId().equals(source.ref())
                                && value.failureCategory().equals(source.category())
                                && value.failureCode().equals(source.code())
                                && decision.taskId().equals(task.taskId())
                                && decision.reviewObjectType().equals(source.type())
                                && decision.reviewObjectId().equals(source.ref())
                                && decision.decisionKind()
                                == ChainProposalKind.REFLECTOR_TASK_FAILED,
                        "CHAIN_FINALIZATION_FAILURE_OUTCOME_SOURCE_INVALID");
            }

            @Override
            public void verifyCancelled(ChainTaskOutcomeRuntime.Cancelled value) {
                throw failure("CHAIN_FINALIZATION_FAILURE_OUTCOME_KIND_INVALID");
            }

            @Override
            public void verifySuperseded(ChainTaskOutcomeRuntime.Superseded value) {
                throw failure("CHAIN_FINALIZATION_FAILURE_OUTCOME_KIND_INVALID");
            }
        });
    }

    private FailureSource exactSourceForContext(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        List<FailureSource> matches = currentSources(taskId).stream()
                .filter(value -> context.contextRevisionId().equals(
                        contextId(taskId, value.type(), value.ref())))
                .toList();
        return exactlyOne(matches,
                "CHAIN_FINALIZATION_FAILURE_CONTEXT_SOURCE_INVALID");
    }

    private FailureSource exactSource(
            String taskId, String type, String ref) {
        required(taskId, "taskId");
        required(type, "sourceAuthorityType");
        required(ref, "sourceAuthorityRef");
        require("FINALIZATION_CHECK".equals(type)
                        || "PUBLISH_FAILURE".equals(type),
                "CHAIN_FINALIZATION_FAILURE_SOURCE_TYPE_INVALID");
        return exactlyOne(currentSources(taskId).stream()
                        .filter(value -> value.type().equals(type)
                                && value.ref().equals(ref)).toList(),
                "CHAIN_FINALIZATION_FAILURE_SOURCE_INVALID");
    }

    private List<FailureSource> currentSources(String taskId) {
        List<FailureSource> result = new ArrayList<>();
        for (ChainPersistenceRecords.FinalizationReadinessRecord readiness
                : finalization.findReadiness(taskId)) {
            var checks = finalization.findFinalizationChecks(
                            readiness.readinessId()).stream()
                    .sorted(Comparator.comparingInt(
                            ChainPersistenceRecords.FinalizationCheckRecord
                                    ::attemptNo))
                    .toList();
            if (checks.isEmpty()) continue;
            var check = checks.get(checks.size() - 1);
            ChainPersistenceRecords.TransitionRecord transition = workflow
                    .findTransition(check.transitionId()).orElseThrow(() ->
                            failure("CHAIN_FINALIZATION_FAILURE_TRANSITION_MISSING"));
            var state = recovery.inspect(transition);
            if (state instanceof ProductChainFinalizationRecoverySource
                    .CheckFailure failed
                    && failed.reason().finalizationCheckId().equals(
                    check.finalizationCheckId())) {
                result.add(new FailureSource(
                        "FINALIZATION_CHECK",
                        failed.reason().finalizationCheckId(),
                        "FINALIZATION", failed.reason().errorCode().name(),
                        readiness, check, transition));
            } else if (state instanceof ProductChainFinalizationRecoverySource
                    .PublishFailureState failed) {
                result.add(new FailureSource(
                        "PUBLISH_FAILURE",
                        failed.reason().formalFailureRef(),
                        "PUBLISH", failed.reason().errorCode().name(),
                        readiness, check, transition));
            }
        }
        return List.copyOf(result);
    }

    private ChainPersistenceRecords.PlanBindingRecord binding(
            FailureSource source) {
        var readiness = source.readiness();
        return exactlyOne(workflow.findPlanBindings(readiness.taskId()).stream()
                        .filter(value -> value.taskFrameId().equals(
                                readiness.taskFrameId()))
                        .filter(value -> value.planId().equals(
                                readiness.finalPlanId()))
                        .filter(value -> value.planRevisionId().equals(
                                readiness.finalPlanRevisionId()))
                        .filter(value -> value.planRevisionNumber()
                                == readiness.finalPlanRevisionNumber())
                        .filter(value -> value.instructionId().equals(
                                readiness.instructionId())).toList(),
                "CHAIN_FINALIZATION_FAILURE_PLAN_BINDING_INVALID");
    }

    private ChainPersistenceRecords.ContextRevisionRecord predecessorContext(
            FailureSource source) {
        var readiness = source.readiness();
        ChainPersistenceRecords.ReviewDecisionRecord review = exactlyOne(
                workflow.findReviewDecisions(readiness.taskId()).stream()
                        .filter(value -> value.reviewDecisionId().equals(
                                readiness.reviewDecisionId())).toList(),
                "CHAIN_FINALIZATION_FAILURE_READINESS_REVIEW_INVALID");
        var proposal = models.findProposal(review.proposalId())
                .orElseThrow(() -> failure(
                        "CHAIN_FINALIZATION_FAILURE_READINESS_PROPOSAL_MISSING"));
        var states = models.findProposalStateEvents(proposal.proposalId())
                .stream().sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        require(!states.isEmpty(),
                "CHAIN_FINALIZATION_FAILURE_READINESS_PROPOSAL_STATE_MISSING");
        var finalState = states.get(states.size() - 1);
        require(proposal.taskId().equals(readiness.taskId())
                        && proposal.role() == ChainRole.REFLECTOR
                        && proposal.proposalId().equals(review.proposalId())
                        && proposal.proposalKind() == review.decisionKind()
                        && finalState.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                        && "REVIEW_DECISION".equals(
                        finalState.officialAuthorityType())
                        && review.reviewDecisionId().equals(
                        finalState.officialAuthorityRef()),
                "CHAIN_FINALIZATION_FAILURE_READINESS_PROPOSAL_INVALID");
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_FINALIZATION_FAILURE_READINESS_INVOCATION_MISSING"));
        var context = contexts.findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_FINALIZATION_FAILURE_READINESS_CONTEXT_MISSING"));
        require(context.taskId().equals(readiness.taskId())
                        && Objects.equals(context.taskFrameId(),
                                readiness.taskFrameId())
                        && Objects.equals(context.planId(),
                                readiness.finalPlanId())
                        && Objects.equals(context.planRevisionId(),
                                readiness.finalPlanRevisionId())
                        && Objects.equals(context.planRevisionNumber(),
                                readiness.finalPlanRevisionNumber())
                        && Objects.equals(context.stepId(),
                                readiness.finalStepId())
                        && context.activationEventId() != null,
                "CHAIN_FINALIZATION_FAILURE_READINESS_CONTEXT_INVALID");
        return context;
    }

    private void validateInvocation(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            FailureSource source,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        var predecessor = predecessorContext(source);
        var binding = binding(source);
        String expectedContext = contextId(
                task.taskId(), source.type(), source.ref());
        require(proposal.taskId().equals(task.taskId())
                        && proposal.role() == ChainRole.REFLECTOR
                        && proposal.proposalKind().role() == ChainRole.REFLECTOR
                        && proposal.invocationId().equals(invocation.invocationId())
                        && invocation.taskId().equals(task.taskId())
                        && invocation.role() == ChainRole.REFLECTOR
                        && invocation.workState()
                        == ChainWorkState.AWAITING_REVIEW
                        && invocation.callReason().equals(CALL_REASON)
                        && invocation.contextRevisionId().equals(expectedContext)
                        && invocation.invocationId().equals(
                                invocationId(expectedContext))
                        && context.contextRevisionId().equals(expectedContext)
                        && context.taskId().equals(task.taskId())
                        && context.status() == ChainContextRevisionStatus.COMPLETE
                        && context.role() == ChainRole.REFLECTOR
                        && context.workState() == ChainWorkState.AWAITING_REVIEW
                        && context.callReason().equals(CALL_REASON)
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
                "CHAIN_FINALIZATION_FAILURE_INVOCATION_IDENTITY_INVALID");
    }

    static void validatePayload(
            ReflectorPayload payload, FailureSource source) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(source, "source");
        require(payload.kind() == ChainProposalKind.REFLECTOR_REPLAN_REQUIRED
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_NEED_PERMISSION
                        || payload.kind()
                        == ChainProposalKind.REFLECTOR_TASK_FAILED,
                "CHAIN_FINALIZATION_FAILURE_REFLECTOR_KIND_INVALID");
        require(payload.review().reviewedObjectRefs().contains(source.ref())
                        && payload.review().directFactRefs().contains(source.ref()),
                "CHAIN_FINALIZATION_FAILURE_FACT_REF_MISSING");
        if (payload instanceof ReflectorPayload.TaskFailed failed) {
            require(failed.failureFactRefs().contains(source.ref())
                            && failed.failureCategory().equals(
                                    source.category()),
                    "CHAIN_FINALIZATION_FAILURE_TASK_FAILED_BINDING_INVALID");
        }
    }

    private ChainReviewRuntime.CommitResult exactBoundReview(
            String taskId,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ReflectorPayload payload,
            FailureSource source,
            ChainPersistenceRecords.ProposalStateEventRecord bound) {
        require(bound.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                        && "REVIEW_DECISION".equals(
                                bound.officialAuthorityType()),
                "CHAIN_FINALIZATION_FAILURE_PROPOSAL_BOUND_ELSEWHERE");
        var decision = exactlyOne(workflow.findReviewDecisions(taskId).stream()
                        .filter(value -> value.reviewDecisionId().equals(
                                bound.officialAuthorityRef()))
                        .filter(value -> value.proposalId().equals(
                                proposal.proposalId()))
                        .filter(value -> value.reviewObjectType().equals(
                                source.type()))
                        .filter(value -> value.reviewObjectId().equals(
                                source.ref()))
                        .filter(value -> value.decisionKind()
                                == proposal.proposalKind())
                        .filter(value -> value.versionFenceSha256().equals(
                                source.readiness().readinessScopeKey()))
                        .toList(),
                "CHAIN_FINALIZATION_FAILURE_BOUND_REVIEW_INVALID");
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
                "CHAIN_FINALIZATION_FAILURE_PROPOSAL_STATE_INVALID");
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            require(state.taskId().equals(taskId)
                            && state.proposalId().equals(proposalId)
                            && state.stateSequence() == index + 1L,
                    "CHAIN_FINALIZATION_FAILURE_PROPOSAL_STATE_INVALID");
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_FINALIZATION_FAILURE_PROPOSAL_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        require(states.get(0).stateKind() == ChainProposalState.ACCEPTED,
                "CHAIN_FINALIZATION_FAILURE_PROPOSAL_NOT_ACCEPTED");
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
            case REFLECTOR_REPLAN_REQUIRED ->
                    ChainReviewRuntime.SuccessorRequirement.PLAN_REVISION;
            case REFLECTOR_NEED_PERMISSION ->
                    ChainReviewRuntime.SuccessorRequirement
                            .PERMISSION_PENDING_ITEM;
            case REFLECTOR_TASK_FAILED ->
                    ChainReviewRuntime.SuccessorRequirement
                            .FAILED_TASK_OUTCOME;
            default -> throw failure(
                    "CHAIN_FINALIZATION_FAILURE_REFLECTOR_KIND_INVALID");
        };
    }

    private static String contextId(
            String taskId, String type, String ref) {
        return identity("context", taskId + "\0REFLECTOR\0"
                + CALL_REASON + "\0" + type + "\0" + ref);
    }

    private static String invocationId(String contextId) {
        return identity("invocation", contextId);
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(
            List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"');
            for (int cursor = 0; cursor < values.get(index).length(); cursor++) {
                char character = values.get(index).charAt(cursor);
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

    record FailureSource(
            String type,
            String ref,
            String category,
            String code,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check,
            ChainPersistenceRecords.TransitionRecord transition) {
        FailureSource {
            required(type, "type");
            required(ref, "ref");
            required(category, "category");
            required(code, "code");
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(transition, "transition");
        }
    }
}
