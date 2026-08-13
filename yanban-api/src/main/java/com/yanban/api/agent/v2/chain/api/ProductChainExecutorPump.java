package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.effect.ProductChainCurrentAuthorityGate;
import com.yanban.api.agent.v2.chain.effect.ProductChainEffectAuthority;
import com.yanban.api.agent.v2.chain.effect.ProductChainWorkspaceCandidateAuthority;
import com.yanban.api.agent.v2.chain.effect.ProductChainWorkspaceChangeSource;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.effect.ChainEffectRuntime;
import io.paperagent.v2.chain.step.ChainActionProposalBinder;
import io.paperagent.v2.chain.step.ChainActionRuntime;
import io.paperagent.v2.chain.step.ChainStepResultRuntime;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Runs one bounded Executor proposal through the chain authorities.
 *
 * <p>This pump deliberately stops at one TOOL_ACTION. It invokes the already
 * frozen Context through {@link ChainModelProtocolService}, admits only the
 * validated proposal, binds one formal Action, and then delegates dispatch to
 * {@link ChainEffectRuntime}. It is not a replacement for the persistent
 * plan loop and it never reads or writes the legacy loop state.</p>
 */
@Component
public final class ProductChainExecutorPump {
    static final String CANDIDATE_REQUIRED_CODE =
            "CHAIN_EXECUTOR_STEP_RESULT_CANDIDATE_REQUIRED";

    private final ProposalAdmission admission;
    private final ActionCommitter actions;
    private final EffectExecutor effects;
    private final WorkspaceChangeExecutor workspaceChanges;
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainProposalAdmissionAdapter proposalAdmission;

    /** Product composition constructor. */
    @Autowired
    public ProductChainExecutorPump(
            ProductChainModelRepositoryAdapter models,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainCurrentAuthorityGate currentGate,
            ProductChainEffectAuthority effectAuthority,
            ProductChainWorkspaceChangeSource workspaceChanges,
            ProductChainWorkspaceCandidateAuthority candidates,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        ProductChainProposalAdmissionAdapter admissionAdapter =
                new ProductChainProposalAdmissionAdapter(
                        jdbc, transactions, models, models);
        ChainActionProposalBinder proposalBinder = binding -> admissionAdapter
                .replaceByOfficialResult(new ChainProposalAdmissionService.OfficialReplacement(
                        binding.proposalId(), binding.taskId(), binding.eventId(),
                        ChainPersistenceRecords.ProposalOfficialAuthorityType.ACTION_BINDING,
                        binding.actionId(), null, binding.sourceIdentitySha256(),
                        binding.committedAt())).state();
        ChainActionRuntime actionRuntime = new ChainActionRuntime(
                models, contexts, workflow, workflow, proposalBinder, currentGate);
        ChainEffectRuntime effectRuntime = new ChainEffectRuntime(
                workflow, models, effectAuthority, workspaceChanges,
                candidates, workflow, currentGate);
        this.admission = admissionAdapter::admit;
        this.actions = command -> actionRuntime.commit(command).fact();
        this.effects = effectRuntime::executeTool;
        this.workspaceChanges = effectRuntime::applyWorkspaceChange;
        this.contexts = contexts;
        this.models = models;
        this.workflow = workflow;
        this.proposalAdmission = admissionAdapter;
    }

