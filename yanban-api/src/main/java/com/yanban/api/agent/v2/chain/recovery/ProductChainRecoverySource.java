package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextBuildFailureRepository;
import io.paperagent.v2.chain.ChainCandidateMaterializationFailureRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemType;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.step.ChainActionProgressIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds one stable, frozen recovery cut exclusively from new-chain authorities. */
public final class ProductChainRecoverySource
        implements ChainRecoveryRuntime.RecoverySource {
    private static final String VERSION = "agent-v2-chain-v70-v80";
    private static final int MAX_STABLE_READ_ATTEMPTS = 4;

    private final ChainFoundationRepository foundations;
    private final ChainContextRepository contexts;
    private final ChainContextBuildFailureRepository contextBuildFailures;
    private final ChainModelRepository models;
    private final ChainWorkflowRepository workflow;
    private final ChainCandidateMaterializationFailureRepository
            candidateFailures;
    private final ChainFinalizationRepository finalization;
    private final StableAuthoritySource retainedAuthorities;

    public ProductChainRecoverySource(
            ChainFoundationRepository foundations,
            ChainContextRepository contexts,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            StableAuthoritySource retainedAuthorities) {
        this(foundations, contexts, ignored -> Optional.empty(), models,
                workflow, (ignoredTask, ignoredAction) -> Optional.empty(),
                finalization, retainedAuthorities);
    }

    public ProductChainRecoverySource(
            ChainFoundationRepository foundations,
            ChainContextRepository contexts,
            ChainContextBuildFailureRepository contextBuildFailures,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization,
            StableAuthoritySource retainedAuthorities) {
        this(foundations, contexts, contextBuildFailures, models, workflow,
                (ignoredTask, ignoredAction) -> Optional.empty(), finalization,
                retainedAuthorities);
    }

    public ProductChainRecoverySource(
            ChainFoundationRepository foundations,
            ChainContextRepository contexts,
            ChainContextBuildFailureRepository contextBuildFailures,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainCandidateMaterializationFailureRepository candidateFailures,
            ChainFinalizationRepository finalization,
            StableAuthoritySource retainedAuthorities) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.contextBuildFailures = Objects.requireNonNull(
                contextBuildFailures, "contextBuildFailures");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.candidateFailures = Objects.requireNonNull(
                candidateFailures, "candidateFailures");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.retainedAuthorities = Objects.requireNonNull(
                retainedAuthorities, "retainedAuthorities");
    }

    @Override
    public ChainRecoveryRuntime.RecoverySnapshot load(String taskId) {
        required(taskId, "taskId");
        ChainPersistenceRecords.TaskRecord task = foundations.findTask(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "chain task does not exist"));
        for (int attempt = 1; attempt <= MAX_STABLE_READ_ATTEMPTS; attempt++) {
            long authorityCut = foundations.highestAuthorityEventSequence(taskId);
            List<ChainPersistenceRecords.AuthorityEventRecord> authorities =
                    foundations.findAuthorityEvents(taskId, authorityCut);
            Map<String, Long> eventSequences = authoritySequences(
                    taskId, authorityCut, authorities);
            ReadPass pass;
            try {
                pass = readAt(task, authorityCut, eventSequences);
            } catch (AuthorityBeyondCut beyondCut) {
                long verifiedCut = foundations.highestAuthorityEventSequence(
                        taskId);
                if (verifiedCut != authorityCut) {
                    continue;
                }
                throw new IllegalStateException(
                        "ContextBuildFailure lacks its AuthorityEvent in the stable cut",
                        beyondCut);
            }
            long verifiedCut = foundations.highestAuthorityEventSequence(taskId);
            if (verifiedCut != authorityCut) {
                continue;
            }
            if (!pass.contextRevisions().equals(
                    contextRevisions(taskId))) {
                continue;
            }
            if (!modelCutStable(pass)) {
                continue;
            }
            return snapshot(pass);
        }
        throw new IllegalStateException(
                "could not obtain a stable recovery authority cut");
    }

    private ReadPass readAt(
            ChainPersistenceRecords.TaskRecord task,
            long authorityCut,
            Map<String, Long> eventSequences) {
        String taskId = task.taskId();
        var instructions = cut(
                taskId, foundations.findTaskInstructions(taskId, authorityCut),
                eventSequences);
        Map<String, ChainPersistenceRecords.InstructionRecord> instructionValues =
                new LinkedHashMap<>();
        instructions.forEach(binding -> instructionValues.put(
                binding.instructionId(), foundations
                        .findInstruction(binding.instructionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "bound instruction is missing"))));

        List<ChainPersistenceRecords.ContextRevisionRecord> contextRevisions =
                contextRevisions(taskId);
        List<ContextFailureProjection> contextFailures = contextFailures(
                taskId, contextRevisions, eventSequences);
        long invocationOrdinalCut = models.highestInvocationOrdinal(taskId);
        List<ChainPersistenceRecords.ModelInvocationRecord> invocations =
                invocationOrdinalCut == 0 ? List.of()
                        : models.findInvocations(taskId, invocationOrdinalCut)
                        .stream().filter(value -> taskId.equals(value.taskId()))
                        .filter(value -> value.invocationOrdinal()
                                <= invocationOrdinalCut)
                        .sorted(Comparator.comparingInt(
                                ChainPersistenceRecords.ModelInvocationRecord
                                        ::invocationOrdinal))
                        .toList();
        validateInvocationPrefix(taskId, invocationOrdinalCut, invocations);
        Map<String, List<ChainPersistenceRecords.ProviderAttemptRecord>>
                providerAttempts = new LinkedHashMap<>();
        List<ModelFailureProjection> modelFailures = new ArrayList<>();
        for (var invocation : invocations) {
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts =
                    models.findProviderAttempts(invocation.invocationId());
            validateAttemptPrefix(taskId, invocation, attempts);
            providerAttempts.put(invocation.invocationId(), attempts);
            ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy
                    .requireVersion(invocation.runtimePolicyVersion());
            if (attempts.size()
                    == runtimePolicy.providerAttemptsTotal()
                    && attempts.stream().allMatch(
                    ProductChainRecoverySource::isFailedAttempt)
                    && models.findProposalByInvocation(
                    invocation.invocationId()).isEmpty()) {
                ChainPersistenceRecords.ContextRevisionRecord context =
                        exactlyOneContext(contextRevisions, invocation);
                List<ChainPersistenceRecords.ModelInvocationRecord>
                        sameContext = invocations.stream().filter(value ->
                        value.contextRevisionId().equals(
                                context.contextRevisionId())).toList();
                modelFailures.add(new ModelFailureProjection(
                        context, invocation, attempts,
                        attempts.get(attempts.size() - 1),
                        sameContext.size(), sameContext.stream().anyMatch(
                        value -> value.invocationOrdinal()
                                > invocation.invocationOrdinal()),
                        invocations.stream().anyMatch(value ->
                                value.role()
                                == io.paperagent.v2.chain.ChainRole.REFLECTOR
                                && "MODEL_CALL_FAILED_REVIEW".equals(
                                value.callReason())
                                && contextRevisions.stream().anyMatch(child ->
                                child.contextRevisionId().equals(
                                        value.contextRevisionId())
                                        && context.contextRevisionId().equals(
                                        child.parentContextRevisionId())))));
            }
        }

        var routes = cut(taskId, workflow.findRouteDecisions(taskId),
                eventSequences);
        var plans = cut(taskId, workflow.findPlanBindings(taskId),
                eventSequences);
        var dispositions = cut(taskId,
                workflow.findInstructionDispositions(taskId), eventSequences);
        var candidates = cut(taskId,
                workflow.findCandidateStepResults(taskId), eventSequences);
        var modelFailureStepBlocks = cut(taskId,
                workflow.findModelFailureStepBlocks(taskId), eventSequences);
        validateModelFailureStepBlocks(
                taskId, modelFailureStepBlocks, modelFailures);
        var actionReceiptStepBlocks = cut(taskId,
                workflow.findActionReceiptStepBlocks(taskId), eventSequences);
        var reviews = cut(taskId, workflow.findReviewDecisions(taskId),
                eventSequences);
        var accepted = cut(taskId, workflow.findAcceptedResults(taskId),
                eventSequences);
        var applicability = cut(taskId,
                workflow.findApplicabilityDecisions(taskId), eventSequences);
        var pending = cut(taskId, workflow.findPendingItems(taskId),
                eventSequences);
        var permissions = cut(taskId,
                workflow.findPermissionDecisions(taskId), eventSequences);
        var actions = cut(taskId, workflow.findActionBindings(taskId),
                eventSequences);
        var workspaceCandidates = cut(taskId,
                workflow.findWorkspaceCandidates(taskId), eventSequences);

        Map<String, List<ChainPersistenceRecords.PendingItemEventRecord>>
                pendingEvents = new LinkedHashMap<>();
        pending.forEach(value -> {
            List<ChainPersistenceRecords.PendingItemEventRecord> events = cut(
                    taskId, workflow.findPendingItemEvents(value.gapId()),
                    eventSequences);
            validatePendingPrefix(value, events);
            pendingEvents.put(value.gapId(), events);
        });

        List<ChainPersistenceRecords.TransitionRecord> transitions =
                transitionsAt(taskId, eventSequences);
        Map<String, List<ChainPersistenceRecords.TransitionStageRecord>>
                transitionStages = new LinkedHashMap<>();
        transitions.forEach(value -> transitionStages.put(
                value.transitionId(), cut(taskId,
                        workflow.findTransitionStages(value.transitionId()),
                        eventSequences)));
        List<ChainPersistenceRecords.TransitionRecord> incompleteTransitions =
                transitions.stream().filter(value -> transitionStages
                        .get(value.transitionId()).stream().noneMatch(stage ->
                                stage.stageCode() == ChainTransitionStage.COMPLETE))
                        .toList();

        var readiness = cut(taskId, finalization.findReadiness(taskId),
                eventSequences);
        Map<String, List<ChainPersistenceRecords.FinalizationCheckRecord>> checks =
                new LinkedHashMap<>();
        readiness.forEach(value -> checks.put(value.readinessId(), cut(
                taskId, finalization.findFinalizationChecks(value.readinessId()),
                eventSequences)));
        Optional<ChainPersistenceRecords.TaskOutcomeRecord> outcome = finalization
                .findTaskOutcome(taskId).filter(value ->
                        eventSequences.containsKey(value.eventId()));
        var deliveries = cut(taskId, finalization.findDeliveries(taskId),
                eventSequences);
        Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                deliveryEvents = new LinkedHashMap<>();
        deliveries.forEach(value -> deliveryEvents.put(value.deliveryId(), cut(
                taskId, finalization.findDeliveryEvents(value.deliveryId()),
                eventSequences)));

        List<ProposalProjection> proposals = new ArrayList<>();
        for (var invocation : invocations) {
            models.findProposalByInvocation(invocation.invocationId())
                    .ifPresent(proposal -> {
                        if (!taskId.equals(proposal.taskId())) {
                            throw new IllegalStateException(
                                    "proposal belongs to another task");
                        }
                        List<ChainPersistenceRecords.ProposalStateEventRecord>
                                states = cut(taskId,
                                models.findProposalStateEvents(
                                        proposal.proposalId()), eventSequences);
                        if (!states.isEmpty()) {
                            validateProposalPrefix(
                                    taskId, proposal.proposalId(), states);
                            var latest = states.get(states.size() - 1);
                            var matchingContexts = contextRevisions.stream()
                                    .filter(value -> value.contextRevisionId()
                                            .equals(invocation
                                                    .contextRevisionId()))
                                    .toList();
                            if (matchingContexts.size() > 1) {
                                throw new IllegalStateException(
                                        "invocation context is ambiguous");
                            }
                            proposals.add(new ProposalProjection(
                                    matchingContexts.isEmpty() ? null
                                            : matchingContexts.get(0),
                                    invocation, proposal, states, latest,
                                    sequence(latest, eventSequences)));
                        }
                    });
        }

        StableAuthoritySnapshot retained = Objects.requireNonNull(
                retainedAuthorities.freeze(new StableAuthorityRequest(
                        task, authorityCut, plans, actions,
                        workspaceCandidates, readiness, checks)),
                "retained authority snapshot");
        if (!taskId.equals(retained.taskId())
                || retained.chainAuthorityCut() != authorityCut) {
            throw new IllegalStateException(
                    "retained authorities are not bound to the chain cut");
        }
        validateActionReceiptStepBlocks(taskId, actionReceiptStepBlocks,
                actions, plans, retained, authorityCut, eventSequences);
        retained.stepState().ifPresent(step -> {
            if (plans.stream().noneMatch(plan ->
                    plan.planRevisionId().equals(step.planRevisionId()))) {
                throw new IllegalStateException(
                        "retained Step does not belong to a frozen Plan binding");
            }
        });
        String boundary = "authority-event-sequence=" + authorityCut
                + ";invocation-ordinal=" + invocationOrdinalCut
                + ";provider-attempt-set=" + digest(providerAttempts.entrySet()
                .stream().map(entry -> entry.getKey() + ":"
                        + digest(entry.getValue().stream().map(value ->
                        value.attemptNo() + ":"
                                + value.schemaValidationStatus() + ":"
                                + value.proposalValidationStatus() + ":"
                                + Objects.toString(value.errorCode(), "NONE"))
                        .toList())).toList())
                + ";context-revision-set=" + digest(contextRevisions.stream()
                .map(value -> value.contextRevisionId() + ":"
                        + value.status().name() + ":"
                        + Objects.toString(value.requestDigest(), "NONE") + ":"
                        + Objects.toString(value.inputDigest(), "NONE") + ":"
                        + Objects.toString(value.blockedErrorCode(), "NONE"))
                .toList())
                + ";context-failure-set=" + digest(contextFailures.stream()
                .map(value -> value.sourceAuthorityType() + ":"
                        + value.sourceAuthorityRef() + ":"
                        + value.contextRevision().contextRevisionId() + ":"
                        + (value.buildFailure() == null ? "NONE"
                        : value.buildFailure().eventId()) + ":"
                        + value.authoritySequence() + ":"
                        + value.successorContextPresent())
                .toList())
                + ";action-receipt-step-block-set="
                + digest(actionReceiptStepBlocks.stream().map(value ->
                        value.stepBlockId() + ":"
                                + value.blockIdentityDigestSha256()).toList())
                + ";retained=" + retained.readBoundary();
        return new ReadPass(task, authorityCut, boundary, eventSequences,
                instructions, Map.copyOf(instructionValues), contextRevisions,
                contextFailures, invocationOrdinalCut, invocations,
                Map.copyOf(providerAttempts), List.copyOf(modelFailures),
                routes, plans, dispositions, candidates,
                modelFailureStepBlocks, actionReceiptStepBlocks,
                reviews, accepted,
                applicability, pending, Map.copyOf(pendingEvents), permissions,
                actions, workspaceCandidates, transitions,
                Map.copyOf(transitionStages), incompleteTransitions, readiness,
                Map.copyOf(checks), outcome, deliveries,
                Map.copyOf(deliveryEvents), List.copyOf(proposals), retained);
    }

    private ChainRecoveryRuntime.RecoverySnapshot snapshot(ReadPass pass) {
        EnumMap<ChainRecoveryRuntime.RecoveryFactKind, List<String>> refs =
                emptyCuts();
        pass.instructions().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.INSTRUCTION_AND_PENDING,
                "TASK_INSTRUCTION", value.instructionId()));
        pass.pending().forEach(value -> {
            add(refs, ChainRecoveryRuntime.RecoveryFactKind
                            .INSTRUCTION_AND_PENDING,
                    "PENDING_ITEM", value.gapId());
            pass.pendingEvents().get(value.gapId()).forEach(event -> add(refs,
                    ChainRecoveryRuntime.RecoveryFactKind
                            .INSTRUCTION_AND_PENDING,
                    "PENDING_ITEM_EVENT", event.eventId() + ":"
                            + event.eventKind().name()));
        });
        pass.permissions().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.INSTRUCTION_AND_PENDING,
                "PERMISSION_DECISION", value.permissionDecisionId()));

        pass.contextRevisions().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.TASKFRAME_PLAN_AND_STEP,
                "CONTEXT_REVISION", value.contextRevisionId() + ":"
                        + value.status().name()));
        pass.contextFailures().forEach(value -> {
            add(refs,
                    ChainRecoveryRuntime.RecoveryFactKind
                            .TASKFRAME_PLAN_AND_STEP,
                    value.sourceAuthorityType(),
                    value.sourceAuthorityRef());
            if (value.buildFailure() != null) {
                add(refs,
                        ChainRecoveryRuntime.RecoveryFactKind
                                .TASKFRAME_PLAN_AND_STEP,
                        "CONTEXT_BUILD_FAILURE_EVENT",
                        value.buildFailure().eventId() + ":"
                                + value.authoritySequence());
            }
        });
        pass.routes().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.TASKFRAME_PLAN_AND_STEP,
                "ROUTE_DECISION", value.routeDecisionId()));
        pass.plans().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.TASKFRAME_PLAN_AND_STEP,
                "PLAN_BINDING", value.planBindingId() + ":"
                        + value.taskFrameId() + ":" + value.planId() + ":"
                        + value.planRevisionId()));
        addRetained(refs, pass.retained(), StableFactKind.TASKFRAME_PLAN_STEP,
                ChainRecoveryRuntime.RecoveryFactKind.TASKFRAME_PLAN_AND_STEP);

        pass.actions().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.ACTION_RECEIPT_AND_ERROR,
                "ACTION_BINDING", value.actionId()));
        addRetained(refs, pass.retained(),
                StableFactKind.EFFECT_INTENT_RECEIPT_ERROR,
                ChainRecoveryRuntime.RecoveryFactKind.ACTION_RECEIPT_AND_ERROR);

        pass.candidates().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .CANDIDATE_RESULT_AND_REVIEW,
                "CANDIDATE_STEP_RESULT", value.candidateResultId()));
        pass.reviews().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .CANDIDATE_RESULT_AND_REVIEW,
                "REVIEW_DECISION", value.reviewDecisionId()));
        pass.accepted().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .CANDIDATE_RESULT_AND_REVIEW,
                "ACCEPTED_RESULT", value.acceptedResultId()));
        pass.applicability().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .CANDIDATE_RESULT_AND_REVIEW,
                "RESULT_APPLICABILITY", value.applicabilityId()));

        pass.workspaceCandidates().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.WORKSPACE_AND_CANDIDATE,
                "WORKSPACE_CANDIDATE", value.workspaceCandidateId() + ":"
                        + value.workspaceId() + ":"
                        + value.candidateFingerprint()));
        addRetained(refs, pass.retained(),
                StableFactKind.WORKSPACE_CANDIDATE,
                ChainRecoveryRuntime.RecoveryFactKind.WORKSPACE_AND_CANDIDATE);

        pass.reviews().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .REVIEW_READINESS_GAP_AND_TRANSITION,
                "REVIEW_DECISION", value.reviewDecisionId()));
        pass.readiness().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .REVIEW_READINESS_GAP_AND_TRANSITION,
                "FINALIZATION_READINESS", value.readinessId()));
        pass.pending().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .REVIEW_READINESS_GAP_AND_TRANSITION,
                "PENDING_ITEM", value.gapId()));
        pass.incompleteTransitions().forEach(value -> {
            add(refs, ChainRecoveryRuntime.RecoveryFactKind
                            .REVIEW_READINESS_GAP_AND_TRANSITION,
                    "TRANSITION", value.transitionId());
            pass.transitionStages().get(value.transitionId()).forEach(stage ->
                    add(refs, ChainRecoveryRuntime.RecoveryFactKind
                                    .REVIEW_READINESS_GAP_AND_TRANSITION,
                            "TRANSITION_STAGE", stage.eventId() + ":"
                                    + stage.stageCode().name()));
        });

        pass.proposals().forEach(value -> {
            add(refs, ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                    "MODEL_INVOCATION", value.invocation().invocationId() + ":"
                            + value.invocation().invocationOrdinal());
            add(refs, ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                    "MODEL_PROPOSAL", value.proposal().proposalId() + ":"
                            + value.proposal().proposalKind().name());
            value.states().forEach(state -> add(refs,
                    ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                    "PROPOSAL_STATE", state.eventId() + ":"
                            + state.stateKind().name()));
        });
        pass.modelFailures().forEach(value -> {
            add(refs, ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                    "MODEL_CALL_FAILED", value.invocation().invocationId());
            value.attempts().forEach(attempt -> add(refs,
                    ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                    "PROVIDER_ATTEMPT", attempt.invocationId() + "#"
                            + attempt.attemptNo()));
        });
        pass.modelFailureStepBlocks().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                "MODEL_FAILURE_STEP_BLOCK", value.stepBlockId()));
        pass.actionReceiptStepBlocks().forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.PROPOSAL_STATE,
                "ACTION_RECEIPT_STEP_BLOCK", value.stepBlockId()));

        pass.readiness().forEach(value -> {
            add(refs, ChainRecoveryRuntime.RecoveryFactKind
                            .VALIDATION_FINALIZATION_AND_PUBLISH,
                    "FINALIZATION_READINESS", value.readinessId());
            pass.checks().get(value.readinessId()).forEach(check -> add(refs,
                    ChainRecoveryRuntime.RecoveryFactKind
                            .VALIDATION_FINALIZATION_AND_PUBLISH,
                    "FINALIZATION_CHECK", check.finalizationCheckId() + ":"
                            + check.resultStatus().name()));
        });
        pass.deliveries().forEach(value -> {
            add(refs, ChainRecoveryRuntime.RecoveryFactKind
                            .VALIDATION_FINALIZATION_AND_PUBLISH,
                    "DELIVERY", value.deliveryId());
            pass.deliveryEvents().get(value.deliveryId()).forEach(event -> add(
                    refs, ChainRecoveryRuntime.RecoveryFactKind
                            .VALIDATION_FINALIZATION_AND_PUBLISH,
                    "DELIVERY_EVENT", event.eventId() + ":"
                            + event.eventKind().name()));
        });
        addRetained(refs, pass.retained(),
                StableFactKind.VALIDATION_AND_PUBLISH,
                ChainRecoveryRuntime.RecoveryFactKind
                        .VALIDATION_FINALIZATION_AND_PUBLISH);

        pass.instructions().forEach(binding -> {
            var instruction = pass.instructionValues().get(
                    binding.instructionId());
            add(refs, ChainRecoveryRuntime.RecoveryFactKind
                            .PAUSE_CANCEL_AND_SUPERSEDE,
                    "INSTRUCTION", instruction.instructionId() + ":"
                            + instruction.relationKind().name());
        });
        pass.outcome().ifPresent(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind
                        .PAUSE_CANCEL_AND_SUPERSEDE,
                "TASK_OUTCOME", value.outcomeId() + ":"
                        + value.outcomeType().name()));

        pass.actions().stream().filter(value ->
                value.resultAuthorityRef() == null).forEach(value -> add(refs,
                ChainRecoveryRuntime.RecoveryFactKind.IN_FLIGHT_ACTION,
                "ACTION", value.actionId() + ":" + value.idempotencyKey()
                        + ":" + Objects.toString(value.effectIntentId(), "NONE")
                        + ":" + Objects.toString(value.dispatchRef(), "NONE")));
        addRetained(refs, pass.retained(), StableFactKind.IN_FLIGHT_ACTION,
                ChainRecoveryRuntime.RecoveryFactKind.IN_FLIGHT_ACTION);

        List<ChainRecoveryRuntime.FactCut> cuts = new ArrayList<>();
        for (ChainRecoveryRuntime.RecoveryFactKind kind
                : ChainRecoveryRuntime.RecoveryFactKind.values()) {
            List<String> values = refs.get(kind).stream().distinct().sorted()
                    .toList();
            cuts.add(new ChainRecoveryRuntime.FactCut(
                    kind, VERSION, pass.boundary(), values));
        }
        List<ChainRecoveryRuntime.TransitionRef> transitionRefs = pass
                .incompleteTransitions().stream().map(value -> transitionRef(
                        pass.task().taskId(), value,
                        pass.transitionStages().get(value.transitionId()),
                        pass.eventSequences())).toList();
        return new ChainRecoveryRuntime.RecoverySnapshot(
                pass.task().taskId(), cuts, transitionRefs,
                roleProjection(pass));
    }

    private boolean modelCutStable(ReadPass pass) {
        if (models.highestInvocationOrdinal(pass.task().taskId())
                != pass.invocationOrdinalCut()) {
            return false;
        }
        for (var entry : pass.providerAttempts().entrySet()) {
            if (models.highestProviderAttemptNo(entry.getKey())
                    != entry.getValue().size()) {
                return false;
            }
        }
        return true;
    }

    private static void validateInvocationPrefix(
            String taskId,
            long invocationOrdinalCut,
            List<ChainPersistenceRecords.ModelInvocationRecord> invocations) {
        if (invocationOrdinalCut > Integer.MAX_VALUE
                || invocations.size() != invocationOrdinalCut) {
            throw new IllegalStateException(
                    "model invocation prefix is incomplete");
        }
        String runtimePolicyVersion = invocations.isEmpty() ? null
                : invocations.get(0).runtimePolicyVersion();
        if (runtimePolicyVersion != null) {
            ChainRuntimePolicy.requireVersion(runtimePolicyVersion);
        }
        for (int index = 0; index < invocations.size(); index++) {
            var invocation = invocations.get(index);
            if (!invocation.taskId().equals(taskId)
                    || invocation.invocationOrdinal() != index + 1
                    || !Objects.equals(runtimePolicyVersion,
                    invocation.runtimePolicyVersion())) {
                throw new IllegalStateException(
                        "model invocation prefix identity is invalid");
            }
        }
    }

    private static void validateAttemptPrefix(
            String taskId,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts) {
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                invocation.runtimePolicyVersion());
        if (attempts.size() > runtimePolicy.providerAttemptsTotal()) {
            throw new IllegalStateException(
                    "provider attempt prefix exceeds runtime policy");
        }
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (!attempt.taskId().equals(taskId)
                    || !attempt.invocationId().equals(
                    invocation.invocationId())
                    || attempt.attemptNo() != index + 1) {
                throw new IllegalStateException(
                        "provider attempt prefix identity is invalid");
            }
        }
    }

    private static boolean isFailedAttempt(
            ChainPersistenceRecords.ProviderAttemptRecord attempt) {
        boolean failed = attempt.schemaValidationStatus()
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
        return failed && attempt.errorCode() != null
                && !attempt.errorCode().isBlank();
    }

    private static void validateModelFailureStepBlocks(
            String taskId,
            List<ChainPersistenceRecords.ModelFailureStepBlockRecord> blocks,
            List<ModelFailureProjection> failures) {
        for (var block : blocks) {
            List<ModelFailureProjection> matches = failures.stream()
                    .filter(value -> value.invocation().invocationId().equals(
                            block.invocationId())).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "model failure Step block source is invalid");
            }
            var failure = matches.get(0);
            var context = failure.context();
            String attemptRef = block.invocationId() + "#"
                    + failure.lastAttempt().attemptNo();
            String fence = hash(block.invocationId() + "\0"
                    + context.contextRevisionId() + "\0"
                    + context.instructionId() + "\0" + context.taskFrameId()
                    + "\0" + context.planId() + "\0"
                    + context.planRevisionId() + "\0"
                    + context.planRevisionNumber() + "\0" + context.stepId()
                    + "\0" + context.activationEventId() + "\0"
                    + attemptRef);
            if (!block.taskId().equals(taskId)
                    || failure.invocation().role()
                    != io.paperagent.v2.chain.ChainRole.EXECUTOR
                    || !block.contextRevisionId().equals(
                    context.contextRevisionId())
                    || !block.instructionId().equals(context.instructionId())
                    || !block.taskFrameId().equals(context.taskFrameId())
                    || !block.planId().equals(context.planId())
                    || !block.planRevisionId().equals(
                    context.planRevisionId())
                    || !Objects.equals(block.planRevisionNumber(),
                    context.planRevisionNumber())
                    || !block.stepId().equals(context.stepId())
                    || !block.activationEventId().equals(
                    context.activationEventId())
                    || !block.lastProviderAttemptRef().equals(attemptRef)
                    || !block.versionFenceSha256().equals(fence)) {
                throw new IllegalStateException(
                        "model failure Step block identity changed");
            }
        }
    }

    private void validateActionReceiptStepBlocks(
            String taskId,
            List<ChainPersistenceRecords.ActionReceiptStepBlockRecord> blocks,
            List<ChainPersistenceRecords.ActionBindingRecord> actions,
            List<ChainPersistenceRecords.PlanBindingRecord> plans,
            StableAuthoritySnapshot retained,
            long authorityCut,
            Map<String, Long> eventSequences) {
        Map<String, StableAuthorityFact> progressByAction =
                new LinkedHashMap<>();
        for (var fact : retained.facts()) {
            if ("ACTION_RECEIPT_PROGRESS_IDENTITY".equals(
                    fact.authorityType())
                    && progressByAction.put(fact.authorityRef(), fact) != null) {
                throw new IllegalStateException(
                        "action receipt progress identity is ambiguous");
            }
        }
        for (var block : blocks) {
            var actionMatches = actions.stream().filter(value ->
                    value.actionId().equals(block.actionId())).toList();
            var planMatches = plans.stream().filter(value ->
                    value.planRevisionId().equals(block.planRevisionId()))
                    .toList();
            var proposal = models.findProposal(block.repairProposalId())
                    .orElseThrow(() -> new IllegalStateException(
                            "action failure repair proposal is missing"));
            var invocation = models.findInvocation(proposal.invocationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "action failure repair invocation is missing"));
            var context = contexts.findContextRevision(
                            invocation.contextRevisionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "action failure repair Context is missing"));
            var proposalStates = models.findProposalStateEvents(
                    proposal.proposalId()).stream()
                    .sorted(Comparator.comparingLong(
                            ChainPersistenceRecords.ProposalStateEventRecord
                                    ::stateSequence)).toList();
            boolean receiptSource = "RECEIPT".equals(
                    block.failureAuthorityType());
            var receiptFact = receiptSource ? retained.facts().stream()
                    .filter(value -> "RECEIPT".equals(value.authorityType())
                            && block.receiptId().equals(
                            value.authorityRef())).toList() : List
                    .<StableAuthorityFact>of();
            String expectedReceiptIdentity = receiptSource
                    ? retainedIdentity(block.receiptId(), block.actionId(),
                    block.receiptStatus(), block.failureCode()) : null;
            var candidateFailure = receiptSource ? null : candidateFailures
                    .findCandidateMaterializationFailure(
                            taskId, block.actionId()).orElse(null);
            boolean sourceValid = receiptSource
                    ? receiptFact.size() == 1
                    && receiptFact.get(0).identityDigest().equals(
                    expectedReceiptIdentity)
                    && receiptFact.get(0).status().equals(
                    block.receiptStatus())
                    : candidateFailure != null
                    && candidateFailure.candidateFailureId().equals(
                    block.failureAuthorityRef())
                    && candidateFailure.errorCode().equals(
                    block.failureCode())
                    && candidateFailure.versionFenceSha256().equals(
                    block.versionFenceSha256())
                    && eventSequences.containsKey(
                    candidateFailure.eventId())
                    && eventSequences.get(candidateFailure.eventId())
                    <= block.progressAuthorityEventCut();
            if (actionMatches.size() != 1 || planMatches.size() != 1
                    || !sourceValid
                    || proposalStates.size() != 2
                    || proposalStates.get(0).stateKind()
                    != ChainProposalState.ACCEPTED
                    || proposalStates.get(1).stateKind()
                    != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                    || !"ACTION_RECEIPT_STEP_BLOCK".equals(
                    proposalStates.get(1).officialAuthorityType())
                    || !block.stepBlockId().equals(
                    proposalStates.get(1).officialAuthorityRef())
                    || !block.taskId().equals(taskId)
                    || block.progressAuthorityEventCut() > authorityCut
                    || eventSequences.getOrDefault(block.eventId(), 0L)
                    <= block.progressAuthorityEventCut()) {
                throw new IllegalStateException(
                        "action receipt Step block authority is invalid");
            }
            var action = actionMatches.get(0);
            var plan = planMatches.get(0);
            if (actions.stream().anyMatch(value ->
                    !eventSequences.containsKey(value.eventId()))) {
                throw new IllegalStateException(
                        "action authority event is missing from recovery cut");
            }
            if (!action.taskId().equals(taskId)
                    || !action.instructionId().equals(block.instructionId())
                    || !action.taskFrameId().equals(block.taskFrameId())
                    || !action.planId().equals(block.planId())
                    || !action.planRevisionId().equals(
                    block.planRevisionId())
                    || !action.stepId().equals(block.stepId())
                    || !action.activationEventId().equals(
                    block.activationEventId())
                    || !action.versionFenceSha256().equals(
                    block.versionFenceSha256())
                    || plan.planRevisionNumber()
                    != block.planRevisionNumber()
                    || proposal.role()
                    != io.paperagent.v2.chain.ChainRole.EXECUTOR
                    || !(proposal.proposalKind()
                    == io.paperagent.v2.chain.ChainProposalKind
                    .EXECUTOR_TOOL_ACTION
                    || proposal.proposalKind()
                    == io.paperagent.v2.chain.ChainProposalKind
                    .EXECUTOR_WORKSPACE_CHANGE)
                    || !proposal.payload().sha256().equals(
                    block.repairProposalSignatureSha256())
                    || invocation.role()
                    != io.paperagent.v2.chain.ChainRole.EXECUTOR
                    || context.status() != ChainContextRevisionStatus.COMPLETE
                    || !context.contextRevisionId().equals(
                    block.repairContextRevisionId())
                    || !Objects.equals(context.instructionId(),
                    block.instructionId())
                    || !Objects.equals(context.planRevisionId(),
                    block.planRevisionId())
                    || !Objects.equals(context.stepId(), block.stepId())
                    || !Objects.equals(context.activationEventId(),
                    block.activationEventId())) {
                throw new IllegalStateException(
                        "action receipt Step block identity changed");
            }
            List<String> markers = actions.stream()
                    .filter(value -> value.stepId().equals(block.stepId())
                            && value.activationEventId().equals(
                            block.activationEventId())
                            && eventSequences.get(value.eventId())
                            <= block.progressAuthorityEventCut())
                    .sorted(Comparator.comparingLong(value ->
                            eventSequences.get(value.eventId())))
                    .map(value -> {
                        var failure = candidateFailures
                                .findCandidateMaterializationFailure(
                                        taskId, value.actionId()).orElse(null);
                        if (failure != null) {
                            return eventSequences.get(value.eventId()) + ":"
                                    + ChainActionProgressIdentity
                                    .candidateFailure(
                                            value.actionSignatureSha256(),
                                            failure.errorCode());
                        }
                        StableAuthorityFact fact = progressByAction.get(
                                value.actionId());
                        return fact == null ? null
                                : eventSequences.get(value.eventId()) + ":"
                                + fact.identityDigest();
                    }).filter(Objects::nonNull).toList();
            String snapshotDigest = hash(String.join("\n", markers));
            if (!snapshotDigest.equals(
                    block.progressSnapshotDigestSha256())) {
                throw new IllegalStateException(
                        "action receipt progress snapshot changed");
            }
            int observed;
            if ("NO_PROGRESS_THRESHOLD_REACHED".equals(
                    block.blockReasonCode())) {
                observed = 0;
                String latestIdentity = markers.isEmpty() ? null
                        : markers.get(markers.size() - 1)
                        .substring(markers.get(markers.size() - 1)
                                .indexOf(':') + 1);
                for (int index = markers.size() - 1; index >= 0; index--) {
                    String identity = markers.get(index).substring(
                            markers.get(index).indexOf(':') + 1);
                    if (!Objects.equals(latestIdentity, identity)) break;
                    observed++;
                }
            } else if ("REPEATED_ACTION_SIGNATURE".equals(
                    block.blockReasonCode())) {
                observed = Math.toIntExact(actions.stream()
                        .filter(value -> value.stepId().equals(block.stepId())
                                && value.activationEventId().equals(
                                block.activationEventId())
                                && eventSequences.get(value.eventId())
                                <= block.progressAuthorityEventCut()
                                && value.actionSignatureSha256().equals(
                                block.repairProposalSignatureSha256()))
                        .count());
            } else {
                observed = 1;
            }
            if (observed != block.thresholdObservedOccurrences()) {
                throw new IllegalStateException(
                        "action receipt Step block threshold changed");
            }
            ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy
                    .requireVersion(block.runtimePolicyVersion());
            if ("NO_PROGRESS_THRESHOLD_REACHED".equals(
                    block.blockReasonCode())
                    && observed < runtimePolicy.noProgressThreshold()
                    || "REPEATED_ACTION_SIGNATURE".equals(
                    block.blockReasonCode())
                    && observed < runtimePolicy
                    .sameActionSignatureOccurrencesMax()) {
                throw new IllegalStateException(
                        "action receipt Step block policy threshold is invalid");
            }
            String expectedBlockIdentity = hash(String.join("\0",
                    block.actionId(), block.failureAuthorityType(),
                    block.failureAuthorityRef(),
                    Objects.toString(block.receiptPayloadSha256(), "NONE"),
                    block.repairProposalId(),
                    block.repairProposalSignatureSha256(),
                    block.repairContextRevisionId(),
                    Long.toString(block.progressAuthorityEventCut()),
                    block.progressSnapshotDigestSha256(),
                    Integer.toString(block.thresholdObservedOccurrences()),
                    block.blockReasonCode(), block.runtimePolicyVersion()));
            if (!expectedBlockIdentity.equals(
                    block.blockIdentityDigestSha256())) {
                throw new IllegalStateException(
                        "action receipt Step block digest changed");
            }
        }
    }

    private static String retainedIdentity(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ChainPersistenceRecords.ContextRevisionRecord
            exactlyOneContext(
                    List<ChainPersistenceRecords.ContextRevisionRecord> contexts,
                    ChainPersistenceRecords.ModelInvocationRecord invocation) {
        List<ChainPersistenceRecords.ContextRevisionRecord> matches = contexts
                .stream().filter(value -> value.contextRevisionId().equals(
                        invocation.contextRevisionId())).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "failed model invocation Context is not unique");
        }
        var context = matches.get(0);
        if (!context.taskId().equals(invocation.taskId())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || context.role() != invocation.role()
                || context.workState() != invocation.workState()
                || !context.callReason().equals(invocation.callReason())
                || !Objects.equals(context.completionToken(),
                invocation.completionToken())
                || !context.runtimePolicyVersion().equals(
                invocation.runtimePolicyVersion())) {
            throw new IllegalStateException(
                    "failed model invocation Context identity is invalid");
        }
        return context;
    }

    private RoleProjection roleProjection(ReadPass pass) {
        List<Sequenced<ChainPersistenceRecords.TaskInstructionBindingRecord>>
                instructions = sequenced(pass.instructions(),
                pass.eventSequences());
        List<PendingProjection> pending = pass.pending().stream().map(value -> {
            List<ChainPersistenceRecords.PendingItemEventRecord> events =
                    pass.pendingEvents().get(value.gapId());
            var latest = events.isEmpty() ? null : events.get(events.size() - 1);
            ChainPendingItemStatus status = latest == null
                    ? ChainPendingItemStatus.PENDING : latest.eventKind();
            long sequence = latest == null
                    ? sequence(value, pass.eventSequences())
                    : sequence(latest, pass.eventSequences());
            return new PendingProjection(value, status,
                    latest == null ? value.gapId() : latest.eventId(), sequence,
                    sequence(value, pass.eventSequences()));
        }).sorted(Comparator.comparingLong(PendingProjection::authoritySequence))
                .toList();
        return new RoleProjection(pass.task().taskId(),
                pass.task().sourceInstructionId(), pass.authorityCut(),
                pass.boundary(), instructions, pass.instructionValues(),
                pass.contextFailures(),
                pass.modelFailures(),
                sequenced(pass.routes(), pass.eventSequences()),
                sequenced(pass.plans(), pass.eventSequences()),
                sequenced(pass.dispositions(), pass.eventSequences()),
                sequenced(pass.candidates(), pass.eventSequences()),
                sequenced(pass.modelFailureStepBlocks(),
                        pass.eventSequences()),
                sequenced(pass.actionReceiptStepBlocks(),
                        pass.eventSequences()),
                sequenced(pass.reviews(), pass.eventSequences()),
                sequenced(pass.accepted(), pass.eventSequences()), pending,
                sequenced(pass.permissions(), pass.eventSequences()),
                pass.proposals().stream().sorted(Comparator.comparingLong(
                        ProposalProjection::authoritySequence)).toList(),
                sequenced(pass.readiness(), pass.eventSequences()),
                pass.outcome().map(value -> new Sequenced<>(value,
                        sequence(value, pass.eventSequences()))),
                sequenced(pass.deliveries(), pass.eventSequences()),
                pass.deliveryEvents(),
                sequenced(pass.workspaceCandidates(), pass.eventSequences()),
                pass.retained().stepState());
    }

    private List<ChainPersistenceRecords.TransitionRecord> transitionsAt(
            String taskId, Map<String, Long> eventSequences) {
        Map<String, ChainPersistenceRecords.TransitionRecord> result =
                new LinkedHashMap<>();
        foundations.findAuthorityEvents(taskId,
                        eventSequences.values().stream().mapToLong(Long::longValue)
                                .max().orElse(0L))
                .stream().filter(value -> value.transitionId() != null)
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.AuthorityEventRecord
                                ::eventSequence))
                .forEach(event -> workflow.findTransition(event.transitionId())
                        .filter(value -> eventSequences.containsKey(
                                value.eventId()))
                        .ifPresent(value -> result.putIfAbsent(
                                value.transitionId(), value)));
        return List.copyOf(result.values());
    }

    private List<ContextFailureProjection> contextFailures(
            String taskId,
            List<ChainPersistenceRecords.ContextRevisionRecord> revisions,
            Map<String, Long> eventSequences) {
        List<ContextFailureProjection> result = new ArrayList<>();
        for (var revision : revisions) {
            var buildFailure = contextBuildFailures.findContextBuildFailure(
                    revision.contextRevisionId()).orElse(null);
            if (buildFailure != null) {
                verifyContextBuildFailure(taskId, revision, buildFailure);
                Long sequence = eventSequences.get(buildFailure.eventId());
                if (sequence == null) {
                    throw new AuthorityBeyondCut(buildFailure.eventId());
                }
                result.add(new ContextFailureProjection(
                        revision, buildFailure,
                        "CONTEXT_BUILD_FAILURE",
                        buildFailure.contextBuildFailureId(), sequence,
                        hasSuccessorContext(revisions, revision)));
            }
            if (revision.status()
                    == ChainContextRevisionStatus.INPUT_BLOCKED) {
                if (!"CONTEXT_INPUT_BLOCKED".equals(
                        revision.blockedErrorCode())) {
                    throw new IllegalStateException(
                            "blocked ContextRevision carries another error code");
                }
                result.add(new ContextFailureProjection(
                        revision, null, "CONTEXT_REVISION",
                        revision.contextRevisionId(), 0L,
                        hasSuccessorContext(revisions, revision)));
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasSuccessorContext(
            List<ChainPersistenceRecords.ContextRevisionRecord> revisions,
            ChainPersistenceRecords.ContextRevisionRecord failed) {
        return revisions.stream().anyMatch(value ->
                failed.contextRevisionId().equals(
                        value.parentContextRevisionId()));
    }

    private List<ChainPersistenceRecords.ContextRevisionRecord>
            contextRevisions(String taskId) {
        List<ChainPersistenceRecords.ContextRevisionRecord> all =
                contexts.findContextRevisions(taskId);
        if (all.stream().anyMatch(value ->
                !taskId.equals(value.taskId()))) {
            throw new IllegalStateException(
                    "ContextRevision query crossed the task boundary");
        }
        return all.stream().sorted(Comparator.comparing(
                ChainPersistenceRecords.ContextRevisionRecord
                        ::contextRevisionId)).toList();
    }

    private static void verifyContextBuildFailure(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord revision,
            ChainPersistenceRecords.ContextBuildFailureRecord failure) {
        if (revision.status() != ChainContextRevisionStatus.BUILDING
                || !taskId.equals(failure.taskId())
                || !revision.taskId().equals(failure.taskId())
                || !revision.contextRevisionId().equals(
                        failure.contextRevisionId())
                || revision.role() != failure.role()
                || revision.workState() != failure.workState()
                || !revision.callReason().equals(failure.callReason())
                || !revision.instructionId().equals(failure.instructionId())
                || !revision.projectorSetVersion().equals(
                        failure.projectorSetVersion())
                || !revision.paginationVersion().equals(
                        failure.paginationVersion())
                || !revision.runtimePolicyVersion().equals(
                        failure.runtimePolicyVersion())
                || !"CONTEXT_INPUT_BLOCKED".equals(failure.errorCode())) {
            throw new IllegalStateException(
                    "ContextBuildFailure is not bound to its BUILDING ContextRevision");
        }
    }

    private static final class AuthorityBeyondCut
            extends RuntimeException {
        private AuthorityBeyondCut(String eventId) {
            super(eventId);
        }
    }

    private static ChainRecoveryRuntime.TransitionRef transitionRef(
            String taskId,
            ChainPersistenceRecords.TransitionRecord transition,
            List<ChainPersistenceRecords.TransitionStageRecord> stageValues,
            Map<String, Long> eventSequences) {
        if (!transition.taskId().equals(taskId)) {
            throw new IllegalStateException("transition belongs to another task");
        }
        List<ChainPersistenceRecords.TransitionStageRecord> stages = stageValues
                .stream().sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal)).toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            if (!stage.taskId().equals(taskId)
                    || !stage.transitionId().equals(transition.transitionId())
                    || stage.stageOrdinal() != index) {
                throw new IllegalStateException(
                        "transition stage prefix is corrupt");
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "transition stage prefix is corrupt", invalid);
            }
            prefix.add(stage.stageCode());
        }
        ChainTransitionStage persisted = stages.isEmpty()
                ? ChainTransitionStage.OPEN
                : stages.get(stages.size() - 1).stageCode();
        if (persisted == ChainTransitionStage.COMPLETE) {
            throw new IllegalStateException(
                    "incomplete transition query returned COMPLETE");
        }
        return new ChainRecoveryRuntime.TransitionRef(
                transition.transitionId(), taskId, transition.transitionType(),
                persisted, sequence(transition, eventSequences));
    }

    private static <T extends ChainPersistenceRecords.TaskAuthorityFact>
            List<T> cut(String taskId, List<T> values,
            Map<String, Long> eventSequences) {
        return values.stream().filter(value -> {
            if (!taskId.equals(value.taskId())) {
                throw new IllegalStateException(
                        "formal fact belongs to another task");
            }
            return eventSequences.containsKey(value.eventId());
        }).sorted(Comparator.comparingLong(value ->
                sequence(value, eventSequences))).toList();
    }

    private static <T extends ChainPersistenceRecords.TaskAuthorityFact>
            List<Sequenced<T>> sequenced(
            List<T> values, Map<String, Long> eventSequences) {
        return values.stream().map(value -> new Sequenced<>(value,
                sequence(value, eventSequences))).toList();
    }

    private static long sequence(
            ChainPersistenceRecords.TaskAuthorityFact value,
            Map<String, Long> eventSequences) {
        Long sequence = eventSequences.get(value.eventId());
        if (sequence == null) {
            throw new IllegalStateException(
                    "formal fact is outside the recovery cut");
        }
        return sequence;
    }

    private static void validateProposalPrefix(
            String taskId,
            String proposalId,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states) {
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposalId)
                    || state.stateSequence() != index + 1L) {
                throw new IllegalStateException(
                        "proposal state prefix is not bound to its proposal");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "proposal state prefix is corrupt", invalid);
            }
            prefix.add(state.stateKind());
        }
    }

    private static void validatePendingPrefix(
            ChainPersistenceRecords.PendingItemRecord item,
            List<ChainPersistenceRecords.PendingItemEventRecord> events) {
        ChainPendingItemStatus status = ChainPendingItemStatus.PENDING;
        int responseRound = 0;
        String answerInstructionId = null;
        for (var event : events) {
            if (!event.taskId().equals(item.taskId())
                    || !event.gapId().equals(item.gapId())) {
                throw new IllegalStateException(
                        "pending event prefix belongs to another gap");
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
                    boolean permissionResolution = common
                            && (status == ChainPendingItemStatus.PENDING
                            || status
                            == ChainPendingItemStatus.RESPONSE_RECEIVED)
                            && item.pendingType()
                            == ChainPendingItemType.PERMISSION
                            && event.validationInvocationId() == null
                            && event.gapValidationOutcome() == null;
                    boolean modelResolution = common
                            && status
                            == ChainPendingItemStatus.RESPONSE_RECEIVED
                            && hasText(event.validationInvocationId())
                            && event.gapValidationOutcome()
                            == GapValidation.Outcome.RESOLVED;
                    yield permissionResolution || modelResolution;
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
                throw new IllegalStateException(
                        "pending event prefix is corrupt");
            }
            status = event.eventKind();
            if (status == ChainPendingItemStatus.RESPONSE_RECEIVED) {
                responseRound = event.responseRound();
                answerInstructionId = event.answerInstructionId();
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Map<String, Long> authoritySequences(
            String taskId, long cut,
            List<ChainPersistenceRecords.AuthorityEventRecord> authorities) {
        Map<String, Long> values = new HashMap<>();
        long previous = 0;
        List<ChainPersistenceRecords.AuthorityEventRecord> ordered = authorities
                .stream().sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.AuthorityEventRecord
                                ::eventSequence)).toList();
        for (var authority : ordered) {
            if (!authority.taskId().equals(taskId)
                    || authority.eventSequence() != previous + 1L
                    || authority.eventSequence() > cut
                    || values.putIfAbsent(authority.eventId(),
                    authority.eventSequence()) != null) {
                throw new IllegalStateException("authority-event cut is corrupt");
            }
            previous = authority.eventSequence();
        }
        if (previous != cut && cut != 0) {
            throw new IllegalStateException(
                    "authority-event cut is not a complete prefix");
        }
        return Map.copyOf(values);
    }

    private static void addRetained(
            Map<ChainRecoveryRuntime.RecoveryFactKind, List<String>> refs,
            StableAuthoritySnapshot snapshot,
            StableFactKind sourceKind,
            ChainRecoveryRuntime.RecoveryFactKind targetKind) {
        snapshot.facts().stream().filter(value -> value.kind() == sourceKind)
                .forEach(value -> add(refs, targetKind,
                        value.authorityType(), value.authorityRef() + ":"
                                + value.identityDigest() + ":"
                                + value.status()));
    }

    private static EnumMap<ChainRecoveryRuntime.RecoveryFactKind, List<String>>
            emptyCuts() {
        EnumMap<ChainRecoveryRuntime.RecoveryFactKind, List<String>> result =
                new EnumMap<>(ChainRecoveryRuntime.RecoveryFactKind.class);
        for (ChainRecoveryRuntime.RecoveryFactKind kind
                : ChainRecoveryRuntime.RecoveryFactKind.values()) {
            result.put(kind, new ArrayList<>());
        }
        return result;
    }

    private static void add(
            Map<ChainRecoveryRuntime.RecoveryFactKind, List<String>> refs,
            ChainRecoveryRuntime.RecoveryFactKind kind,
            String type, String id) {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            throw new IllegalStateException("formal recovery reference is blank");
        }
        refs.get(kind).add(type + ":" + id);
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values.stream().sorted().toList()) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public interface StableAuthoritySource {
        StableAuthoritySnapshot freeze(StableAuthorityRequest request);
    }

    public record StableAuthorityRequest(
            ChainPersistenceRecords.TaskRecord task,
            long chainAuthorityCut,
            List<ChainPersistenceRecords.PlanBindingRecord> planBindings,
            List<ChainPersistenceRecords.ActionBindingRecord> actions,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                    workspaceCandidates,
            List<ChainPersistenceRecords.FinalizationReadinessRecord> readiness,
            Map<String, List<ChainPersistenceRecords.FinalizationCheckRecord>>
                    finalizationChecks) {
        public StableAuthorityRequest {
            Objects.requireNonNull(task, "task");
            if (chainAuthorityCut < 0) {
                throw new IllegalArgumentException(
                        "chainAuthorityCut must not be negative");
            }
            planBindings = List.copyOf(planBindings);
            actions = List.copyOf(actions);
            workspaceCandidates = List.copyOf(workspaceCandidates);
            readiness = List.copyOf(readiness);
            finalizationChecks = Map.copyOf(finalizationChecks);
        }

        public String taskId() {
            return task.taskId();
        }
    }

    public record StableAuthoritySnapshot(
            String taskId,
            long chainAuthorityCut,
            String readBoundary,
            List<StableAuthorityFact> facts,
            Optional<StepState> stepState) {
        public StableAuthoritySnapshot {
            required(taskId, "taskId");
            if (chainAuthorityCut < 0) {
                throw new IllegalArgumentException(
                        "chainAuthorityCut must not be negative");
            }
            required(readBoundary, "readBoundary");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            stepState = Objects.requireNonNull(stepState, "stepState");
        }

        public static StableAuthoritySnapshot empty(
                String taskId, long chainAuthorityCut) {
            return new StableAuthoritySnapshot(taskId, chainAuthorityCut,
                    "no-retained-authority", List.of(), Optional.empty());
        }
    }

    public record StableAuthorityFact(
            StableFactKind kind,
            String authorityType,
            String authorityRef,
            String identityDigest,
            String status) {
        public StableAuthorityFact {
            Objects.requireNonNull(kind, "kind");
            required(authorityType, "authorityType");
            required(authorityRef, "authorityRef");
            required(identityDigest, "identityDigest");
            if (!identityDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "identityDigest must be lowercase SHA-256");
            }
            required(status, "status");
        }
    }

    /**
     * Exposes only the identities needed to resume the selector's exact model
     * action.  The view is derived from the already frozen recovery
     * projection; it never performs a second "latest" read.
     */
    public static FrozenModelInput frozenModelInput(
            ChainRecoveryRuntime.RecoverySnapshot snapshot,
            ChainRecoveryRuntime.NextDirective directive) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(directive, "directive");
        if (!(snapshot.roleProjection() instanceof RoleProjection projection)
                || !snapshot.taskId().equals(projection.taskId())) {
            throw new IllegalStateException(
                    "CHAIN_MODEL_FROZEN_PROJECTION_INVALID");
        }
        var instruction = ProductChainNextRoleSelector
                .currentModelInstruction(snapshot);
        boolean sourcePresent = projection.stepState().stream().anyMatch(
                value -> value.authorityType().equals(
                        directive.sourceAuthorityType())
                        && value.authorityRef().equals(
                        directive.sourceAuthorityRef())) || switch (
                directive.sourceAuthorityType()) {
            case "TASK" -> projection.taskId().equals(
                    directive.sourceAuthorityRef());
            case "INSTRUCTION" -> instruction.instructionId().equals(
                    directive.sourceAuthorityRef());
            case "ROUTE_DECISION" -> projection.routes().stream().anyMatch(
                    value -> value.value().routeDecisionId().equals(
                            directive.sourceAuthorityRef()));
            case "PLAN_BINDING" -> projection.plans().stream().anyMatch(
                    value -> value.value().planBindingId().equals(
                            directive.sourceAuthorityRef()));
            case "CANDIDATE_STEP_RESULT" -> projection.candidates().stream()
                    .anyMatch(value -> value.value().candidateResultId().equals(
                            directive.sourceAuthorityRef()));
            case "REVIEW_DECISION" -> projection.reviews().stream().anyMatch(
                    value -> value.value().reviewDecisionId().equals(
                            directive.sourceAuthorityRef()));
            case "TASK_OUTCOME" -> projection.outcome().stream().anyMatch(
                    value -> value.value().outcomeId().equals(
                            directive.sourceAuthorityRef()));
            case "PENDING_ITEM" -> projection.pending().stream().anyMatch(
                    value -> value.item().gapId().equals(
                            directive.sourceAuthorityRef()));
            case "PROPOSAL_STATE" -> projection.proposals().stream().anyMatch(
                    value -> value.latest().eventId().equals(
                            directive.sourceAuthorityRef()));
            case "ACTION_RECEIPT_STEP_BLOCK" -> projection
                    .actionReceiptStepBlocks().stream().anyMatch(value ->
                            value.value().stepBlockId().equals(
                                    directive.sourceAuthorityRef()));
            case "FINALIZATION_CHECK", "PUBLISH_FAILURE" ->
                    frozenAuthorityPresent(snapshot,
                            directive.sourceAuthorityType(),
                            directive.sourceAuthorityRef());
            default -> false;
        };
        if (!sourcePresent) {
            throw new IllegalStateException(
                    "CHAIN_MODEL_SELECTOR_SOURCE_NOT_IN_FROZEN_CUT");
        }
        String planRevisionId = projection.stepState()
                .map(StepState::planRevisionId).orElse(null);
        if ("CANDIDATE_STEP_RESULT".equals(
                directive.sourceAuthorityType())) {
            planRevisionId = projection.candidates().stream()
                    .filter(value -> value.value().candidateResultId().equals(
                            directive.sourceAuthorityRef()))
                    .map(value -> value.value().planRevisionId())
                    .findFirst().orElseThrow();
        } else if ("TASK_OUTCOME".equals(
                directive.sourceAuthorityType())) {
            planRevisionId = projection.outcome().orElseThrow()
                    .value().finalPlanRevisionId();
        }
        final String exactRevisionId = planRevisionId;
        List<ChainPersistenceRecords.PlanBindingRecord> bindings =
                exactRevisionId == null ? List.of() : projection.plans()
                .stream().map(Sequenced::value)
                .filter(value -> exactRevisionId.equals(
                        value.planRevisionId())).toList();
        if (bindings.size() > 1) {
            throw new IllegalStateException(
                    "CHAIN_MODEL_PLAN_BINDING_AMBIGUOUS");
        }
        FrozenStepInput step = projection.stepState().map(value ->
                new FrozenStepInput(value.planRevisionId(), value.stepId(),
                        value.activationEventId(), value.status())).orElse(null);
        FrozenCandidateInput candidate = projection.workspaceCandidates()
                .stream().max(Comparator.comparingLong(
                        Sequenced::authoritySequence))
                .map(value -> new FrozenCandidateInput(
                        value.value().workspaceId(),
                        value.value().artifactId(),
                        value.value().candidateFingerprint()))
                .orElse(null);
        String routeDecisionId = projection.routes().stream()
                .filter(value -> value.value().instructionId().equals(
                        instruction.instructionId()))
                .max(Comparator.comparingLong(Sequenced::authoritySequence))
                .map(value -> value.value().routeDecisionId()).orElse(null);
        String candidateResultId = projection.candidates().stream()
                .max(Comparator.comparingLong(Sequenced::authoritySequence))
                .map(value -> value.value().candidateResultId()).orElse(null);
        List<PendingProjection> currentPending = projection.pending().stream()
                .filter(value -> value.status()
                        == ChainPendingItemStatus.PENDING
                        || value.status()
                        == ChainPendingItemStatus.RESPONSE_RECEIVED)
                .toList();
        if (currentPending.size() > 1) {
            throw new IllegalStateException(
                    "CHAIN_MODEL_PENDING_ITEM_AMBIGUOUS");
        }
        String pendingGapId = currentPending.isEmpty() ? null
                : currentPending.get(0).item().gapId();
        boolean explicitReplacementReintake = instruction.relationKind()
                == io.paperagent.v2.chain.ChainInstructionRelation.REPLACEMENT
                && projection.instructions().size() == 2
                && projection.taskSourceInstructionId().equals(
                        instruction.instructionId())
                && projection.instructions().get(0).value().relationRole()
                        == ChainPersistenceRecords.BindingRole.INHERITED_ROOT
                && projection.instructions().get(1).value().relationRole()
                        == ChainPersistenceRecords.BindingRole.ORIGIN
                && projection.instructions().get(1).value().instructionId()
                        .equals(instruction.instructionId());
        return new FrozenModelInput(instruction,
                bindings.isEmpty() ? null : bindings.get(0), step,
                candidate, routeDecisionId, candidateResultId,
                pendingGapId, explicitReplacementReintake);
    }

    private static boolean frozenAuthorityPresent(
            ChainRecoveryRuntime.RecoverySnapshot snapshot,
            String authorityType,
            String authorityRef) {
        String exact = authorityType + ":" + authorityRef;
        int qualifiers = "PUBLISH_FAILURE".equals(authorityType) ? 2 : 1;
        return snapshot.factCuts().stream()
                .filter(value -> value.kind() == ChainRecoveryRuntime
                        .RecoveryFactKind.VALIDATION_FINALIZATION_AND_PUBLISH)
                .flatMap(value -> value.authorityRefs().stream())
                .anyMatch(value -> frozenAuthorityIdentity(
                        value, qualifiers).equals(exact));
    }

    private static String frozenAuthorityIdentity(
            String value,
            int qualifierCount) {
        String identity = value;
        for (int index = 0; index < qualifierCount; index++) {
            int separator = identity.lastIndexOf(':');
            if (separator < 0) return "";
            identity = identity.substring(0, separator);
        }
        return identity;
    }

    public record FrozenModelInput(
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.PlanBindingRecord planBinding,
            FrozenStepInput step,
            FrozenCandidateInput candidate,
            String routeDecisionId,
            String candidateResultId,
            String pendingGapId,
            boolean explicitReplacementReintake) {
        public FrozenModelInput {
            Objects.requireNonNull(instruction, "instruction");
        }

        public FrozenModelInput(
                ChainPersistenceRecords.InstructionRecord instruction,
                ChainPersistenceRecords.PlanBindingRecord planBinding) {
            this(instruction, planBinding, null, null);
        }

        public FrozenModelInput(
                ChainPersistenceRecords.InstructionRecord instruction,
                ChainPersistenceRecords.PlanBindingRecord planBinding,
                FrozenStepInput step,
                FrozenCandidateInput candidate) {
            this(instruction, planBinding, step, candidate, null, null, null,
                    false);
        }
    }

    public record FrozenCandidateInput(
            String workspaceId, long artifactId,
            String candidateFingerprint) {
        public FrozenCandidateInput {
            required(workspaceId, "workspaceId");
            if (artifactId < 1) throw new IllegalArgumentException(
                    "artifactId must be positive");
            required(candidateFingerprint, "candidateFingerprint");
        }
    }

    public record FrozenStepInput(
            String planRevisionId, String stepId, String activationEventId,
            ChainStepStatus status) {
        public FrozenStepInput {
            required(planRevisionId, "planRevisionId");
            required(stepId, "stepId");
            Objects.requireNonNull(status, "status");
            if (status == ChainStepStatus.ACTIVE) {
                required(activationEventId, "activationEventId");
            }
        }
    }

    public enum StableFactKind {
        TASKFRAME_PLAN_STEP,
        EFFECT_INTENT_RECEIPT_ERROR,
        WORKSPACE_CANDIDATE,
        VALIDATION_AND_PUBLISH,
        IN_FLIGHT_ACTION
    }

    public record StepState(
            String planRevisionId,
            String stepId,
            String activationEventId,
            ChainStepStatus status,
            String authorityType,
            String authorityRef,
            long authoritySequence) {
        public StepState {
            required(planRevisionId, "planRevisionId");
            required(stepId, "stepId");
            Objects.requireNonNull(status, "status");
            required(authorityType, "authorityType");
            required(authorityRef, "authorityRef");
            if (status == ChainStepStatus.ACTIVE
                    && (activationEventId == null
                    || activationEventId.isBlank())) {
                throw new IllegalArgumentException(
                        "ACTIVE step requires activationEventId");
            }
            if (authoritySequence < 0
                    || (status == ChainStepStatus.ACTIVE
                    && authoritySequence == 0)) {
                throw new IllegalArgumentException(
                        "authoritySequence is invalid for the Step state");
            }
        }
    }

    record Sequenced<T>(T value, long authoritySequence) {
        Sequenced {
            Objects.requireNonNull(value, "value");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    record PendingProjection(
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPendingItemStatus status,
            String authorityRef,
            long authoritySequence,
            long sourceAuthoritySequence) {
        PendingProjection {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(status, "status");
            required(authorityRef, "authorityRef");
            if (authoritySequence < 1 || sourceAuthoritySequence < 1
                    || sourceAuthoritySequence > authoritySequence) {
                throw new IllegalArgumentException(
                        "PendingItem authority sequence is invalid");
            }
        }
    }

    record ProposalProjection(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states,
            ChainPersistenceRecords.ProposalStateEventRecord latest,
            long authoritySequence) {
        ProposalProjection {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(proposal, "proposal");
            states = List.copyOf(Objects.requireNonNull(states, "states"));
            Objects.requireNonNull(latest, "latest");
        }
    }

    record ContextFailureProjection(
            ChainPersistenceRecords.ContextRevisionRecord contextRevision,
            ChainPersistenceRecords.ContextBuildFailureRecord buildFailure,
            String sourceAuthorityType,
            String sourceAuthorityRef,
            long authoritySequence,
            boolean successorContextPresent) {
        ContextFailureProjection {
            Objects.requireNonNull(contextRevision, "contextRevision");
            required(sourceAuthorityType, "sourceAuthorityType");
            required(sourceAuthorityRef, "sourceAuthorityRef");
            if (buildFailure == null) {
                if (contextRevision.status()
                        != ChainContextRevisionStatus.INPUT_BLOCKED
                        || authoritySequence != 0L
                        || !"CONTEXT_REVISION".equals(sourceAuthorityType)
                        || !contextRevision.contextRevisionId().equals(
                                sourceAuthorityRef)) {
                    throw new IllegalArgumentException(
                            "terminal Context failure identity is invalid");
                }
            } else if (contextRevision.status()
                    != ChainContextRevisionStatus.BUILDING
                    || authoritySequence < 1
                    || !"CONTEXT_BUILD_FAILURE".equals(sourceAuthorityType)
                    || !buildFailure.contextBuildFailureId().equals(
                            sourceAuthorityRef)) {
                throw new IllegalArgumentException(
                        "build Context failure identity is invalid");
            }
        }
    }

    record ModelFailureProjection(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts,
            ChainPersistenceRecords.ProviderAttemptRecord lastAttempt,
            int contextInvocationCount,
            boolean successorInvocationPresent,
            boolean formalReviewInvocationPresent) {
        ModelFailureProjection {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(invocation, "invocation");
            attempts = List.copyOf(attempts);
            Objects.requireNonNull(lastAttempt, "lastAttempt");
            if (attempts.size()
                    != ChainRuntimePolicy.requireVersion(
                    invocation.runtimePolicyVersion())
                    .providerAttemptsTotal()
                    || !attempts.get(attempts.size() - 1)
                    .equals(lastAttempt)
                    || contextInvocationCount < 1) {
                throw new IllegalArgumentException(
                        "model failure attempt identity is invalid");
            }
        }
    }

    record RoleProjection(
            String taskId,
            String taskSourceInstructionId,
            long authorityCut,
            String readBoundary,
            List<Sequenced<ChainPersistenceRecords.TaskInstructionBindingRecord>>
                    instructions,
            Map<String, ChainPersistenceRecords.InstructionRecord>
                    instructionValues,
            List<ContextFailureProjection> contextFailures,
            List<ModelFailureProjection> modelFailures,
            List<Sequenced<ChainPersistenceRecords.RouteDecisionRecord>> routes,
            List<Sequenced<ChainPersistenceRecords.PlanBindingRecord>> plans,
            List<Sequenced<ChainPersistenceRecords.InstructionDispositionRecord>> dispositions,
            List<Sequenced<ChainPersistenceRecords.CandidateStepResultRecord>>
                    candidates,
            List<Sequenced<ChainPersistenceRecords
                    .ModelFailureStepBlockRecord>> modelFailureStepBlocks,
            List<Sequenced<ChainPersistenceRecords
                    .ActionReceiptStepBlockRecord>> actionReceiptStepBlocks,
            List<Sequenced<ChainPersistenceRecords.ReviewDecisionRecord>> reviews,
            List<Sequenced<ChainPersistenceRecords.AcceptedResultRecord>> accepted,
            List<PendingProjection> pending,
            List<Sequenced<ChainPersistenceRecords.PermissionDecisionRecord>>
                    permissions,
            List<ProposalProjection> proposals,
            List<Sequenced<ChainPersistenceRecords.FinalizationReadinessRecord>>
                    readiness,
            Optional<Sequenced<ChainPersistenceRecords.TaskOutcomeRecord>>
                    outcome,
            List<Sequenced<ChainPersistenceRecords.DeliveryRecord>> deliveries,
            Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                    deliveryEvents,
            List<Sequenced<ChainPersistenceRecords.WorkspaceCandidateRecord>>
                    workspaceCandidates,
            Optional<StepState> stepState)
            implements ChainRecoveryRuntime.FrozenRoleProjection {
        RoleProjection(
                String taskId, String taskSourceInstructionId,
                long authorityCut, String readBoundary,
                List<Sequenced<ChainPersistenceRecords
                        .TaskInstructionBindingRecord>> instructions,
                Map<String, ChainPersistenceRecords.InstructionRecord>
                        instructionValues,
                List<ContextFailureProjection> contextFailures,
                List<ModelFailureProjection> modelFailures,
                List<Sequenced<ChainPersistenceRecords.RouteDecisionRecord>> routes,
                List<Sequenced<ChainPersistenceRecords.PlanBindingRecord>> plans,
                List<Sequenced<ChainPersistenceRecords
                        .InstructionDispositionRecord>> dispositions,
                List<Sequenced<ChainPersistenceRecords
                        .CandidateStepResultRecord>> candidates,
                List<Sequenced<ChainPersistenceRecords
                        .ModelFailureStepBlockRecord>> modelFailureStepBlocks,
                List<Sequenced<ChainPersistenceRecords.ReviewDecisionRecord>> reviews,
                List<Sequenced<ChainPersistenceRecords.AcceptedResultRecord>> accepted,
                List<PendingProjection> pending,
                List<Sequenced<ChainPersistenceRecords
                        .PermissionDecisionRecord>> permissions,
                List<ProposalProjection> proposals,
                List<Sequenced<ChainPersistenceRecords
                        .FinalizationReadinessRecord>> readiness,
                Optional<Sequenced<ChainPersistenceRecords.TaskOutcomeRecord>>
                        outcome,
                List<Sequenced<ChainPersistenceRecords.DeliveryRecord>> deliveries,
                Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                        deliveryEvents,
                List<Sequenced<ChainPersistenceRecords
                        .WorkspaceCandidateRecord>> workspaceCandidates,
                Optional<StepState> stepState) {
            this(taskId, taskSourceInstructionId, authorityCut, readBoundary,
                    instructions, instructionValues, contextFailures,
                    modelFailures, routes, plans, dispositions, candidates,
                    modelFailureStepBlocks, List.of(), reviews, accepted,
                    pending, permissions, proposals, readiness, outcome,
                    deliveries, deliveryEvents, workspaceCandidates,
                    stepState);
        }

        RoleProjection(
                String taskId, String taskSourceInstructionId,
                long authorityCut, String readBoundary,
                List<Sequenced<ChainPersistenceRecords
                        .TaskInstructionBindingRecord>> instructions,
                Map<String, ChainPersistenceRecords.InstructionRecord>
                        instructionValues,
                List<ContextFailureProjection> contextFailures,
                List<Sequenced<ChainPersistenceRecords.RouteDecisionRecord>>
                        routes,
                List<Sequenced<ChainPersistenceRecords.PlanBindingRecord>> plans,
                List<Sequenced<ChainPersistenceRecords
                        .InstructionDispositionRecord>> dispositions,
                List<Sequenced<ChainPersistenceRecords
                        .CandidateStepResultRecord>> candidates,
                List<Sequenced<ChainPersistenceRecords.ReviewDecisionRecord>>
                        reviews,
                List<Sequenced<ChainPersistenceRecords.AcceptedResultRecord>>
                        accepted,
                List<PendingProjection> pending,
                List<Sequenced<ChainPersistenceRecords
                        .PermissionDecisionRecord>> permissions,
                List<ProposalProjection> proposals,
                List<Sequenced<ChainPersistenceRecords
                        .FinalizationReadinessRecord>> readiness,
                Optional<Sequenced<ChainPersistenceRecords.TaskOutcomeRecord>>
                        outcome,
                List<Sequenced<ChainPersistenceRecords.DeliveryRecord>>
                        deliveries,
                Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                        deliveryEvents,
                List<Sequenced<ChainPersistenceRecords
                        .WorkspaceCandidateRecord>> workspaceCandidates,
                Optional<StepState> stepState) {
            this(taskId, taskSourceInstructionId, authorityCut, readBoundary,
                    instructions, instructionValues, contextFailures,
                    List.of(), routes, plans, dispositions, candidates,
                    List.of(), List.of(), reviews, accepted, pending, permissions,
                    proposals, readiness, outcome, deliveries, deliveryEvents,
                    workspaceCandidates, stepState);
        }

        RoleProjection {
            required(taskId, "taskId");
            required(taskSourceInstructionId, "taskSourceInstructionId");
            if (authorityCut < 0) {
                throw new IllegalArgumentException(
                        "authorityCut must be non-negative");
            }
            required(readBoundary, "readBoundary");
            instructions = List.copyOf(instructions);
            instructionValues = Map.copyOf(instructionValues);
            contextFailures = List.copyOf(contextFailures);
            modelFailures = List.copyOf(modelFailures);
            routes = List.copyOf(routes);
            plans = List.copyOf(plans);
            dispositions = List.copyOf(dispositions);
            candidates = List.copyOf(candidates);
            modelFailureStepBlocks = List.copyOf(modelFailureStepBlocks);
            actionReceiptStepBlocks = List.copyOf(actionReceiptStepBlocks);
            reviews = List.copyOf(reviews);
            accepted = List.copyOf(accepted);
            pending = List.copyOf(pending);
            permissions = List.copyOf(permissions);
            proposals = List.copyOf(proposals);
            readiness = List.copyOf(readiness);
            Objects.requireNonNull(outcome, "outcome");
            deliveries = List.copyOf(deliveries);
            deliveryEvents = Map.copyOf(deliveryEvents);
            workspaceCandidates = List.copyOf(workspaceCandidates);
            Objects.requireNonNull(stepState, "stepState");
        }
    }

    private record ReadPass(
            ChainPersistenceRecords.TaskRecord task,
            long authorityCut,
            String boundary,
            Map<String, Long> eventSequences,
            List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                    instructions,
            Map<String, ChainPersistenceRecords.InstructionRecord>
                    instructionValues,
            List<ChainPersistenceRecords.ContextRevisionRecord>
                    contextRevisions,
            List<ContextFailureProjection> contextFailures,
            long invocationOrdinalCut,
            List<ChainPersistenceRecords.ModelInvocationRecord> invocations,
            Map<String, List<ChainPersistenceRecords.ProviderAttemptRecord>>
                    providerAttempts,
            List<ModelFailureProjection> modelFailures,
            List<ChainPersistenceRecords.RouteDecisionRecord> routes,
            List<ChainPersistenceRecords.PlanBindingRecord> plans,
            List<ChainPersistenceRecords.InstructionDispositionRecord> dispositions,
            List<ChainPersistenceRecords.CandidateStepResultRecord> candidates,
            List<ChainPersistenceRecords.ModelFailureStepBlockRecord>
                    modelFailureStepBlocks,
            List<ChainPersistenceRecords.ActionReceiptStepBlockRecord>
                    actionReceiptStepBlocks,
            List<ChainPersistenceRecords.ReviewDecisionRecord> reviews,
            List<ChainPersistenceRecords.AcceptedResultRecord> accepted,
            List<ChainPersistenceRecords.ResultApplicabilityRecord>
                    applicability,
            List<ChainPersistenceRecords.PendingItemRecord> pending,
            Map<String, List<ChainPersistenceRecords.PendingItemEventRecord>>
                    pendingEvents,
            List<ChainPersistenceRecords.PermissionDecisionRecord> permissions,
            List<ChainPersistenceRecords.ActionBindingRecord> actions,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                    workspaceCandidates,
            List<ChainPersistenceRecords.TransitionRecord> transitions,
            Map<String, List<ChainPersistenceRecords.TransitionStageRecord>>
                    transitionStages,
            List<ChainPersistenceRecords.TransitionRecord>
                    incompleteTransitions,
            List<ChainPersistenceRecords.FinalizationReadinessRecord> readiness,
            Map<String, List<ChainPersistenceRecords.FinalizationCheckRecord>>
                    checks,
            Optional<ChainPersistenceRecords.TaskOutcomeRecord> outcome,
            List<ChainPersistenceRecords.DeliveryRecord> deliveries,
            Map<String, List<ChainPersistenceRecords.DeliveryEventRecord>>
                    deliveryEvents,
            List<ProposalProjection> proposals,
            StableAuthoritySnapshot retained) {
        ReadPass {
            providerAttempts = providerAttempts.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            value -> List.copyOf(value.getValue())));
            modelFailures = List.copyOf(modelFailures);
        }
    }
}
