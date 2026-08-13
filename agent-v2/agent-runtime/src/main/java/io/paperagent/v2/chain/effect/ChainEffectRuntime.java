package io.paperagent.v2.chain.effect;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainWorkspaceCandidateWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes only formally bound actions and routes every file mutation through
 * one Workspace/Candidate authority. External reconciliation always precedes
 * dispatch; late results remain receipts and cannot advance the chain.
 */
public final class ChainEffectRuntime {
    private final ChainWorkflowRepository workflow;
    private final ChainModelRepository models;
    private final EffectAuthority effects;
    private final WorkspaceChangeSource workspaceChanges;
    private final WorkspaceCandidateAuthority candidates;
    private final ChainWorkspaceCandidateWriter candidateWriter;
    private final CurrentAuthorityGate currentGate;

    public ChainEffectRuntime(
            ChainWorkflowRepository workflow,
            ChainModelRepository models,
            EffectAuthority effects,
            WorkspaceChangeSource workspaceChanges,
            WorkspaceCandidateAuthority candidates,
            ChainWorkspaceCandidateWriter candidateWriter,
            CurrentAuthorityGate currentGate) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.models = Objects.requireNonNull(models, "models");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.workspaceChanges = Objects.requireNonNull(workspaceChanges, "workspaceChanges");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.candidateWriter = Objects.requireNonNull(candidateWriter, "candidateWriter");
        this.currentGate = Objects.requireNonNull(currentGate, "currentGate");
    }

    public ExecutionOutcome executeTool(ToolActionRequest request) {
        Objects.requireNonNull(request, "request");
        FrozenMutation action = formalAction(
                request.taskId(), request.actionId(), SourceKind.TOOL_ACTION);
        return executeTool(action, request.observedAt());
    }

    private ExecutionOutcome executeTool(FrozenMutation action, Instant observedAt) {
        EffectReconciliation reconciliation = effects.reconcile(action);
        validateReconciliation(action, reconciliation);
        if (reconciliation.status() != EffectStatus.NOT_DISPATCHED) {
            return reconciled(action, reconciliation, observedAt);
        }
        GateStatus gate = currentGate.classify(action);
        if (gate != GateStatus.CURRENT) {
            return new ExecutionOutcome(
                    OutcomeKind.LATE_RESULT_RETAINED, action,
                    null, null, null, null, gate);
        }
        PreparedEffect prepared = effects.prepare(action);
        require(prepared.actionId().equals(action.actionId())
                        && prepared.idempotencyKey().equals(action.idempotencyKey())
                        && prepared.versionFenceSha256().equals(action.versionFenceSha256()),
                "EffectIntent does not bind the formal action identity");
        // Preparing the durable EffectIntent is the in-flight ordering point.
        // A later cancel may retain the result, but cannot turn that intent
        // back into NOT_DISPATCHED or strand a consumed one-time permit.
        EffectReconciliation dispatched = effects.dispatch(prepared);
        validateReconciliation(action, dispatched);
        require(dispatched.status() != EffectStatus.NOT_DISPATCHED,
                "dispatch returned NOT_DISPATCHED");
        return reconciled(action, dispatched, observedAt);
    }

    public ExecutionOutcome applyWorkspaceChange(WorkspaceChangeRequest request) {
        Objects.requireNonNull(request, "request");
        FrozenMutation mutation = formalAction(
                request.taskId(), request.actionId(), SourceKind.WORKSPACE_CHANGE);
        WorkspaceChangeBinding binding = workspaceChanges.loadAccepted(
                request.taskId(), request.actionId());
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(mutation.proposalId())
                .orElseThrow(() -> new IllegalStateException(
                        "formal workspace-change proposal does not exist"));
        require(binding.taskId().equals(request.taskId())
                        && binding.actionId().equals(request.actionId())
                        && binding.proposalId().equals(mutation.proposalId())
                        && "WORKSPACE_CHANGE_BODY".equals(
                        proposal.bodyAuthorityType())
                        && binding.changeBodyRef().equals(
                        proposal.bodyAuthorityRef()),
                "workspace-change source returned another formal action");
        return materialize(
                new CandidateMutation(
                        mutation, "WORKSPACE_CHANGE_BODY", binding.changeBodyRef()),
                request.observedAt(), null);
    }

    /** Resumes one formal action through its proposal-defined authority path. */
    public ExecutionOutcome recoverBoundAction(ActionRecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        FrozenMutation action = formalAction(
                request.taskId(), request.actionId(), null);
        return switch (action.sourceKind()) {
            case TOOL_ACTION -> executeTool(action, request.observedAt());
            case WORKSPACE_CHANGE -> applyWorkspaceChange(
                    new WorkspaceChangeRequest(
                            request.taskId(), request.actionId(), request.observedAt()));
        };
    }

    /** Read-only reconciliation entry used by restart recovery; it never dispatches. */
    public ExecutionOutcome reconcileOnly(String taskId, String actionId, Instant observedAt) {
        FrozenMutation action = formalAction(taskId, actionId, SourceKind.TOOL_ACTION);
        EffectReconciliation reconciliation = effects.reconcile(action);
        validateReconciliation(action, reconciliation);
        return reconciled(action, reconciliation, observedAt);
    }

    private ExecutionOutcome reconciled(
            FrozenMutation action,
            EffectReconciliation reconciliation,
            Instant observedAt) {
        return switch (reconciliation.status()) {
            case NOT_DISPATCHED -> new ExecutionOutcome(
                    OutcomeKind.NOT_DISPATCHED, action, null, null, null, null,
                    currentGate.classify(action));
            case IN_FLIGHT -> new ExecutionOutcome(
                    OutcomeKind.WAITING_EFFECT, action, reconciliation.receiptRef(),
                    reconciliation.errorRef(), reconciliation.uncertaintyRef(),
                    null, currentGate.classify(action));
            case UNKNOWN -> new ExecutionOutcome(
                    OutcomeKind.UNKNOWN_SIDE_EFFECT, action,
                    reconciliation.receiptRef(), reconciliation.errorRef(),
                    reconciliation.uncertaintyRef(), null,
                    currentGate.classify(action));
            case FAILED -> new ExecutionOutcome(
                    OutcomeKind.EFFECT_FAILED, action, reconciliation.receiptRef(),
                    reconciliation.errorRef(), reconciliation.uncertaintyRef(),
                    null, currentGate.classify(action));
            case SUCCEEDED -> {
                GateStatus gate = currentGate.classify(action);
                if (gate != GateStatus.CURRENT) {
                    yield new ExecutionOutcome(
                            OutcomeKind.LATE_RESULT_RETAINED, action,
                            reconciliation.receiptRef(), null, null, null, gate);
                }
                if (reconciliation.workspaceMutation() == null) {
                    yield new ExecutionOutcome(
                            OutcomeKind.EFFECT_SUCCEEDED, action,
                            reconciliation.receiptRef(), null, null, null, gate);
                }
                yield materialize(
                        new CandidateMutation(
                                action, "TOOL_EFFECT_RESULT",
                                reconciliation.workspaceMutation().mutationAuthorityRef()),
                        observedAt, reconciliation.receiptRef());
            }
        };
    }

    private ExecutionOutcome materialize(
            CandidateMutation candidateMutation, Instant observedAt, String receiptRef) {
        FrozenMutation mutation = candidateMutation.mutation();
        Optional<ChainPersistenceRecords.WorkspaceCandidateRecord> existingBinding =
                existingCandidate(mutation);
        if (existingBinding.isPresent()) {
            MaterializedCandidate reconciled = candidates.reconcile(candidateMutation)
                    .orElseThrow(() -> new IllegalStateException(
                            "Workspace authority cannot reconcile an existing Candidate binding"));
            validateCandidate(mutation, reconciled);
            require(reconciled.disposition() == CandidateDisposition.COMMITTED,
                    "an existing Candidate binding cannot reconcile as late");
            validateExistingCandidate(mutation, existingBinding.get(), reconciled);
            GateStatus gate = currentGate.classify(mutation);
            return new ExecutionOutcome(
                    gate == GateStatus.CURRENT
                            ? OutcomeKind.CANDIDATE_COMMITTED
                            : OutcomeKind.LATE_RESULT_RETAINED,
                    mutation, receiptRef, null, null, existingBinding.get(), gate);
        }
        GateStatus gate = currentGate.classify(mutation);
        if (gate != GateStatus.CURRENT) {
            return new ExecutionOutcome(
                    OutcomeKind.LATE_RESULT_RETAINED, mutation,
                    receiptRef, null, null, null, gate);
        }
        Optional<MaterializedCandidate> reconciled = candidates.reconcile(candidateMutation);
        AtomicInteger bindingCalls = new AtomicInteger();
        AtomicReference<ChainPersistenceRecords.WorkspaceCandidateRecord> bound =
                new AtomicReference<>();
        MaterializedCandidate materialized = reconciled.orElseGet(() ->
                candidates.materialize(candidateMutation, candidate -> {
                    require(bindingCalls.incrementAndGet() == 1,
                            "Candidate binding callback may be used exactly once");
                    validateCandidate(mutation, candidate);
                    require(candidate.disposition() == CandidateDisposition.COMMITTED,
                            "a late Candidate cannot create a chain binding");
                    require(currentGate.classify(mutation) == GateStatus.CURRENT,
                            "workspace authority changed before Candidate binding");
                    ChainPersistenceRecords.WorkspaceCandidateRecord fact =
                            appendCandidateBinding(mutation, candidate, observedAt);
                    bound.set(fact);
                    return fact;
                }));
        validateCandidate(mutation, materialized);
        if (materialized.disposition() == CandidateDisposition.FAILED) {
            require(bindingCalls.get() == 0 && bound.get() == null,
                    "a failed Candidate created a chain binding");
            return new ExecutionOutcome(
                    OutcomeKind.EFFECT_FAILED, mutation, receiptRef,
                    materialized.errorRef(), null, null,
                    currentGate.classify(mutation));
        }
        if (materialized.disposition() == CandidateDisposition.LATE_RETAINED) {
            require(bindingCalls.get() == 0 && bound.get() == null,
                    "a late Candidate created a chain binding");
            return new ExecutionOutcome(
                    OutcomeKind.LATE_RESULT_RETAINED, mutation,
                    receiptRef, null, null, null,
                    currentGate.classify(mutation));
        }
        GateStatus completedGate = currentGate.classify(mutation);
        ChainPersistenceRecords.WorkspaceCandidateRecord fact;
        if (reconciled.isPresent()) {
            require(bindingCalls.get() == 0,
                    "reconciliation cannot use the materialization callback");
            if (completedGate != GateStatus.CURRENT) {
                return new ExecutionOutcome(
                        OutcomeKind.LATE_RESULT_RETAINED, mutation, receiptRef,
                        null, null, null, completedGate);
            }
            fact = appendCandidateBinding(mutation, materialized, observedAt);
        } else {
            require(bindingCalls.get() == 1 && bound.get() != null,
                    "Candidate materialization must atomically create its chain binding");
            fact = bound.get();
            validateExistingCandidate(mutation, fact, materialized);
        }
        if (completedGate != GateStatus.CURRENT) {
            return new ExecutionOutcome(
                    OutcomeKind.LATE_RESULT_RETAINED, mutation, receiptRef,
                    null, null, fact, completedGate);
        }
        return new ExecutionOutcome(
                OutcomeKind.CANDIDATE_COMMITTED, mutation, receiptRef, null, null,
                fact, GateStatus.CURRENT);
    }

    private ChainPersistenceRecords.WorkspaceCandidateRecord appendCandidateBinding(
            FrozenMutation mutation,
            MaterializedCandidate materialized,
            Instant observedAt) {
        String candidateId = "workspace-candidate." + sha256(
                mutation.actionId() + "\0" + mutation.workspaceId() + "\0"
                        + materialized.artifactId() + "\0"
                        + materialized.candidateFingerprint() + "\0"
                        + materialized.diffDigest());
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> existing = workflow
                .findWorkspaceCandidates(mutation.taskId()).stream()
                .filter(value -> value.actionId().equals(mutation.actionId())).toList();
        require(existing.size() <= 1,
                "one action has multiple Workspace/Candidate bindings");
        ChainPersistenceRecords.WorkspaceCandidateRecord fact;
        if (existing.size() == 1) {
            fact = existing.get(0);
            require(fact.workspaceCandidateId().equals(candidateId)
                            && fact.taskId().equals(mutation.taskId())
                            && fact.workspaceId().equals(mutation.workspaceId())
                            && fact.baseProjectVersion().equals(
                            materialized.baseProjectVersion())
                            && fact.artifactId() == materialized.artifactId()
                            && fact.candidateFingerprint().equals(
                            materialized.candidateFingerprint())
                            && fact.diffDigest().equals(materialized.diffDigest())
                            && fact.versionFenceSha256().equals(
                            mutation.versionFenceSha256()),
                    "existing Workspace/Candidate binding changed immutable identity");
        } else {
            String eventId = "workspace-candidate.binding." + sha256(candidateId);
            fact = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                    candidateId, mutation.taskId(), eventId, mutation.actionId(),
                    mutation.workspaceId(), materialized.baseProjectVersion(),
                    materialized.artifactId(), materialized.candidateFingerprint(),
                    materialized.diffDigest(), mutation.versionFenceSha256(), observedAt);
        }
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        fact.eventId(), mutation.taskId(), "WORKSPACE_CANDIDATE", null,
                        mutation.versionFenceSha256(), fact.createdAt());
        ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.WorkspaceCandidateRecord> appended =
                candidateWriter.appendWorkspaceCandidate(
                        new ChainPersistenceRecords.AuthoritativeFact<>(event, fact));
        require(sameAuthorityEvent(event, appended.event())
                        && sameWorkspaceCandidate(fact, appended.fact()),
                "Workspace/Candidate replay changed immutable contents");
        return appended.fact();
    }

    private static boolean sameAuthorityEvent(
            ChainPersistenceRecords.AuthorityEventRequest expected,
            ChainPersistenceRecords.AuthorityEventRecord actual) {
        return actual.eventId().equals(expected.eventId())
                && actual.taskId().equals(expected.taskId())
                && actual.eventType().equals(expected.eventType())
                && Objects.equals(actual.transitionId(), expected.transitionId())
                && actual.sourceIdentitySha256().equals(
                expected.sourceIdentitySha256());
    }

    /** Product persistence owns audit time; every immutable business field stays exact. */
    private static boolean sameWorkspaceCandidate(
            ChainPersistenceRecords.WorkspaceCandidateRecord expected,
            ChainPersistenceRecords.WorkspaceCandidateRecord actual) {
        return actual.workspaceCandidateId().equals(expected.workspaceCandidateId())
                && actual.taskId().equals(expected.taskId())
                && actual.eventId().equals(expected.eventId())
                && actual.actionId().equals(expected.actionId())
                && actual.workspaceId().equals(expected.workspaceId())
                && actual.baseProjectVersion().equals(
                expected.baseProjectVersion())
                && actual.artifactId() == expected.artifactId()
                && actual.candidateFingerprint().equals(
                expected.candidateFingerprint())
                && actual.diffDigest().equals(expected.diffDigest())
                && actual.versionFenceSha256().equals(
                expected.versionFenceSha256());
    }

    private Optional<ChainPersistenceRecords.WorkspaceCandidateRecord> existingCandidate(
            FrozenMutation mutation) {
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> matches = workflow
                .findWorkspaceCandidates(mutation.taskId()).stream()
                .filter(value -> value.actionId().equals(mutation.actionId())).toList();
        require(matches.size() <= 1,
                "one action has multiple Workspace/Candidate bindings");
        if (matches.isEmpty()) return Optional.empty();
        ChainPersistenceRecords.WorkspaceCandidateRecord existing = matches.get(0);
        String expectedCandidateId = "workspace-candidate." + sha256(
                mutation.actionId() + "\0" + mutation.workspaceId() + "\0"
                        + existing.artifactId() + "\0"
                        + existing.candidateFingerprint() + "\0"
                        + existing.diffDigest());
        String expectedEventId = "workspace-candidate.binding." + sha256(
                expectedCandidateId);
        require(existing.actionId().equals(mutation.actionId())
                        && existing.workspaceCandidateId().equals(expectedCandidateId)
                        && existing.eventId().equals(expectedEventId)
                        && existing.taskId().equals(mutation.taskId())
                        && existing.workspaceId().equals(mutation.workspaceId())
                        && existing.versionFenceSha256().equals(
                        mutation.versionFenceSha256()),
                "existing Workspace/Candidate binding changed frozen action identity");
        return Optional.of(existing);
    }

    private static void validateExistingCandidate(
            FrozenMutation mutation,
            ChainPersistenceRecords.WorkspaceCandidateRecord existing,
            MaterializedCandidate reconciled) {
        require(existing.actionId().equals(mutation.actionId())
                        && existing.workspaceId().equals(reconciled.workspaceId())
                        && existing.baseProjectVersion().equals(
                        reconciled.baseProjectVersion())
                        && existing.artifactId() == reconciled.artifactId()
                        && existing.candidateFingerprint().equals(
                        reconciled.candidateFingerprint())
                        && existing.diffDigest().equals(reconciled.diffDigest())
                        && existing.versionFenceSha256().equals(
                        reconciled.versionFenceSha256()),
                "existing Candidate disagrees with Workspace authority");
    }

    private FrozenMutation formalAction(
            String taskId, String actionId, SourceKind expectedKind) {
        required(taskId, "taskId");
        required(actionId, "actionId");
        List<ChainPersistenceRecords.ActionBindingRecord> matches = workflow
                .findActionBindings(taskId).stream()
                .filter(value -> value.actionId().equals(actionId)).toList();
        require(matches.size() == 1,
                "effect execution requires exactly one formal action binding");
        ChainPersistenceRecords.ActionBindingRecord action = matches.get(0);
        require(action.taskId().equals(taskId), "action binding task mismatch");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(action.proposalId())
                .orElseThrow(() -> new IllegalStateException(
                        "formal action proposal does not exist"));
        require(proposal.taskId().equals(taskId)
                        && proposal.proposalId().equals(action.proposalId()),
                "formal action proposal identity mismatch");
        SourceKind actualKind = switch (proposal.proposalKind()) {
            case EXECUTOR_TOOL_ACTION -> SourceKind.TOOL_ACTION;
            case EXECUTOR_WORKSPACE_CHANGE -> SourceKind.WORKSPACE_CHANGE;
            default -> throw new IllegalStateException(
                    "formal action proposal has no effect authority path");
        };
        require(expectedKind == null || actualKind == expectedKind,
                "formal action was routed through the wrong effect authority");
        return FrozenMutation.from(action, actualKind);
    }

    private static void validateReconciliation(
            FrozenMutation action, EffectReconciliation reconciliation) {
        Objects.requireNonNull(reconciliation, "reconciliation");
        require(action.actionId().equals(reconciliation.actionId())
                        && action.idempotencyKey().equals(reconciliation.idempotencyKey()),
                "effect reconciliation returned another action identity");
    }

    private static void validateCandidate(
            FrozenMutation mutation, MaterializedCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        require(candidate.actionId().equals(mutation.actionId())
                        && candidate.workspaceId().equals(mutation.workspaceId())
                        && candidate.baseCandidateKey().equals(
                        mutation.baseCandidateKey())
                        && candidate.versionFenceSha256().equals(
                        mutation.versionFenceSha256()),
                "Workspace/Candidate authority returned another frozen identity");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record ToolActionRequest(String taskId, String actionId, Instant observedAt) {
        public ToolActionRequest {
            required(taskId, "taskId");
            required(actionId, "actionId");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record WorkspaceChangeRequest(
            String taskId, String actionId, Instant observedAt) {
        public WorkspaceChangeRequest {
            required(taskId, "taskId");
            required(actionId, "actionId");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record ActionRecoveryRequest(
            String taskId, String actionId, Instant observedAt) {
        public ActionRecoveryRequest {
            required(taskId, "taskId");
            required(actionId, "actionId");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    public record FrozenMutation(
            SourceKind sourceKind,
            String taskId,
            String actionId,
            String idempotencyKey,
            String proposalId,
            String instructionId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            String stepId,
            String activationEventId,
            String workspaceId,
            String baseCandidateKey,
            String actionSignatureSha256,
            String versionFenceSha256) {
        public FrozenMutation {
            Objects.requireNonNull(sourceKind, "sourceKind");
            required(taskId, "taskId");
            required(actionId, "actionId");
            required(idempotencyKey, "idempotencyKey");
            required(proposalId, "proposalId");
            required(instructionId, "instructionId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            required(workspaceId, "workspaceId");
            required(baseCandidateKey, "baseCandidateKey");
            required(actionSignatureSha256, "actionSignatureSha256");
            required(versionFenceSha256, "versionFenceSha256");
        }

        static FrozenMutation from(
                ChainPersistenceRecords.ActionBindingRecord action,
                SourceKind sourceKind) {
            return new FrozenMutation(
                    sourceKind, action.taskId(), action.actionId(),
                    action.idempotencyKey(), action.proposalId(), action.instructionId(),
                    action.taskFrameId(), action.planId(), action.planRevisionId(),
                    action.stepId(), action.activationEventId(), action.workspaceId(),
                    action.baseCandidateKey(), action.actionSignatureSha256(),
                    action.versionFenceSha256());
        }
    }

    public record WorkspaceChangeBinding(
            String taskId,
            String proposalId,
            String actionId,
            String changeBodyRef) {
        public WorkspaceChangeBinding {
            required(taskId, "taskId");
            required(proposalId, "proposalId");
            required(actionId, "actionId");
            required(changeBodyRef, "changeBodyRef");
        }
    }

    public record PreparedEffect(
            String effectIntentId,
            String actionId,
            String idempotencyKey,
            String versionFenceSha256,
            String dispatchPermit) {
        public PreparedEffect {
            required(effectIntentId, "effectIntentId");
            required(actionId, "actionId");
            required(idempotencyKey, "idempotencyKey");
            required(versionFenceSha256, "versionFenceSha256");
            required(dispatchPermit, "dispatchPermit");
        }
    }

    public record EffectReconciliation(
            String actionId,
            String idempotencyKey,
            EffectStatus status,
            String receiptRef,
            String errorRef,
            String uncertaintyRef,
            WorkspaceMutation workspaceMutation) {
        public EffectReconciliation {
            required(actionId, "actionId");
            required(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(status, "status");
            switch (status) {
                case NOT_DISPATCHED -> requireShape(
                        receiptRef == null && errorRef == null
                                && uncertaintyRef == null
                                && workspaceMutation == null,
                        "NOT_DISPATCHED cannot carry an effect result");
                case IN_FLIGHT -> requireShape(
                        receiptRef != null && errorRef == null
                                && uncertaintyRef == null
                                && workspaceMutation == null,
                        "IN_FLIGHT requires only its dispatch receipt");
                case UNKNOWN -> requireShape(
                        uncertaintyRef != null && receiptRef == null
                                && errorRef == null
                                && workspaceMutation == null,
                        "UNKNOWN requires its uncertainty authority and no result");
                case SUCCEEDED -> requireShape(
                        receiptRef != null && errorRef == null
                                && uncertaintyRef == null,
                        "SUCCEEDED requires a receipt and cannot carry an error");
                case FAILED -> requireShape(
                        errorRef != null && uncertaintyRef == null
                                && workspaceMutation == null,
                        "FAILED requires an error and no Workspace result");
            }
        }

        private static void requireShape(boolean condition, String message) {
            if (!condition) throw new IllegalArgumentException(message);
        }
    }

    public record WorkspaceMutation(String mutationAuthorityRef) {
        public WorkspaceMutation {
            required(mutationAuthorityRef, "mutationAuthorityRef");
        }
    }

    public record CandidateMutation(
            FrozenMutation mutation,
            String mutationAuthorityType,
            String mutationAuthorityRef) {
        public CandidateMutation {
            Objects.requireNonNull(mutation, "mutation");
            required(mutationAuthorityType, "mutationAuthorityType");
            required(mutationAuthorityRef, "mutationAuthorityRef");
        }
    }

    public record MaterializedCandidate(
            CandidateDisposition disposition,
            String actionId,
            String workspaceId,
            String baseCandidateKey,
            String baseProjectVersion,
            long artifactId,
            String candidateFingerprint,
            String diffDigest,
            String versionFenceSha256,
            String errorRef,
            String errorCode) {
        public MaterializedCandidate(
                CandidateDisposition disposition, String actionId,
                String workspaceId, String baseCandidateKey,
                String baseProjectVersion, long artifactId,
                String candidateFingerprint, String diffDigest,
                String versionFenceSha256) {
            this(disposition, actionId, workspaceId, baseCandidateKey,
                    baseProjectVersion, artifactId, candidateFingerprint,
                    diffDigest, versionFenceSha256, null, null);
        }
        public MaterializedCandidate {
            Objects.requireNonNull(disposition, "disposition");
            required(actionId, "actionId");
            required(workspaceId, "workspaceId");
            required(baseCandidateKey, "baseCandidateKey");
            required(versionFenceSha256, "versionFenceSha256");
            if (disposition == CandidateDisposition.COMMITTED) {
                required(baseProjectVersion, "baseProjectVersion");
                if (artifactId < 1) throw new IllegalArgumentException("artifactId must be positive");
                required(candidateFingerprint, "candidateFingerprint");
                required(diffDigest, "diffDigest");
            }
            if (disposition == CandidateDisposition.FAILED) {
                required(errorRef, "errorRef");
                required(errorCode, "errorCode");
                if (baseProjectVersion != null || artifactId != 0
                        || candidateFingerprint != null || diffDigest != null) {
                    throw new IllegalArgumentException(
                            "FAILED Candidate cannot carry Candidate identity");
                }
            } else if (errorRef != null || errorCode != null) {
                throw new IllegalArgumentException(
                        "non-failed Candidate cannot carry failure identity");
            }
        }
    }

    public record ExecutionOutcome(
            OutcomeKind kind,
            FrozenMutation action,
            String receiptRef,
            String errorRef,
            String uncertaintyRef,
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate,
            GateStatus gateStatus) {
        public ExecutionOutcome {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(gateStatus, "gateStatus");
        }
    }

    public enum SourceKind { TOOL_ACTION, WORKSPACE_CHANGE }

    public enum EffectStatus { NOT_DISPATCHED, IN_FLIGHT, UNKNOWN, SUCCEEDED, FAILED }

    public enum CandidateDisposition { COMMITTED, LATE_RETAINED, FAILED }

    public enum GateStatus { CURRENT, PAUSED, CANCELLED, SUPERSEDED, STALE_VERSION }

    public enum OutcomeKind {
        NOT_DISPATCHED,
        WAITING_EFFECT,
        UNKNOWN_SIDE_EFFECT,
        EFFECT_SUCCEEDED,
        EFFECT_FAILED,
        CANDIDATE_COMMITTED,
        LATE_RESULT_RETAINED
    }

    public interface EffectAuthority {
        EffectReconciliation reconcile(FrozenMutation action);

        PreparedEffect prepare(FrozenMutation action);

        EffectReconciliation dispatch(PreparedEffect prepared);
    }

    public interface WorkspaceChangeSource {
        WorkspaceChangeBinding loadAccepted(String taskId, String actionId);
    }

    /** Shared authority for direct WORKSPACE_CHANGE and TOOL_ACTION file output. */
    public interface WorkspaceCandidateAuthority {
        Optional<MaterializedCandidate> reconcile(CandidateMutation mutation);

        MaterializedCandidate materialize(
                CandidateMutation mutation, CandidateBindingPort binding);
    }

    @FunctionalInterface
    public interface CandidateBindingPort {
        ChainPersistenceRecords.WorkspaceCandidateRecord bind(
                MaterializedCandidate candidate);
    }

    public interface CurrentAuthorityGate {
        GateStatus classify(FrozenMutation mutation);
    }
}