    ProductChainExecutorPump(
            ProposalAdmission admission,
            ActionCommitter actions,
        EffectExecutor effects) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.contexts = null;
        this.models = null;
        this.workflow = null;
        this.proposalAdmission = null;
        this.workspaceChanges = request -> {
            throw new UnsupportedOperationException(
                    "workspace-change executor is not configured");
        };
    }

    /**
     * Invokes one complete frozen Executor context and executes its proposal.
     * A model failure is returned as a bounded outcome; no admission or action
     * binding is attempted in that case.
     */
    public Result execute(
            ChainModelProtocolService protocol,
            ChainModelProtocolRequest request) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(request, "request");
        return execute(request.taskId(), protocol.invoke(request), request.createdAt());
    }

    /**
     * Named hand-off for the Plan transition driver. The caller must supply a
     * COMPLETE Executor Context request; context construction remains owned by
     * the product context composition and is intentionally not duplicated here.
     */
    public Result runAfterPlan(
            ChainModelProtocolService protocol,
            ChainModelProtocolRequest executorRequest) {
        return execute(protocol, executorRequest);
    }

    /**
     * Commits the real Executor STEP_RESULT proposal produced after a
     * workspace candidate.  This method deliberately does not create a
     * result from the candidate alone: the proposal must be accepted by the
     * model protocol and its content must be bound by ChainStepResultRuntime.
     */
    public ChainPersistenceRecords.CandidateStepResultRecord commitStepResult(
            String taskId,
            ChainModelProtocolOutcome outcome,
            StepResultIdentity identity,
            Instant committedAt) {
        required(taskId, "taskId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(committedAt, "committedAt");
        if (!(outcome instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().proposalKind()
                != ChainProposalKind.EXECUTOR_STEP_RESULT) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_PROPOSAL_REQUIRED");
        }
        if (identity.candidateRequired() && identity.workspaceCandidateId() == null) {
            throw failure(CANDIDATE_REQUIRED_CODE);
        }
        if (contexts == null || models == null || workflow == null) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_PRODUCTION_ONLY");
        }
        ChainPersistenceRecords.ModelProposalRecord proposal = ready.proposal();
        if (!proposal.taskId().equals(taskId)
                || ready.bodyContent() == null
                || proposal.bodyAuthorityRef() == null
                || !proposal.bodyAuthorityRef().equals(ready.bodyContent().contentId())) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_CONTENT_MISMATCH");
        }
        if (identity.workspaceCandidateId() != null
                && !proposal.sourceRefs().json().contains(identity.workspaceCandidateId())) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_CANDIDATE_REF_MISSING");
        }
        Instant factTime = committedAt.truncatedTo(ChronoUnit.MICROS);
        ChainProposalAdmissionService.AdmissionResult admitted = proposalAdmission.admit(
                new ChainProposalAdmissionService.AdmissionRequest(
                        proposal.proposalId(), taskId,
                        identity("executor-step-result-accepted", proposal.proposalId()),
                        true, null, proposal.payload().sha256(), factTime));
        if (!admitted.executable()) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_NOT_EXECUTABLE");
        }
        String candidateResultId = "candidate-result." + sha256(
                proposal.proposalId() + "\0" + ready.bodyContent().contentId());
        ChainPersistenceRecords.CandidateStepResultRecord requested =
                new ChainPersistenceRecords.CandidateStepResultRecord(
                        candidateResultId, taskId,
                        "candidate-result.event." + sha256(candidateResultId),
                        proposal.proposalId(), ready.bodyContent().contentId(),
                        identity.instructionId(), identity.taskFrameId(), identity.planId(),
                        identity.planRevisionId(), identity.planRevisionNumber(), identity.stepId(),
                        identity.activationEventId(), identity.artifactId(),
                        identity.candidateFingerprint(), identity.diffDigest(),
                        stepResultReceiptRefs(ready),
                        identity.validationId(),
                        identity.validationRequestDigest(),
                        identity.validationReceiptDigest(), canonicalEmpty(),
                        identity.versionFenceSha256(), factTime);
        ChainStepResultRuntime runtime = new ChainStepResultRuntime(
                models, contexts, workflow, workflow, workflow,
                binding -> proposalAdmission.replaceByOfficialResult(
                        new ChainProposalAdmissionService.OfficialReplacement(
                                binding.proposalId(), binding.taskId(), binding.eventId(),
                                ChainPersistenceRecords.ProposalOfficialAuthorityType
                                        .CANDIDATE_STEP_RESULT,
                                binding.candidateResultId(), null,
                                binding.sourceIdentitySha256(), binding.committedAt())).state());
        return runtime.commitCandidate(requested).fact();
    }

    /**
     * Consumes one already accepted Executor proposal through the same formal
     * Action/Effect or StepResult owner used by the live model path.
     */
    public OfficialSuccessor consumeAccepted(
            String taskId,
            ChainModelProtocolOutcome.ProposalReady ready,
            StepResultIdentity stepResultIdentity,
            Instant committedAt) {
        required(taskId, "taskId");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(committedAt, "committedAt");
        return switch (ready.proposal().proposalKind()) {
            case EXECUTOR_TOOL_ACTION, EXECUTOR_WORKSPACE_CHANGE -> {
                Result result = execute(taskId, ready, committedAt);
                if (result.status() != Status.EFFECT_DISPATCHED
                        || result.actionId() == null) {
                    throw failure("CHAIN_EXECUTOR_ACTION_SUCCESSOR_MISSING");
                }
                yield new OfficialSuccessor("ACTION_BINDING", result.actionId());
            }
            case EXECUTOR_STEP_RESULT -> {
                if (stepResultIdentity == null) {
                    throw failure("CHAIN_EXECUTOR_STEP_RESULT_IDENTITY_MISSING");
                }
                var result = commitStepResult(
                        taskId, ready, stepResultIdentity, committedAt);
                yield new OfficialSuccessor(
                        "CANDIDATE_STEP_RESULT", result.candidateResultId());
            }
            case EXECUTOR_STEP_BLOCKED -> throw failure(
                    "CHAIN_EXECUTOR_STEP_BLOCKED_CONSUMER_MISSING");
            default -> throw failure(
                    "CHAIN_EXECUTOR_PROPOSAL_CONSUMER_MISSING");
        };
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalEmpty() {
        return new ChainPersistenceRecords.CanonicalJson(1, sha256("[]"), "[]");
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalRefs(
            String value) {
        if (value == null) return canonicalEmpty();
        String json = "[\"" + value + "\"]";
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
    }

    private static ChainPersistenceRecords.CanonicalJson stepResultReceiptRefs(
            ChainModelProtocolOutcome.ProposalReady ready) {
        ProviderRoleOutput parsed = ProductChainPersistedProposalDecoder.decode(
                ready, io.paperagent.v2.chain.ChainWorkState.EXECUTING, null);
        if (!(parsed.payload() instanceof ExecutorPayload.StepResult value)) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_PROPOSAL_REQUIRED");
        }
        String json = value.receiptRefs().stream().sorted()
                .map(ref -> "\"" + ref.replace("\\", "\\\\")
                        .replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
    }

    /** Executes a previously materialized protocol outcome. */
    public Result execute(
            String taskId,
            ChainModelProtocolOutcome outcome,
            Instant committedAt) {
        required(taskId, "taskId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(committedAt, "committedAt");
        if (outcome instanceof ChainModelProtocolOutcome.ModelCallFailed failed) {
            return Result.modelFailed(failed.invocationId(), failed.errorCode(), failed.attempts());
        }
        ChainModelProtocolOutcome.ProposalReady ready =
                (ChainModelProtocolOutcome.ProposalReady) outcome;
        ChainPersistenceRecords.ModelProposalRecord proposal = ready.proposal();
        if (!proposal.taskId().equals(taskId)) {
            throw failure("CHAIN_EXECUTOR_PROPOSAL_TASK_MISMATCH");
        }
        if (proposal.proposalKind() != ChainProposalKind.EXECUTOR_TOOL_ACTION
                && proposal.proposalKind()
                        != ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE) {
            return Result.unsupportedProposal(proposal.proposalId(), proposal.proposalKind());
        }
        ChainProposalAdmissionService.AdmissionResult admitted = admission.admit(
                new ChainProposalAdmissionService.AdmissionRequest(
                        proposal.proposalId(), taskId,
                        identity("executor-proposal-accepted", proposal.proposalId()),
                        true, null, proposal.payload().sha256(), committedAt));
        if (!admitted.executable()) {
            return Result.proposalNotExecutable(
                    proposal.proposalId(), admitted.state().stateKind().name());
        }

        ChainPersistenceRecords.ActionBindingRecord action = actions.commit(
                new ChainActionRuntime.ActionCommand(
                        taskId, proposal.proposalId(), committedAt));
        ChainEffectRuntime.ExecutionOutcome execution = proposal.proposalKind()
                == ChainProposalKind.EXECUTOR_TOOL_ACTION
                ? effects.execute(new ChainEffectRuntime.ToolActionRequest(
                        taskId, action.actionId(), committedAt))
                : workspaceChanges.apply(new ChainEffectRuntime.WorkspaceChangeRequest(
                        taskId, action.actionId(), committedAt));
        return Result.effect(
                proposal.proposalId(), action.actionId(), execution.kind(),
                execution.receiptRef(), execution.errorRef(), execution.uncertaintyRef());
    }

    private static String identity(String prefix, String value) {
        return prefix + "." + sha256(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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

    @FunctionalInterface
    interface ProposalAdmission {
        ChainProposalAdmissionService.AdmissionResult admit(
                ChainProposalAdmissionService.AdmissionRequest request);
    }

    @FunctionalInterface
    interface ActionCommitter {
        ChainPersistenceRecords.ActionBindingRecord commit(
                ChainActionRuntime.ActionCommand command);
    }

    @FunctionalInterface
    interface EffectExecutor {
        ChainEffectRuntime.ExecutionOutcome execute(
                ChainEffectRuntime.ToolActionRequest request);
    }

    @FunctionalInterface
    interface WorkspaceChangeExecutor {
        ChainEffectRuntime.ExecutionOutcome apply(
                ChainEffectRuntime.WorkspaceChangeRequest request);
    }

    public record Result(
            Status status,
            String proposalId,
            String actionId,
            ChainEffectRuntime.OutcomeKind effectKind,
            String receiptRef,
            String errorRef,
            String uncertaintyRef,
            String modelInvocationId,
            String failureCode,
            int attempts,
            ChainProposalKind unsupportedKind) {
        public Result {
            Objects.requireNonNull(status, "status");
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts must not be negative");
            }
        }

        static Result modelFailed(String invocationId, String code, int attempts) {
            return new Result(Status.MODEL_FAILED, null, null, null, null, null,
                    null, invocationId, code, attempts, null);
        }

        static Result unsupportedProposal(String proposalId, ChainProposalKind kind) {
            return new Result(Status.UNSUPPORTED_PROPOSAL, proposalId, null, null,
                    null, null, null, null, null, 0, kind);
        }

        static Result proposalNotExecutable(String proposalId, String state) {
            return new Result(Status.PROPOSAL_NOT_EXECUTABLE, proposalId, null, null,
                    null, null, null, null, "PROPOSAL_" + state, 0, null);
        }

        static Result repairRejected(String proposalId, String reason) {
            return new Result(Status.REPAIR_REJECTED, proposalId, null, null,
                    null, null, null, null, reason, 0, null);
        }

        static Result actionFailureStepBlocked(
                String proposalId, String stepBlockId) {
            return new Result(Status.ACTION_FAILURE_STEP_BLOCK_COMMITTED,
                    proposalId, null, null, null, stepBlockId, null,
                    null, null, 0, null);
        }

        static Result stepResultCommitted(String proposalId) {
            return new Result(Status.STEP_RESULT_COMMITTED, proposalId, null, null,
                    null, null, null, null, null, 0, null);
        }

        static Result effect(
                String proposalId,
                String actionId,
                ChainEffectRuntime.OutcomeKind kind,
                String receiptRef,
                String errorRef,
                String uncertaintyRef) {
            return new Result(Status.EFFECT_DISPATCHED, proposalId, actionId, kind,
                    receiptRef, errorRef, uncertaintyRef, null, null, 0, null);
        }
    }

    public record StepResultIdentity(
            String instructionId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            long planRevisionNumber,
            String stepId,
            String activationEventId,
            String versionFenceSha256,
            String workspaceCandidateId,
            Long artifactId,
            String candidateFingerprint,
            String diffDigest,
            String receiptRef,
            String validationId,
            String validationRequestDigest,
            String validationReceiptDigest,
            boolean candidateRequired) {
        public StepResultIdentity(
                String instructionId, String taskFrameId, String planId,
                String planRevisionId, long planRevisionNumber, String stepId,
                String activationEventId, String versionFenceSha256,
                String workspaceCandidateId, Long artifactId,
                String candidateFingerprint, String diffDigest,
                boolean candidateRequired) {
            this(instructionId, taskFrameId, planId, planRevisionId,
                    planRevisionNumber, stepId, activationEventId,
                    versionFenceSha256, workspaceCandidateId, artifactId,
                    candidateFingerprint, diffDigest, null, null, null, null,
                    candidateRequired);
        }

        public StepResultIdentity(
                String instructionId, String taskFrameId, String planId,
                String planRevisionId, long planRevisionNumber, String stepId,
                String activationEventId, String versionFenceSha256,
                String workspaceCandidateId, Long artifactId,
                String candidateFingerprint, String diffDigest) {
            this(instructionId, taskFrameId, planId, planRevisionId,
                    planRevisionNumber, stepId, activationEventId,
                    versionFenceSha256, workspaceCandidateId, artifactId,
                    candidateFingerprint, diffDigest, null, null, null, null,
                    false);
        }

        public StepResultIdentity(
                String instructionId, String taskFrameId, String planId,
                String planRevisionId, long planRevisionNumber, String stepId,
                String activationEventId, String versionFenceSha256,
                String workspaceCandidateId, long artifactId,
                String candidateFingerprint, String diffDigest) {
            this(instructionId, taskFrameId, planId, planRevisionId,
                    planRevisionNumber, stepId, activationEventId,
                    versionFenceSha256, workspaceCandidateId,
                    Long.valueOf(artifactId), candidateFingerprint,
                    diffDigest, null, null, null, null, false);
        }

        public StepResultIdentity {
            required(instructionId, "instructionId");
            required(taskFrameId, "taskFrameId");
            required(planId, "planId");
            required(planRevisionId, "planRevisionId");
            required(stepId, "stepId");
            required(activationEventId, "activationEventId");
            required(versionFenceSha256, "versionFenceSha256");
            if (planRevisionNumber < 1 || (artifactId != null && artifactId < 1)) {
                throw new IllegalArgumentException("StepResult identity numbers must be positive");
            }
            if (artifactId == null && (candidateFingerprint != null || diffDigest != null)) {
                throw new IllegalArgumentException("candidate identity requires artifact");
            }
            if (artifactId != null) {
                required(workspaceCandidateId, "workspaceCandidateId");
                required(candidateFingerprint, "candidateFingerprint");
                required(diffDigest, "diffDigest");
            }
            boolean anyValidation = validationId != null
                    || validationRequestDigest != null
                    || validationReceiptDigest != null;
            boolean allValidation = validationId != null
                    && validationRequestDigest != null
                    && validationReceiptDigest != null;
            if (anyValidation && !allValidation) {
                throw new IllegalArgumentException(
                        "validation identity must be all-or-none");
            }
            if (validationId != null) {
                required(receiptRef, "receiptRef");
            }
        }
    }

    public record OfficialSuccessor(
            String authorityType,
            String authorityRef) {
        public OfficialSuccessor {
            required(authorityType, "authorityType");
            required(authorityRef, "authorityRef");
        }
    }

    public enum Status {
        MODEL_FAILED,
        UNSUPPORTED_PROPOSAL,
        PROPOSAL_NOT_EXECUTABLE,
        REPAIR_REJECTED,
        ACTION_FAILURE_STEP_BLOCK_COMMITTED,
        STEP_RESULT_COMMITTED,
        EFFECT_DISPATCHED
    }
}
