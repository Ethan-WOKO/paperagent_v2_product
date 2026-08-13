package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainAcceptedResultWriter;
import io.paperagent.v2.chain.ChainCandidateStepResultWriter;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainPersistenceRecords.*;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ChainStepTestStore implements
        ChainStepAuthorityPort,
        ChainWorkflowRepository,
        ChainFoundationRepository,
        ChainModelRepository,
        ChainContextRepository,
        ChainCandidateStepResultWriter,
        ChainAcceptedResultWriter,
        io.paperagent.v2.chain.ChainActionBindingWriter,
        io.paperagent.v2.chain.ChainReadinessWriter,
        io.paperagent.v2.chain.ChainFinalizationRepository,
        ChainCandidateProposalBinder,
        ChainActionProposalBinder,
        ChainProgressAuthorityPort,
        ChainStepCommitGate,
        ChainReadinessAuthorityPort {
    PlanSnapshot plan;
    final List<StepEvent> stepEvents = new ArrayList<>();
    final List<AuthorityEventRecord> authorityEvents = new ArrayList<>();
    final List<CandidateStepResultRecord> candidates = new ArrayList<>();
    final List<ReviewDecisionRecord> reviews = new ArrayList<>();
    final List<AcceptedResultRecord> accepted = new ArrayList<>();
    final List<ResultApplicabilityRecord> applicability = new ArrayList<>();
    final List<PendingItemRecord> openPending = new ArrayList<>();
    final List<TransitionRecord> transitions = new ArrayList<>();
    final List<TransitionStageRecord> transitionStages = new ArrayList<>();
    final List<PlanBindingRecord> planBindings = new ArrayList<>();
    final List<ActionBindingRecord> actions = new ArrayList<>();
    final List<ActionBindingRecord> inFlightActions = new ArrayList<>();
    ChainProgressAuthorityPort.ProgressSnapshot progressSnapshot =
            new ChainProgressAuthorityPort.ProgressSnapshot(0, List.of());
    boolean failBindingOnce;
    boolean failActionBindingOnce;
    Instant authoritativeActionTime;
    int bindingAttempts;
    int actionBindingAttempts;
    boolean gateBlocked;
    ChainReadinessAuthorityPort.VerifiedReadinessMaterial readinessMaterial;
    final List<FinalizationReadinessRecord> readinessFacts =
            new ArrayList<>();
    final Map<String, ModelProposalRecord> proposals = new HashMap<>();
    final Map<String, ModelInvocationRecord> invocations = new HashMap<>();
    final Map<String, ContextRevisionRecord> contexts = new HashMap<>();
    final Map<String, ContentRecord> contents = new HashMap<>();
    final Map<String, List<ProposalStateEventRecord>> proposalStates =
            new HashMap<>();

    public static ActionBindingRecord commitWorkspaceChangeForBoundary(
            String body,
            String bodyTaskId,
            String bodyInvocationId,
            ChainContentKind bodyKind) {
        return commitWorkspaceChangeForBoundary(
                body, bodyTaskId, bodyInvocationId, bodyKind, null);
    }

    public static ActionBindingRecord commitWorkspaceChangeForBoundary(
            String body,
            String bodyTaskId,
            String bodyInvocationId,
            ChainContentKind bodyKind,
            Instant authoritativeActionTime) {
        ChainStepTestStore store = new ChainStepTestStore();
        Instant now = Instant.parse("2026-08-07T00:00:00Z");
        store.authoritativeActionTime = authoritativeActionTime;
        String taskId = "task-1";
        String invocationId = "invocation-workspace";
        store.contexts.put("context-workspace", new ContextRevisionRecord(
                "context-workspace", taskId, null, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "workspace-change",
                "instruction-1", "frame-1", "plan-1", "revision-1",
                1L, "step-1", "activation-1", null, null,
                "workspace-1", null, null, null, null, null,
                "projectors-v1", "pagination-v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new FormattedJson(1, "{}"), sha256("request-workspace"),
                "token-workspace", null, null, now, now));
        store.invocations.put(invocationId, new ModelInvocationRecord(
                invocationId, taskId, "context-workspace", "token-workspace",
                ChainRole.EXECUTOR, ChainWorkState.EXECUTING,
                "workspace-change", "provider", "model", 1,
                ChainRuntimePolicy.V1.policyVersion(), now));
        String contentId = "content-workspace";
        store.contents.put(contentId, new ContentRecord(
                contentId, bodyTaskId, bodyInvocationId, bodyKind,
                body, sha256(body), "application/json", now));
        String proposalId = "proposal-workspace";
        store.proposals.put(proposalId, new ModelProposalRecord(
                proposalId, taskId, invocationId, 1, ChainRole.EXECUTOR,
                ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE,
                canonical("{\"change\":\"workspace\"}"), canonical("[]"),
                ChainContentKind.WORKSPACE_CHANGE_BODY.name(), contentId, now));
        store.proposalStates.put(proposalId, List.of(
                new ProposalStateEventRecord(
                        proposalId, 1, taskId, "event-proposal-workspace-accepted",
                        ChainProposalState.ACCEPTED, null, null, now)));
        return new ChainActionRuntime(
                store, store, store, store, store, store)
                .commit(new ChainActionRuntime.ActionCommand(
                        taskId, proposalId, now))
                .fact();
    }

    private static CanonicalJson canonical(String json) {
        return new CanonicalJson(1, sha256(json), json);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public Optional<PlanSnapshot> findPlan(
            String taskId, String planRevisionId) {
        return plan != null && plan.taskId().equals(taskId)
                && plan.planRevisionId().equals(planRevisionId)
                ? Optional.of(plan) : Optional.empty();
    }

    @Override
    public List<StepEvent> findStepEvents(
            String taskId, String planRevisionId) {
        return stepEvents.stream().filter(event ->
                event.command().taskId().equals(taskId)
                        && event.command().planRevisionId().equals(
                        planRevisionId)).toList();
    }

    @Override
    public AppendResult<StepEvent> appendStepEvent(
            StepEventCommand command) {
        Optional<StepEvent> existing = stepEvents.stream().filter(event ->
                event.command().eventId().equals(command.eventId())).findFirst();
        if (existing.isPresent()) {
            if (!existing.get().command().equals(command)) {
                throw new IllegalStateException("conflicting Step replay");
            }
            return new AppendResult<>(existing.get(), true);
        }
        StepEvent event = new StepEvent(command, stepEvents.size() + 1L);
        stepEvents.add(event);
        return new AppendResult<>(event, false);
    }

    void addAuthority(String taskId, String eventId) {
        authorityEvents.add(new AuthorityEventRecord(
                eventId, taskId, authorityEvents.size() + 1L,
                "TEST", null, "0".repeat(64),
                Instant.parse("2026-08-07T04:00:00Z")));
    }

    @Override public Optional<TransitionRecord> findTransition(String id) {
        return transitions.stream().filter(value ->
                value.transitionId().equals(id)).findFirst();
    }
    @Override public List<TransitionStageRecord> findTransitionStages(String id) { return transitionStages.stream().filter(value -> value.transitionId().equals(id)).toList(); }
    @Override public List<TransitionRecord> findIncompleteTransitions(String taskId) { return List.of(); }
    @Override public List<RouteDecisionRecord> findRouteDecisions(String taskId) { return List.of(); }
    @Override public List<PlanBindingRecord> findPlanBindings(String taskId) { return planBindings.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<CandidateStepResultRecord> findCandidateStepResults(String taskId) { return candidates.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<ReviewDecisionRecord> findReviewDecisions(String taskId) { return reviews.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<AcceptedResultRecord> findAcceptedResults(String taskId) { return accepted.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<ResultApplicabilityRecord> findApplicabilityDecisions(String taskId) { return applicability.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<PendingItemRecord> findPendingItems(String taskId) { return openPending.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<PendingItemRecord> findOpenPendingItems(String taskId) { return findPendingItems(taskId); }
    @Override public List<PendingItemEventRecord> findPendingItemEvents(String gapId) { return List.of(); }
    @Override public List<PermissionDecisionRecord> findPermissionDecisions(String taskId) { return List.of(); }
    @Override public List<ActionBindingRecord> findActionBindings(String taskId) { return actions.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<ActionBindingRecord> findInFlightActions(String taskId) { return inFlightActions.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<WorkspaceCandidateRecord> findWorkspaceCandidates(String taskId) { return List.of(); }

    @Override public Optional<CommandRecord> findCommand(long userId, long sessionId, String clientRequestId) { return Optional.empty(); }
    @Override public Optional<CommandRecord> findCommand(String commandId) { return Optional.empty(); }
    @Override public Optional<TaskRecord> findTask(String taskId) { return Optional.empty(); }
    @Override public Optional<InstructionRecord> findInstruction(String instructionId) { return Optional.empty(); }
    @Override public List<TaskInstructionBindingRecord> findTaskInstructions(String taskId, long cut) { return List.of(); }
    @Override public List<AuthorityEventRecord> findAuthorityEvents(String taskId, long cut) { return authorityEvents.stream().filter(value -> value.taskId().equals(taskId) && value.eventSequence() <= cut).toList(); }
    @Override public long highestAuthorityEventSequence(String taskId) { return authorityEvents.stream().filter(value -> value.taskId().equals(taskId)).mapToLong(AuthorityEventRecord::eventSequence).max().orElse(0); }

    @Override public Optional<ModelInvocationRecord> findInvocation(String id) { return Optional.ofNullable(invocations.get(id)); }
    @Override public long highestInvocationOrdinal(String taskId) { return invocations.values().stream().filter(value -> value.taskId().equals(taskId)).mapToLong(ModelInvocationRecord::invocationOrdinal).max().orElse(0); }
    @Override public List<ModelInvocationRecord> findInvocations(String taskId, long cut) { return invocations.values().stream().filter(value -> value.taskId().equals(taskId) && value.invocationOrdinal() <= cut).toList(); }
    @Override public int highestProviderAttemptNo(String invocationId) { return 0; }
    @Override public List<ProviderAttemptRecord> findProviderAttempts(String id) { return List.of(); }
    @Override public List<ContentRecord> findContents(String id) { return contents.values().stream().filter(value -> value.invocationId().equals(id)).toList(); }
    @Override public Optional<ContentRecord> findContent(String id) { return Optional.ofNullable(contents.get(id)); }
    @Override public Optional<ModelProposalRecord> findProposal(String id) { return Optional.ofNullable(proposals.get(id)); }
    @Override public Optional<ModelProposalRecord> findProposalByInvocation(String id) { return proposals.values().stream().filter(value -> value.invocationId().equals(id)).findFirst(); }
    @Override public List<ProposalStateEventRecord> findProposalStateEvents(String id) { return proposalStates.getOrDefault(id, List.of()); }

    @Override public Optional<ContextRevisionRecord> findContextRevision(String id) { return Optional.ofNullable(contexts.get(id)); }
    @Override public List<ContextRevisionRecord> findContextRevisions(String taskId) { return contexts.values().stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<ContextModuleRecord> findContextModules(String id) { return List.of(); }

    @Override
    public AuthoritativeAppendResult<CandidateStepResultRecord>
            appendCandidateStepResult(
                    AuthoritativeFact<CandidateStepResultRecord> value) {
        Optional<CandidateStepResultRecord> existing = candidates.stream()
                .filter(item -> item.candidateResultId().equals(
                        value.fact().candidateResultId())).findFirst();
        if (existing.isPresent()) {
            return result(value.event(), existing.get(), true);
        }
        candidates.add(value.fact());
        return result(value.event(), value.fact(), false);
    }

    @Override
    public AuthoritativeAppendResult<AcceptedResultRecord>
            appendAcceptedResult(
                    AuthoritativeFact<AcceptedResultRecord> value) {
        Optional<AcceptedResultRecord> existing = accepted.stream()
                .filter(item -> item.acceptedResultId().equals(
                        value.fact().acceptedResultId())).findFirst();
        if (existing.isPresent()) {
            return result(value.event(), existing.get(), true);
        }
        accepted.add(value.fact());
        return result(value.event(), value.fact(), false);
    }

    @Override
    public ProposalStateEventRecord bindCandidate(
            ChainCandidateProposalBinder.Binding binding) {
        bindingAttempts++;
        if (failBindingOnce) {
            failBindingOnce = false;
            throw new IllegalStateException("simulated binding failure");
        }
        List<ProposalStateEventRecord> states = new ArrayList<>(
                proposalStates.getOrDefault(
                        binding.proposalId(), List.of()));
        if (states.size() == 2) {
            return states.get(1);
        }
        ProposalStateEventRecord state = new ProposalStateEventRecord(
                binding.proposalId(), 2, binding.taskId(), binding.eventId(),
                io.paperagent.v2.chain.ChainProposalState
                        .REPLACED_BY_OFFICIAL_RESULT,
                "CANDIDATE_STEP_RESULT", binding.candidateResultId(),
                binding.committedAt());
        states.add(state);
        proposalStates.put(binding.proposalId(), states);
        return state;
    }

    @Override
    public ProposalStateEventRecord bindAction(
            ChainActionProposalBinder.Binding binding) {
        actionBindingAttempts++;
        if (failActionBindingOnce) {
            failActionBindingOnce = false;
            throw new IllegalStateException("simulated action binding failure");
        }
        List<ProposalStateEventRecord> states = new ArrayList<>(
                proposalStates.getOrDefault(
                        binding.proposalId(), List.of()));
        if (states.size() == 2) {
            ProposalStateEventRecord existing = states.get(1);
            if (!existing.eventId().equals(binding.eventId())
                    || !existing.officialAuthorityRef().equals(
                    binding.actionId())
                    || !existing.committedAt().equals(
                    binding.committedAt())) {
                throw new IllegalStateException(
                        "conflicting action proposal replay");
            }
            return states.get(1);
        }
        ProposalStateEventRecord state = new ProposalStateEventRecord(
                binding.proposalId(), 2, binding.taskId(), binding.eventId(),
                io.paperagent.v2.chain.ChainProposalState
                        .REPLACED_BY_OFFICIAL_RESULT,
                "ACTION_BINDING", binding.actionId(), binding.committedAt());
        states.add(state);
        proposalStates.put(binding.proposalId(), states);
        return state;
    }

    @Override
    public AuthoritativeAppendResult<ActionBindingRecord> appendActionBinding(
            AuthoritativeFact<ActionBindingRecord> value) {
        Optional<ActionBindingRecord> existing = actions.stream()
                .filter(item -> item.actionId().equals(
                        value.fact().actionId())).findFirst();
        if (existing.isPresent()) {
            return result(value.event(), existing.get(), true);
        }
        ActionBindingRecord stored = authoritativeActionTime == null
                ? value.fact()
                : withCreatedAt(value.fact(), authoritativeActionTime);
        actions.add(stored);
        return result(value.event(), stored, false);
    }

    private static ActionBindingRecord withCreatedAt(
            ActionBindingRecord fact, Instant createdAt) {
        return new ActionBindingRecord(
                fact.actionId(), fact.taskId(), fact.eventId(), fact.proposalId(),
                fact.attemptNo(), fact.actionSignatureSha256(),
                fact.idempotencyKey(), fact.instructionId(), fact.taskFrameId(),
                fact.planId(), fact.planRevisionId(), fact.stepId(),
                fact.activationEventId(), fact.workspaceId(),
                fact.baseCandidateKey(), fact.effectIntentId(), fact.dispatchRef(),
                fact.resultAuthorityType(), fact.resultAuthorityRef(),
                fact.versionFenceSha256(), createdAt);
    }

    @Override
    public void requireCurrent(ChainStepCommitGate.GateQuery query) {
        if (gateBlocked) {
            throw new ChainStepException(
                    "CHAIN_TEST_GATE_BLOCKED", "formal gate blocked commit");
        }
    }

    @Override
    public ChainReadinessAuthorityPort.VerifiedReadinessMaterial verify(
            ChainReadinessAuthorityPort.ReadinessQuery query) {
        if (readinessMaterial == null) {
            throw new ChainStepException(
                    "CHAIN_TEST_READINESS_UNVERIFIED",
                    "readiness authority is absent");
        }
        return readinessMaterial;
    }

    @Override
    public AuthoritativeAppendResult<FinalizationReadinessRecord>
            appendReadiness(
                    AuthoritativeFact<FinalizationReadinessRecord> value) {
        Optional<FinalizationReadinessRecord> existing = readinessFacts
                .stream().filter(item -> item.readinessId().equals(
                        value.fact().readinessId())).findFirst();
        if (existing.isPresent()) {
            return result(value.event(), existing.get(), true);
        }
        FinalizationReadinessRecord stored = authoritativeActionTime == null
                ? value.fact() : withCreatedAt(
                value.fact(), authoritativeActionTime);
        readinessFacts.add(stored);
        return result(value.event(), stored, false);
    }

    private static FinalizationReadinessRecord withCreatedAt(
            FinalizationReadinessRecord value, Instant createdAt) {
        return new FinalizationReadinessRecord(
                value.readinessId(), value.taskId(), value.eventId(),
                value.transitionId(), value.readinessScopeKey(),
                value.taskFrameId(), value.finalPlanId(),
                value.finalPlanRevisionId(), value.finalPlanRevisionNumber(),
                value.finalStepId(), value.reviewDecisionId(),
                value.acceptedSet(),
                value.applicabilityCutEventSequence(), value.artifactId(),
                value.candidateKey(), value.workspaceId(),
                value.validationId(), value.validationRequestDigest(),
                value.validationReceiptDigest(), value.coverage(),
                value.publishRequirement(),
                value.publishRequirementDigest(), value.instructionId(),
                value.projectVersion(), createdAt);
    }

    @Override public Optional<FinalizationReadinessRecord> findReadinessById(String id) { return readinessFacts.stream().filter(value -> value.readinessId().equals(id)).findFirst(); }
    @Override public Optional<FinalizationReadinessRecord> findReadinessByScope(String scope) { return readinessFacts.stream().filter(value -> value.readinessScopeKey().equals(scope)).findFirst(); }
    @Override public List<FinalizationReadinessRecord> findReadiness(String taskId) { return readinessFacts.stream().filter(value -> value.taskId().equals(taskId)).toList(); }
    @Override public List<FinalizationCheckRecord> findFinalizationChecks(String id) { return List.of(); }
    @Override public Optional<TaskOutcomeRecord> findTaskOutcome(String taskId) { return Optional.empty(); }
    @Override public List<DeliveryRecord> findDeliveries(String taskId) { return List.of(); }
    @Override public List<DeliveryRecord> findIncompleteDeliveries(String taskId) { return List.of(); }
    @Override public List<DeliveryEventRecord> findDeliveryEvents(String id) { return List.of(); }

    @Override
    public ChainProgressAuthorityPort.ProgressSnapshot readProgress(
            String taskId, String stepId, String activationEventId) {
        return progressSnapshot;
    }

    private <T extends TaskAuthorityFact> AuthoritativeAppendResult<T> result(
            AuthorityEventRequest event, T fact, boolean replayed) {
        AuthorityEventRecord stored = authorityEvents.stream().filter(value ->
                value.eventId().equals(event.eventId())).findFirst().orElseGet(() -> {
            AuthorityEventRecord created = new AuthorityEventRecord(
                    event.eventId(), event.taskId(),
                    authorityEvents.size() + 1L, event.eventType(),
                    event.transitionId(), event.sourceIdentitySha256(),
                    authoritativeActionTime == null
                            ? event.committedAt() : authoritativeActionTime);
            authorityEvents.add(created);
            return created;
        });
        return new AuthoritativeAppendResult<>(stored, fact, replayed);
    }
}
