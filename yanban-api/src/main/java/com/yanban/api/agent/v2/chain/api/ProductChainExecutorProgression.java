package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceAdapter;
import com.yanban.api.agent.v2.chain.context.ProductChainContextSourceFactory;
import com.yanban.api.agent.v2.chain.context.ProductChainRuntimePolicySource;
import com.yanban.api.agent.v2.chain.context.ProductChainModelCallIdentity;
import com.yanban.api.agent.v2.chain.context.ProductChainExecutorActionContextProjection;
import com.yanban.api.agent.v2.chain.progression.ProductChainActionFailureProgression;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpointResolver;
import com.yanban.api.agent.v2.chain.model.ProductChainModelMaterializationAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainValidationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.validation.ProductChainValidationAuthority;
import com.yanban.api.agent.v2.chain.validation.ProductChainValidationBundleAuthority;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.effect.ProductChainCurrentAuthorityGate;
import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.persistence.ProductPlanBootstrapRepositoryAdapter;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatModelProvider;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.context.ChainContextFreezeOutcome;
import io.paperagent.v2.chain.context.DefaultChainContextManager;
import io.paperagent.v2.chain.model.ChainModelProtocolOutcome;
import io.paperagent.v2.chain.model.ChainModelProtocolRequest;
import io.paperagent.v2.chain.model.ChainModelProtocolService;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.model.ChainRoleOutputDecoder;
import io.paperagent.v2.chain.model.ChainProviderProtocolCode;
import io.paperagent.v2.chain.model.ChainProviderProtocolException;
import io.paperagent.v2.chain.step.ChainStepAuthorityPort;
import io.paperagent.v2.chain.step.ChainStepResultRuntime;
import io.paperagent.v2.chain.step.ChainStepRuntime;
import io.paperagent.v2.chain.step.ChainStepStateMachine;
import io.paperagent.v2.chain.step.ChainReadinessAuthorityPort;
import io.paperagent.v2.chain.review.ChainReviewRuntime;
import io.paperagent.v2.chain.validation.ChainValidationRuntime;
import io.paperagent.v2.chain.validation.ChainValidationBundleRuntime;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainPersistenceRecords.AcceptedResultRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import io.paperagent.v2.chain.transition.ChainApplicabilityRuntime;
import io.paperagent.v2.chain.ChainApplicability;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectOutcomeRepository;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStep;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Product composition for one bounded Executor turn after a Plan change.
 *
 * <p>The class freezes a complete Executor ContextRevision from the current
 * formal Plan/Step facts, invokes the same model protocol as Planner, and
 * hands the result to the sole Action/Effect pump. It intentionally performs
 * one role turn only; recovery or a later turn must re-read durable facts.</p>
 */
@Component
public final class ProductChainExecutorProgression {
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductPlanBootstrapRepositoryAdapter bootstraps;
    private final ProductChainStepAuthorityAdapter steps;
    private final UserSettingsService settings;
    private final ChatModelProvider provider;
    private final PlatformTransactionManager transactions;
    private final ProductChainExecutorPump pump;
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainFinalizationRepositoryAdapter finalization;
    private final ProductChainCurrentAuthorityGate authorityGate;
    private final ProductChainExecutorActionContextProjection actionContext;
    private final ChainValidationRuntime validations;
    private final ProductChainValidationBundleAuthority validationBundles;
    private final ProductChainContextSourceFactory contextSources;
    private final ProductChainModelCallIdentity modelCallIdentity;
    private final ProductChainActionFailureProgression actionFailures;
    private final EffectOutcomeRepository effectOutcomes;

    public ProductChainExecutorProgression(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models,
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations,
            ProductPlanBootstrapRepositoryAdapter bootstraps,
            ProductChainStepAuthorityAdapter steps,
            UserSettingsService settings,
            @Qualifier("chatModelProvider") ChatModelProvider provider,
            PlatformTransactionManager transactions,
            ProductChainExecutorPump pump,
            NamedParameterJdbcTemplate jdbc,
            ProductChainFinalizationRepositoryAdapter finalization,
            ProductChainCurrentAuthorityGate authorityGate,
            ProductChainExecutorActionContextProjection actionContext,
            ProductChainValidationAuthority validationAuthority,
            ProductChainValidationRepositoryAdapter validationRepository,
            ProductChainValidationBundleAuthority validationBundles,
            ProductChainContextSourceFactory contextSources,
            ProductChainModelCallIdentity modelCallIdentity,
            ProductChainActionFailureProgression actionFailures,
            EffectOutcomeRepository effectOutcomes) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.pump = Objects.requireNonNull(pump, "pump");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.authorityGate = Objects.requireNonNull(authorityGate, "authorityGate");
        this.actionContext = Objects.requireNonNull(actionContext, "actionContext");
        this.validations = new ChainValidationRuntime(
                Objects.requireNonNull(validationAuthority,
                        "validationAuthority"),
                Objects.requireNonNull(validationRepository,
                        "validationRepository"));
        this.validationBundles = Objects.requireNonNull(
                validationBundles, "validationBundles");
        this.contextSources = Objects.requireNonNull(
                contextSources, "contextSources");
        this.modelCallIdentity = Objects.requireNonNull(
                modelCallIdentity, "modelCallIdentity");
        this.actionFailures = Objects.requireNonNull(
                actionFailures, "actionFailures");
        this.effectOutcomes = Objects.requireNonNull(
                effectOutcomes, "effectOutcomes");
    }

    /**
     * Reuses the unique formal successor left by this owner when a crash
     * happened before its transition-stage marker was appended.
     */
    public ChainCompositeTransitionRuntime.StageCommitResult
            recoverCommittedStage(
                    ChainCompositeTransitionRuntime.StageCommand command) {
        Objects.requireNonNull(command, "command");
        return switch (command.transition().transitionType()) {
            case ACCEPT_STEP -> recoverAcceptedStepStage(command);
            case FINAL_STEP_READINESS -> recoverReadinessStage(command);
            default -> throw new IllegalStateException(
                    "CHAIN_STEP_RECOVERY_TRANSITION_TYPE_INVALID");
        };
    }

    /**
     * Recovers and consumes one persisted, accepted Executor proposal without
     * invoking the model. An already bound proposal is returned read-only.
     */
    public ProductChainExecutorPump.OfficialSuccessor consumeAcceptedProposal(
            String taskId, String proposalId, Instant committedAt) {
        Objects.requireNonNull(committedAt, "committedAt");
        AcceptedExecutorProposal recovered = acceptedExecutorProposal(
                required(taskId, "taskId"), required(proposalId, "proposalId"));
        if (recovered.states().size() == 2) {
            return verifyBoundSuccessor(recovered);
        }
        if (recovered.ready().proposal().proposalKind()
                == ChainProposalKind.EXECUTOR_STEP_BLOCKED) {
            throw new IllegalStateException(
                    "CHAIN_EXECUTOR_STEP_BLOCKED_CONSUMER_MISSING");
        }
        ProductChainExecutorPump.StepResultIdentity identity =
                recovered.ready().proposal().proposalKind()
                        == ChainProposalKind.EXECUTOR_STEP_RESULT
                        ? recoveredStepResultIdentity(
                        recovered, committedAt) : null;
        return pump.consumeAccepted(
                taskId, recovered.ready(), identity, committedAt);
    }

    /** Read-only reconstruction used by persistent recovery. */
    public ChainModelProtocolOutcome.ProposalReady recoverAcceptedProposal(
            String taskId, String proposalId) {
        return acceptedExecutorProposal(
                required(taskId, "taskId"), required(proposalId, "proposalId"))
                .ready();
    }

    /**
     * Recovers the exact accepted STEP_BLOCKED authority selected by its
     * state-event identity.  The accepted model output is returned only after
     * the ordinary Executor lineage, COMPLETE Context, Plan/Step activation,
     * canonical payload, and complete proposal-state prefix are revalidated.
     */
    public AcceptedStepBlock recoverAcceptedStepBlock(
            String taskId, String acceptedEventId) {
        var accepted = models.findProposalStateEvent(
                        required(acceptedEventId, "acceptedEventId"))
                .orElseThrow(() -> failure(
                        "CHAIN_STEP_BLOCK_ACCEPTED_EVENT_MISSING"));
        if (!accepted.taskId().equals(required(taskId, "taskId"))
                || accepted.stateSequence() != 1L
                || accepted.stateKind() != ChainProposalState.ACCEPTED
                || accepted.officialAuthorityType() != null
                || accepted.officialAuthorityRef() != null) {
            throw failure("CHAIN_STEP_BLOCK_ACCEPTED_EVENT_INVALID");
        }
        AcceptedExecutorProposal recovered = acceptedExecutorProposal(
                taskId, accepted.proposalId());
        if (recovered.ready().proposal().proposalKind()
                != ChainProposalKind.EXECUTOR_STEP_BLOCKED
                || recovered.states().size() != 1
                || !recovered.states().get(0).equals(accepted)) {
            throw failure("CHAIN_STEP_BLOCK_PROPOSAL_STATE_INVALID");
        }
        String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                + recovered.ready().proposal().proposalKind().wireName()
                + "\",\"payload\":"
                + recovered.ready().proposal().payload().json() + "}";
        ExecutorPayload.StepBlocked payload = (ExecutorPayload.StepBlocked)
                new StrictChainProviderOutputParser().parse(
                        encoded, ChainRole.EXECUTOR,
                        recovered.context().workState(), null).payload();
        return new AcceptedStepBlock(recovered.ready().proposal(), accepted,
                recovered.context(), recovered.binding(), payload);
    }

    public record AcceptedStepBlock(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ProposalStateEventRecord acceptedState,
            ChainPersistenceRecords.ContextRevisionRecord executorContext,
            ChainPersistenceRecords.PlanBindingRecord planBinding,
            ExecutorPayload.StepBlocked payload) {
        public AcceptedStepBlock {
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(acceptedState, "acceptedState");
            Objects.requireNonNull(executorContext, "executorContext");
            Objects.requireNonNull(planBinding, "planBinding");
            Objects.requireNonNull(payload, "payload");
        }
    }

    private AcceptedExecutorProposal acceptedExecutorProposal(
            String taskId, String proposalId) {
        var task = foundations.findTask(taskId).orElseThrow(() -> failure(
                "CHAIN_EXECUTOR_RECOVERY_TASK_MISSING"));
        var proposal = models.findProposal(proposalId).orElseThrow(() -> failure(
                "CHAIN_EXECUTOR_RECOVERY_PROPOSAL_MISSING"));
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTOR_RECOVERY_INVOCATION_MISSING"));
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTOR_RECOVERY_CONTEXT_MISSING"));
        var byInvocation = models.findProposalByInvocation(
                        invocation.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTOR_RECOVERY_INVOCATION_PROPOSAL_MISSING"));
        String instructionId = currentInstructionId(task);
        boolean allowedState = invocation.workState() == ChainWorkState.EXECUTING
                || invocation.workState()
                == ChainWorkState.VALIDATING_PENDING_ITEM;
        if (!proposal.equals(byInvocation)
                || !proposal.taskId().equals(taskId)
                || !proposal.invocationId().equals(invocation.invocationId())
                || proposal.role() != ChainRole.EXECUTOR
                || proposal.proposalKind().role() != ChainRole.EXECUTOR
                || !invocation.taskId().equals(taskId)
                || invocation.role() != ChainRole.EXECUTOR
                || !allowedState
                || !invocation.runtimePolicyVersion().equals(
                        context.runtimePolicyVersion())
                || !context.contextRevisionId().equals(
                        invocation.contextRevisionId())
                || !context.taskId().equals(taskId)
                || context.role() != ChainRole.EXECUTOR
                || context.workState() != invocation.workState()
                || !context.callReason().equals(invocation.callReason())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !Objects.equals(context.completionToken(),
                        invocation.completionToken())
                || !context.runtimePolicyVersion().equals(
                        invocation.runtimePolicyVersion())
                || !context.instructionId().equals(instructionId)
                || !Objects.equals(context.projectId(), task.projectId())
                || !Objects.equals(context.projectVersion(),
                        task.initialProjectVersion())
                || context.taskFrameId() == null || context.planId() == null
                || context.planRevisionId() == null
                || context.planRevisionNumber() == null
                || context.stepId() == null
                || context.activationEventId() == null
                || context.workspaceId() == null
                || context.requestDigest() == null) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_LINEAGE_INVALID");
        }
        verifyCanonical(proposal.payload(),
                "CHAIN_EXECUTOR_RECOVERY_PAYLOAD_INVALID");
        verifyCanonical(proposal.sourceRefs(),
                "CHAIN_EXECUTOR_RECOVERY_SOURCE_REFS_INVALID");
        String expectedProposalId = "proposal." + sha256(
                invocation.invocationId() + "\0"
                        + proposal.proposalKind().wireName() + "\0"
                        + proposal.payload().sha256());
        if (!proposal.proposalId().equals(expectedProposalId)) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_PROPOSAL_ID_INVALID");
        }
        var binding = exactPlanBinding(taskId, context);
        step(binding, context.stepId());
        exactActivation(taskId, context);
        var candidate = exactCandidate(taskId, context);
        var body = recoveredBody(taskId, proposal, invocation);
        int attempts = verifiedAttempts(taskId, invocation.invocationId());
        var states = acceptedStatePrefix(taskId, proposalId);
        return new AcceptedExecutorProposal(task, context, binding, candidate,
                new ChainModelProtocolOutcome.ProposalReady(
                        proposal, body, attempts, true), states);
    }

    private String currentInstructionId(
            ChainPersistenceRecords.TaskRecord task) {
        var bindings = foundations.findTaskInstructions(
                        task.taskId(), task.nextEventSequence()).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord
                                ::taskInstructionSequence))
                .toList();
        if (bindings.isEmpty()) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_INSTRUCTION_MISSING");
        }
        for (int index = 0; index < bindings.size(); index++) {
            if (!bindings.get(index).taskId().equals(task.taskId())
                    || bindings.get(index).taskInstructionSequence()
                    != index + 1L) {
                throw failure(
                        "CHAIN_EXECUTOR_RECOVERY_INSTRUCTION_PREFIX_INVALID");
            }
        }
        String instructionId = bindings.get(bindings.size() - 1)
                .instructionId();
        var instruction = foundations.findInstruction(instructionId)
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTOR_RECOVERY_INSTRUCTION_MISSING"));
        if (instruction.sessionId() != task.sessionId()) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_INSTRUCTION_INVALID");
        }
        return instructionId;
    }

    private ChainPersistenceRecords.PlanBindingRecord exactPlanBinding(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        var matches = workflow.findPlanBindings(taskId).stream()
                .filter(value -> value.instructionId().equals(
                        context.instructionId()))
                .filter(value -> value.taskFrameId().equals(
                        context.taskFrameId()))
                .filter(value -> value.planId().equals(context.planId()))
                .filter(value -> value.planRevisionId().equals(
                        context.planRevisionId()))
                .filter(value -> value.planRevisionNumber()
                        == context.planRevisionNumber())
                .toList();
        if (matches.size() != 1) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_PLAN_INVALID");
        }
        return matches.get(0);
    }

    private void exactActivation(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        long matches = steps.findStepEvents(
                        taskId, context.planRevisionId()).stream()
                .filter(value -> value.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .filter(value -> value.command().stepId().equals(
                        context.stepId()))
                .filter(value -> value.command().activationEventId().equals(
                        context.activationEventId()))
                .filter(value -> value.command().eventId().equals(
                        context.activationEventId()))
                .count();
        if (matches != 1) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_ACTIVATION_INVALID");
        }
    }

    private ChainPersistenceRecords.WorkspaceCandidateRecord exactCandidate(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        if (context.candidateArtifactId() == null) {
            return null;
        }
        var matches = workflow.findWorkspaceCandidates(taskId).stream()
                .filter(value -> value.artifactId()
                        == context.candidateArtifactId())
                .filter(value -> value.candidateFingerprint().equals(
                        context.candidateFingerprint()))
                .filter(value -> value.workspaceId().equals(
                        context.workspaceId()))
                .filter(value -> value.baseProjectVersion().equals(
                        context.projectVersion()))
                .toList();
        if (matches.size() != 1) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_CANDIDATE_INVALID");
        }
        return matches.get(0);
    }

    private ChainPersistenceRecords.ContentRecord recoveredBody(
            String taskId,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation) {
        ChainContentKind expected = switch (proposal.proposalKind()) {
            case EXECUTOR_WORKSPACE_CHANGE ->
                    ChainContentKind.WORKSPACE_CHANGE_BODY;
            case EXECUTOR_STEP_RESULT ->
                    ChainContentKind.CANDIDATE_STEP_RESULT;
            default -> null;
        };
        if (expected == null) {
            if (proposal.bodyAuthorityType() != null
                    || proposal.bodyAuthorityRef() != null) {
                throw failure("CHAIN_EXECUTOR_RECOVERY_BODY_INVALID");
            }
            return null;
        }
        if (!expected.name().equals(proposal.bodyAuthorityType())
                || proposal.bodyAuthorityRef() == null) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_BODY_INVALID");
        }
        var body = models.findContent(proposal.bodyAuthorityRef())
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTOR_RECOVERY_BODY_MISSING"));
        String expectedContentId = "content." + sha256(taskId + "\0"
                + invocation.invocationId() + "\0" + expected.name());
        if (!body.contentId().equals(expectedContentId)
                || !body.taskId().equals(taskId)
                || !body.invocationId().equals(invocation.invocationId())
                || body.contentKind() != expected
                || !body.contentId().equals(proposal.bodyAuthorityRef())
                || !sha256(body.body()).equals(body.bodySha256())) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_BODY_INVALID");
        }
        return body;
    }

    private int verifiedAttempts(String taskId, String invocationId) {
        var attempts = models.findProviderAttempts(invocationId);
        if (attempts.isEmpty()) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_ATTEMPT_MISSING");
        }
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (attempt.attemptNo() != index + 1
                    || !attempt.invocationId().equals(invocationId)
                    || !attempt.taskId().equals(taskId)) {
                throw failure("CHAIN_EXECUTOR_RECOVERY_ATTEMPT_INVALID");
            }
        }
        var winner = attempts.get(attempts.size() - 1);
        if (winner.schemaValidationStatus()
                != ChainPersistenceRecords.ValidationStatus.PASSED
                || winner.proposalValidationStatus()
                != ChainPersistenceRecords.ValidationStatus.PASSED
                || winner.errorCode() != null) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_ATTEMPT_INVALID");
        }
        return attempts.size();
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            acceptedStatePrefix(String taskId, String proposalId) {
        var states = models.findProposalStateEvents(proposalId).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence))
                .toList();
        if (states.isEmpty() || states.size() > 2) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_STATE_INVALID");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposalId)
                    || state.stateSequence() != index + 1L) {
                throw failure("CHAIN_EXECUTOR_RECOVERY_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_EXECUTOR_RECOVERY_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_PROPOSAL_NOT_ACCEPTED");
        }
        return states;
    }

    private ProductChainExecutorPump.StepResultIdentity
            recoveredStepResultIdentity(
                    AcceptedExecutorProposal recovered,
                    Instant committedAt) {
        var context = recovered.context();
        var candidate = recovered.candidate();
        var step = step(recovered.binding(), context.stepId());
        var activation = steps.findStepEvents(
                        recovered.task().taskId(), context.planRevisionId())
                .stream().filter(value -> value.command().eventId().equals(
                        context.activationEventId())).findFirst()
                .orElseThrow(() -> failure(
                        "CHAIN_EXECUTOR_RECOVERY_ACTIVATION_INVALID"));
        ExecutorPayload.StepResult payload = stepResultPayload(
                recovered.ready());
        ChainValidationRuntime.CommitResult validation =
                recoverStepResultValidation(validations,
                        context.validationId(),
                        context.validationRequestDigest(),
                        context.validationReceiptDigest(),
                        validationScope(recovered.binding(), activation,
                                committedAt),
                        stepRequirements(recovered.binding(), step),
                        payload.validationSources());
        return new ProductChainExecutorPump.StepResultIdentity(
                context.instructionId(), context.taskFrameId(),
                context.planId(), context.planRevisionId(),
                context.planRevisionNumber(), context.stepId(),
                context.activationEventId(), candidate == null
                        ? sha256("step-result\0" + recovered.task().taskId()
                        + "\0" + context.activationEventId())
                        : candidate.versionFenceSha256(),
                candidate == null ? null : candidate.workspaceCandidateId(),
                candidate == null ? null : candidate.artifactId(),
                candidate == null ? null : candidate.candidateFingerprint(),
                candidate == null ? null : candidate.diffDigest(),
                payload.validationSources().stream().map(
                                io.paperagent.v2.chain.ProposalFields
                                        .ValidationSource::receiptRef)
                        .sorted().findFirst().orElse(null),
                validation == null ? null
                        : validation.validation().validationId(),
                validation == null ? null
                        : validation.validation().requestDigest(),
                validation == null ? null
                        : validation.validation().receiptSetDigest(),
                candidateRequired(
                        recovered.binding(), step));
    }

    private ProductChainExecutorPump.OfficialSuccessor verifyBoundSuccessor(
            AcceptedExecutorProposal recovered) {
        var state = recovered.states().get(1);
        if (state.stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_STATE_INVALID");
        }
        String type = state.officialAuthorityType();
        String ref = state.officialAuthorityRef();
        var proposal = recovered.ready().proposal();
        boolean exact = switch (proposal.proposalKind()) {
            case EXECUTOR_TOOL_ACTION, EXECUTOR_WORKSPACE_CHANGE ->
                    "ACTION_BINDING".equals(type)
                            && workflow.findActionBindings(
                                    proposal.taskId()).stream()
                            .filter(value -> value.actionId().equals(ref))
                            .filter(value -> value.proposalId().equals(
                                    proposal.proposalId()))
                            .filter(value -> value.instructionId().equals(
                                    recovered.context().instructionId()))
                            .filter(value -> value.taskFrameId().equals(
                                    recovered.context().taskFrameId()))
                            .filter(value -> value.planId().equals(
                                    recovered.context().planId()))
                            .filter(value -> value.planRevisionId().equals(
                                    recovered.context().planRevisionId()))
                            .filter(value -> value.stepId().equals(
                                    recovered.context().stepId()))
                            .filter(value -> value.activationEventId().equals(
                                    recovered.context().activationEventId()))
                            .filter(value -> value.workspaceId().equals(
                                    recovered.context().workspaceId()))
                            .filter(value -> value.baseCandidateKey().equals(
                                    recovered.context().candidateFingerprint()
                                            == null ? ChainIdentity.NONE
                                            : recovered.context()
                                                    .candidateFingerprint()))
                            .filter(value -> value.versionFenceSha256().equals(
                                    sha256(recovered.context().requestDigest()
                                            + "\0" + recovered.context()
                                                    .workspaceId()
                                            + "\0" + (recovered.context()
                                                    .candidateFingerprint()
                                                    == null
                                                    ? ChainIdentity.NONE
                                                    : recovered.context()
                                                            .candidateFingerprint()))))
                            .count() == 1;
            case EXECUTOR_STEP_RESULT ->
                    "CANDIDATE_STEP_RESULT".equals(type)
                            && workflow.findCandidateStepResults(
                                    proposal.taskId()).stream()
                            .filter(value -> value.candidateResultId()
                                    .equals(ref))
                            .filter(value -> value.proposalId().equals(
                                    proposal.proposalId()))
                            .filter(value -> value.contentId().equals(
                                    proposal.bodyAuthorityRef()))
                            .filter(value -> value.instructionId().equals(
                                    recovered.context().instructionId()))
                            .filter(value -> value.taskFrameId().equals(
                                    recovered.context().taskFrameId()))
                            .filter(value -> value.planId().equals(
                                    recovered.context().planId()))
                            .filter(value -> value.planRevisionId().equals(
                                    recovered.context().planRevisionId()))
                            .filter(value -> value.stepId().equals(
                                    recovered.context().stepId()))
                            .filter(value -> value.activationEventId().equals(
                                    recovered.context().activationEventId()))
                            .filter(value -> Objects.equals(value.artifactId(),
                                    recovered.context()
                                            .candidateArtifactId()))
                            .filter(value -> Objects.equals(
                                    value.candidateFingerprint(),
                                    recovered.context()
                                            .candidateFingerprint()))
                            .filter(value -> value.versionFenceSha256().equals(
                                    recovered.candidate() == null
                                            ? sha256("step-result\0"
                                                    + proposal.taskId() + "\0"
                                                    + recovered.context()
                                                            .activationEventId())
                                            : recovered.candidate()
                                                    .versionFenceSha256()))
                            .count() == 1;
            case EXECUTOR_STEP_BLOCKED -> throw failure(
                    "CHAIN_EXECUTOR_STEP_BLOCKED_CONSUMER_MISSING");
            default -> false;
        };
        if (!exact) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_OFFICIAL_MISMATCH");
        }
        return new ProductChainExecutorPump.OfficialSuccessor(type, ref);
    }

    private static void verifyCanonical(
            CanonicalJson value, String errorCode) {
        if (!sha256(value.json()).equals(value.sha256())) {
            throw failure(errorCode);
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

    private record AcceptedExecutorProposal(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate,
            ChainModelProtocolOutcome.ProposalReady ready,
            List<ChainPersistenceRecords.ProposalStateEventRecord> states) {
    }

    private ChainCompositeTransitionRuntime.StageCommitResult
            recoverAcceptedStepStage(
                    ChainCompositeTransitionRuntime.StageCommand command) {
        var transition = command.transition();
        var review = recoveryReview(transition);
        var candidate = recoveryCandidate(transition, review);
        return switch (command.stage()) {
            case ACCEPTED_RESULT_COMMITTED -> successor(
                    "ACCEPTED_RESULT", recoveryAccepted(
                            transition, review, candidate).acceptedResultId());
            case APPLICABILITY_COMMITTED -> successor(
                    "RESULT_APPLICABILITY", recoveryApplicability(
                            transition, recoveryAccepted(
                                    transition, review, candidate))
                            .applicabilityId());
            case STEP_COMPLETED -> successor(
                    "STEP_EVENT", recoveryStepEvent(
                            transition, candidate,
                            ChainStepAuthorityPort.StepEventKind.COMPLETED)
                            .command().eventId());
            case NEXT_STEP_ACTIVATED_OR_NONE -> {
                List<ChainStepAuthorityPort.StepEvent> activated =
                        recoveryStepEvents(transition, candidate,
                                ChainStepAuthorityPort.StepEventKind.ACTIVATED);
                if (activated.size() == 1) {
                    yield successor("STEP_EVENT",
                            activated.get(0).command().eventId());
                }
                if (activated.size() > 1) {
                    throw new IllegalStateException(
                            "CHAIN_ACCEPT_STEP_NEXT_AUTHORITY_AMBIGUOUS");
                }
                ChainStepStateMachine.PlanState state =
                        new ChainStepStateMachine(
                                steps, workflow, foundations, models, contexts)
                                .derive(transition.taskId(),
                                        candidate.planRevisionId());
                boolean verifiedEmpty = state.activeStep().isEmpty()
                        && state.steps().stream().noneMatch(value ->
                        value.status()
                                == io.paperagent.v2.chain.ChainStepStatus.READY);
                if (!verifiedEmpty) {
                    throw new IllegalStateException(
                            "CHAIN_ACCEPT_STEP_NEXT_AUTHORITY_MISSING");
                }
                yield ChainCompositeTransitionRuntime.StageCommitResult.none();
            }
            case OPEN, COMPLETE -> throw new IllegalStateException(
                    "CHAIN_ACCEPT_STEP_RECOVERY_STAGE_NOT_COMMITTED");
            default -> throw new IllegalStateException(
                    "CHAIN_ACCEPT_STEP_RECOVERY_STAGE_UNSUPPORTED");
        };
    }

    private ChainCompositeTransitionRuntime.StageCommitResult
            recoverReadinessStage(
                    ChainCompositeTransitionRuntime.StageCommand command) {
        var transition = command.transition();
        var review = recoveryReview(transition);
        var candidate = recoveryCandidate(transition, review);
        return switch (command.stage()) {
            case ACCEPTED_RESULT_COMMITTED_OR_VERIFIED -> successor(
                    "ACCEPTED_RESULT", recoveryAccepted(
                            transition, review, candidate).acceptedResultId());
            case APPLICABILITY_COMMITTED_OR_EMPTY -> {
                boolean hasUnexpectedAuthority = workflow
                        .findApplicabilityDecisions(transition.taskId()).stream()
                        .anyMatch(value -> transition.transitionId().equals(
                                value.sourceDecisionId()));
                if (hasUnexpectedAuthority) {
                    throw new IllegalStateException(
                            "CHAIN_READINESS_EMPTY_APPLICABILITY_CONFLICT");
                }
                yield ChainCompositeTransitionRuntime.StageCommitResult.none();
            }
            case STEP_COMPLETED_OR_VERIFIED -> successor(
                    "STEP_EVENT", recoveryStepEvent(
                            transition, candidate,
                            ChainStepAuthorityPort.StepEventKind.COMPLETED)
                            .command().eventId());
            case READINESS_COMMITTED -> {
                List<ChainPersistenceRecords.FinalizationReadinessRecord>
                        readiness = finalization.findReadiness(
                                transition.taskId()).stream()
                        .filter(value -> transition.transitionId().equals(
                                value.transitionId()))
                        .filter(value -> transition.sourceDecisionId().equals(
                                value.reviewDecisionId()))
                        .toList();
                if (readiness.isEmpty()) {
                    readiness = List.of(recoverMissingReadiness(
                            transition, review, candidate));
                }
                yield successor("FINALIZATION_READINESS",
                        exactlyOne(readiness,
                                "CHAIN_READINESS_AUTHORITY_MISSING")
                                .readinessId());
            }
            case OPEN, COMPLETE -> throw new IllegalStateException(
                    "CHAIN_READINESS_RECOVERY_STAGE_NOT_COMMITTED");
            default -> throw new IllegalStateException(
                    "CHAIN_READINESS_RECOVERY_STAGE_UNSUPPORTED");
        };
    }

    private ChainPersistenceRecords.FinalizationReadinessRecord
            recoverMissingReadiness(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.ReviewDecisionRecord review,
                    ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(transition.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_READINESS_TASK_AUTHORITY_MISSING"));
        ChainPersistenceRecords.PlanBindingRecord binding = exactlyOne(
                workflow.findPlanBindings(transition.taskId()).stream()
                        .filter(value -> value.taskId().equals(task.taskId()))
                        .filter(value -> value.instructionId().equals(
                                candidate.instructionId()))
                        .filter(value -> value.taskFrameId().equals(
                                candidate.taskFrameId()))
                        .filter(value -> value.planId().equals(
                                candidate.planId()))
                        .filter(value -> value.planRevisionId().equals(
                                candidate.planRevisionId()))
                        .filter(value -> value.planRevisionNumber()
                                == candidate.planRevisionNumber())
                        .toList(),
                "CHAIN_READINESS_PLAN_BINDING_MISSING");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(review.proposalId())
                .filter(value -> value.taskId().equals(task.taskId()))
                .filter(value -> value.proposalKind()
                        == ChainProposalKind
                        .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE)
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_READINESS_REVIEW_PROPOSAL_MISSING"));
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedReflectorStatePrefix(proposal,
                        models.findProposalStateEvents(proposal.proposalId()));
        if (states.size() != 2
                || states.get(1).stateKind()
                != ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                || !"REVIEW_DECISION".equals(
                states.get(1).officialAuthorityType())
                || !review.reviewDecisionId().equals(
                states.get(1).officialAuthorityRef())) {
            throw new IllegalStateException(
                    "CHAIN_READINESS_REVIEW_PROPOSAL_NOT_BOUND");
        }
        ReflectorPayload decoded = decodeReflectorProposal(proposal);
        if (!(decoded instanceof ReflectorPayload
                .AcceptStepAndReadyToFinalize combined)) {
            throw new IllegalStateException(
                    "CHAIN_READINESS_REVIEW_PAYLOAD_INVALID");
        }
        String acceptedIdentity = sha256(candidate.candidateResultId() + "\0"
                + review.reviewDecisionId() + "\0" + candidate.contentId());
        if (!transition.targetIdentityDigest().equals(acceptedIdentity)
                || transition.transitionType()
                != ChainTransitionType.FINAL_STEP_READINESS) {
            throw new IllegalStateException(
                    "CHAIN_READINESS_TRANSITION_IDENTITY_INVALID");
        }
        ChainReviewRuntime.CommitResult reviewResult =
                new ChainReviewRuntime.CommitResult(review, true,
                        ChainReviewRuntime.SuccessorRequirement
                                .ACCEPTED_RESULT_STEP_AND_READINESS);
        ChainReadinessAuthorityPort readinessAuthority = query ->
                readinessMaterial(task, binding, candidate, reviewResult,
                        combined, acceptedIdentity, transition.createdAt());
        ChainStepStateMachine machine = new ChainStepStateMachine(
                steps, workflow, foundations, models, contexts);
        ChainStepRuntime stepRuntime = new ChainStepRuntime(
                machine, workflow, finalization, finalization,
                readinessAuthority, authorityGate);
        return stepRuntime.commitReadiness(
                new ChainStepRuntime.ReadinessCommand(
                        task.taskId(), transition.transitionId(),
                        review.reviewDecisionId(), transition.createdAt()))
                .fact();
    }

    private ChainPersistenceRecords.ReviewDecisionRecord recoveryReview(
            ChainPersistenceRecords.TransitionRecord transition) {
        List<ChainPersistenceRecords.ReviewDecisionRecord> reviews = workflow
                .findReviewDecisions(transition.taskId()).stream()
                .filter(value -> transition.sourceDecisionId().equals(
                        value.reviewDecisionId()))
                .filter(value -> "CANDIDATE_STEP_RESULT".equals(
                        value.reviewObjectType()))
                .toList();
        return exactlyOne(reviews, "CHAIN_RECOVERY_REVIEW_AUTHORITY_MISSING");
    }

    private ChainPersistenceRecords.CandidateStepResultRecord
            recoveryCandidate(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.ReviewDecisionRecord review) {
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates =
                workflow.findCandidateStepResults(transition.taskId()).stream()
                .filter(value -> review.reviewObjectId().equals(
                        value.candidateResultId()))
                .toList();
        return exactlyOne(candidates,
                "CHAIN_RECOVERY_CANDIDATE_AUTHORITY_MISSING");
    }

    private ChainPersistenceRecords.AcceptedResultRecord recoveryAccepted(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.ReviewDecisionRecord review,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        List<ChainPersistenceRecords.AcceptedResultRecord> accepted = workflow
                .findAcceptedResults(transition.taskId()).stream()
                .filter(value -> transition.transitionId().equals(
                        value.transitionId()))
                .filter(value -> review.reviewDecisionId().equals(
                        value.reviewDecisionId()))
                .filter(value -> candidate.candidateResultId().equals(
                        value.candidateResultId()))
                .filter(value -> transition.targetIdentityDigest().equals(
                        value.acceptedIdentitySha256()))
                .toList();
        return exactlyOne(accepted,
                "CHAIN_RECOVERY_ACCEPTED_AUTHORITY_MISSING");
    }

    private ChainPersistenceRecords.ResultApplicabilityRecord
            recoveryApplicability(
                    ChainPersistenceRecords.TransitionRecord transition,
                    ChainPersistenceRecords.AcceptedResultRecord accepted) {
        List<ChainPersistenceRecords.ResultApplicabilityRecord> values =
                workflow.findApplicabilityDecisions(
                        transition.taskId()).stream()
                .filter(value -> accepted.acceptedResultId().equals(
                        value.acceptedResultId()))
                .filter(value -> value.sourceType()
                        == ChainApplicability.SourceType.ACCEPT_STEP)
                .filter(value -> transition.transitionId().equals(
                        value.sourceDecisionId()))
                .filter(value -> value.conclusion()
                        == ChainApplicability.Outcome.APPLICABLE)
                .toList();
        return exactlyOne(values,
                "CHAIN_RECOVERY_APPLICABILITY_AUTHORITY_MISSING");
    }

    private ChainStepAuthorityPort.StepEvent recoveryStepEvent(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            ChainStepAuthorityPort.StepEventKind kind) {
        return exactlyOne(recoveryStepEvents(transition, candidate, kind),
                "CHAIN_RECOVERY_STEP_EVENT_AUTHORITY_MISSING");
    }

    private List<ChainStepAuthorityPort.StepEvent> recoveryStepEvents(
            ChainPersistenceRecords.TransitionRecord transition,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            ChainStepAuthorityPort.StepEventKind kind) {
        return steps.findStepEvents(
                        transition.taskId(), candidate.planRevisionId()).stream()
                .filter(value -> value.command().eventKind() == kind)
                .filter(value -> transition.transitionId().equals(
                        value.command().transitionId()))
                .filter(value -> transition.sourceDecisionId().equals(
                        value.command().sourceDecisionId()))
                .toList();
    }

    private static ChainCompositeTransitionRuntime.StageCommitResult successor(
            String type, String ref) {
        return ChainCompositeTransitionRuntime.StageCommitResult.successor(
                type, ref);
    }

    private static <T> T exactlyOne(List<T> values, String errorCode) {
        if (values.size() != 1) {
            throw new IllegalStateException(errorCode);
        }
        return values.get(0);
    }

    public ProductChainExecutorPump.Result advance(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body,
            ProductChainPlanTransitionDriver.Result transition,
            Instant now) {
        ChainPersistenceRecords.PlanBindingRecord binding =
                transition.planBinding();
        ChainStepAuthorityPort.StepEvent activation = activeActivation(
                task.taskId(), binding.planRevisionId());
        ChainPersistenceRecords.WorkspaceCandidateRecord candidate =
                currentWorkspaceCandidate(task.taskId());
        if (candidate != null && candidateBelongsToActiveStep(
                candidate, currentCandidateAction(task.taskId(), candidate),
                binding, activation)) {
            ChainPersistenceRecords.CandidateStepResultRecord result =
                    advanceStepResult(session, task, instruction, body,
                            transition, now);
            return ProductChainExecutorPump.Result.stepResultCommitted(
                    result.proposalId());
        }
        ProductChainExecutorActionContextProjection.Failure failure =
                actionContext.project(task.taskId(),
                        activation.command().stepId(),
                        activation.command().activationEventId())
                        .latestFailure();
        if (failure != null) {
            ProductChainExecutorPump.Result failedExecution =
                    ProductChainExecutorPump.Result.effect(
                            failure.proposalId(), failure.actionId(),
                            io.paperagent.v2.chain.effect.ChainEffectRuntime
                                    .OutcomeKind.EFFECT_FAILED,
                            failure.errorRef(), failure.errorRef(), null);
            return advanceRepair(session, task, instruction, body,
                    transition, failedExecution, now);
        }
        return advanceInternal(session, task, instruction, body, transition,
                null, now);
    }

    public ProductChainExecutorPump.Result advanceRepair(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body,
            ProductChainPlanTransitionDriver.Result transition,
            ProductChainExecutorPump.Result failedExecution,
            Instant now) {
        Objects.requireNonNull(failedExecution, "failedExecution");
        return advanceInternal(session, task, instruction, body, transition,
                failedExecution, now);
    }

    private ProductChainExecutorPump.Result advanceInternal(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body,
            ProductChainPlanTransitionDriver.Result transition,
            ProductChainExecutorPump.Result failedExecution,
            Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(now, "now");
        ChainPersistenceRecords.PlanBindingRecord binding = transition.planBinding();
        ChainStepAuthorityPort.StepEvent activation = activeActivation(
                task.taskId(), binding.planRevisionId());
        PlanStep step = step(binding, activation.command().stepId());
        // The stable V2 execution context derives one persistent workspace
        // from the authenticated Agent turn run identity. The chain action
        // must reuse that exact authority, otherwise sandbox.execute rejects
        // the effect with intent_authority before dispatch.
        // Keep the Executor's authority exactly aligned with the authenticated
        // turn context composer: ProductWorkspaceIdDerivation hashes the
        // stable AgentRunIdentity.runId(), not the numeric turn id alone.
        String workspaceId = "product-workspace." + sha256(
                "workspace\0AGENT_TURN:" + task.turnId());
        String callReason = "STEP_EXECUTION";
        String executionBody = Objects.toString(body, "");
        ChainPersistenceRecords.WorkspaceCandidateRecord currentCandidate =
                currentWorkspaceCandidate(task.taskId());
        Long currentCandidateArtifactId = currentCandidate == null
                ? null : currentCandidate.artifactId();
        String currentCandidateFingerprint = currentCandidate == null
                ? null : currentCandidate.candidateFingerprint();

        ProductChainExecutorActionContextProjection.Projection actionProjection =
                actionContext.project(task.taskId(), activation.command().stepId(),
                        activation.command().activationEventId());
        ProductChainExecutorActionContextProjection.Failure repairFailure =
                failedExecution == null
                        ? null : actionProjection.latestFailure();
        SuccessfulReceipt successfulReceipt = latestSuccessfulReceipt(
                task.taskId(), activation.command().stepId(),
                activation.command().activationEventId());
        boolean receiptValidation = receiptValidationStep(binding, step);
        boolean observedActionFailure = receiptValidation
                && repairFailure != null
                && isObservedActionFailure(repairFailure);
        // A later Executor turn can use the same instruction text while its
        // formal Candidate or Action authority has advanced. Include those
        // exact durable inputs in the deterministic identity so distinct
        // turns cannot collide, while replaying the same authority remains
        // idempotent.
        String contextId = executorContextId(
                task.taskId(), activation.command().activationEventId(), callReason,
                executionBody, currentCandidateFingerprint,
                actionProjection.fields().get("action.currentStepAttemptTable"));
        String invocationId = identity("invocation", contextId);
        ProductChainModelCallIdentity.Binding callIdentity =
                modelCallIdentity.bind(
                        task.taskId(), contextId, invocationId);
        ProductChainContextSourceAdapter source = contextSources.source();
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, source);
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        callIdentity.contextRevisionId(), task.taskId(),
                        callIdentity.parentContextRevisionId(),
                        ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, callReason,
                        instruction.instructionId(), binding.taskFrameId(),
                        binding.planId(), binding.planRevisionId(),
                        binding.planRevisionNumber(), activation.command().stepId(),
                        activation.command().activationEventId(), task.projectId(),
                        task.initialProjectVersion(), workspaceId, currentCandidateArtifactId,
                        currentCandidateFingerprint,
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
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw new IllegalStateException("Executor context input is blocked");
        }
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                task.userId(), session.getModelProviderSnapshot(),
                session.getModelSnapshot());
        ProductChainModelEndpointResolver resolver = request ->
                new ProductChainModelEndpoint(endpoint.providerKey(), endpoint.modelName(),
                        endpoint.apiKey(), endpoint.apiUrl());
        String expectedBaseCandidateRef = currentCandidate == null
                ? ChainIdentity.NONE : currentCandidate.candidateFingerprint();
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) -> {
            ProviderRoleOutput output =
                    new io.paperagent.v2.chain.model.StrictChainProviderOutputParser()
                            .parse(raw, role, state, gap);
            validateExecutorCandidateBase(output, expectedBaseCandidateRef);
            validateStepMutationBoundary(output, step);
            validateExecutorStepResultValidationBindings(
                    output, step.validationRequirementIds());
            if (receiptValidation && successfulReceipt != null) {
                validateReceiptValidationSuccess(output, successfulReceipt);
            }
            if (observedActionFailure
                    && output.payload()
                    instanceof ExecutorPayload.StepBlocked) {
                validateReceiptValidationFailure(output, repairFailure);
            }
            if (repairFailure != null && !observedActionFailure) {
                String rejection = actionContext.validateRepair(
                        output, repairFailure);
                if (rejection != null
                        && !"REPAIR_DID_NOT_CHANGE_ACTION".equals(
                        rejection)) {
                    throw new IllegalArgumentException(
                            repairProtocolFeedback(rejection));
                }
            }
            return output;
        };
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(models, models, models,
                        transactions),
                new ProductChainChatModelAdapter(provider, resolver), decoder);
        ChainModelProtocolOutcome outcome = protocol.invoke(
                new ChainModelProtocolRequest(task.taskId(),
                        callIdentity.invocationId(),
                        callIdentity.contextRevisionId(),
                        complete.context().revision().completionToken(),
                        ChainRole.EXECUTOR, ChainWorkState.EXECUTING, callReason,
                        endpoint.providerKey(), endpoint.modelName(),
                        callIdentity.invocationOrdinal(),
                        null, now));
        if (failedExecution != null) {
            ProductChainExecutorActionContextProjection.Failure failure =
                    actionProjection.latestFailure();
            if (failure == null
                    || !Objects.equals(failure.actionId(), failedExecution.actionId())
                    || !Objects.equals(failure.errorRef(), failedExecution.errorRef())) {
                return ProductChainExecutorPump.Result.repairRejected(
                        null, "REPAIR_FORMAL_FAILURE_MISSING");
            }
            if (observedActionFailure
                    && outcome instanceof ChainModelProtocolOutcome
                    .ProposalReady ready) {
                if (ready.proposal().proposalKind()
                        == ChainProposalKind.EXECUTOR_STEP_BLOCKED) {
                    validateReceiptValidationFailure(
                            ProductChainPersistedProposalDecoder.decode(
                                    ready, ChainWorkState.EXECUTING, null),
                            failure);
                    return pump.execute(task.taskId(), outcome, now);
                }
                var decision = actionFailures.decide(
                        task, binding, activation, ready, failure,
                        "REPAIR_DID_NOT_CHANGE_ACTION", now);
                return ProductChainExecutorPump.Result
                        .actionFailureStepBlocked(
                                ready.proposal().proposalId(),
                                decision.stepBlockId());
            }
            String rejection = actionContext.validateRepair(outcome, failure);
            if (outcome instanceof ChainModelProtocolOutcome.ProposalReady ready
                    && (ready.proposal().proposalKind()
                    == ChainProposalKind.EXECUTOR_TOOL_ACTION
                    || ready.proposal().proposalKind()
                    == ChainProposalKind.EXECUTOR_WORKSPACE_CHANGE)
                    && (rejection == null
                    || "REPAIR_DID_NOT_CHANGE_ACTION".equals(rejection))) {
                var decision = actionFailures.decide(
                        task, binding, activation, ready, failure,
                        "REPAIR_DID_NOT_CHANGE_ACTION".equals(rejection)
                                ? rejection : null, now);
                if (decision.blocked()) {
                    return ProductChainExecutorPump.Result
                            .actionFailureStepBlocked(
                                    ready.proposal().proposalId(),
                                    decision.stepBlockId());
                }
            }
            if (rejection != null) {
                String proposalId = outcome instanceof ChainModelProtocolOutcome.ProposalReady ready
                        ? ready.proposal().proposalId() : null;
                return ProductChainExecutorPump.Result.repairRejected(
                        proposalId, rejection);
            }
        }
        if (outcome instanceof ChainModelProtocolOutcome.ProposalReady ready
                && ready.proposal().proposalKind()
                == io.paperagent.v2.chain.ChainProposalKind.EXECUTOR_STEP_RESULT) {
            if (candidateRequired(binding, step)
                    && currentCandidate == null) {
                return ProductChainExecutorPump.Result.repairRejected(
                        ready.proposal().proposalId(),
                        ProductChainExecutorPump.CANDIDATE_REQUIRED_CODE);
            }
            ChainValidationRuntime.CommitResult validation = commitValidation(
                    binding, activation, step, ready, now);
            pump.commitStepResult(task.taskId(), outcome,
                    new ProductChainExecutorPump.StepResultIdentity(
                            instruction.instructionId(), binding.taskFrameId(),
                            binding.planId(), binding.planRevisionId(),
                            binding.planRevisionNumber(), activation.command().stepId(),
                            activation.command().activationEventId(),
                            currentCandidate == null
                                    ? sha256("step-result\0" + task.taskId() + "\0"
                                    + activation.command().activationEventId())
                                    : currentCandidate.versionFenceSha256(),
                            currentCandidate == null ? null
                                    : currentCandidate.workspaceCandidateId(),
                            currentCandidate == null ? null
                                    : currentCandidate.artifactId(),
                             currentCandidate == null ? null
                                     : currentCandidate.candidateFingerprint(),
                             currentCandidate == null ? null
                                     : currentCandidate.diffDigest(),
                             firstReceipt(validation),
                             validation == null ? null
                                     : validation.validation().validationId(),
                             validation == null ? null
                                     : validation.validation().requestDigest(),
                             validation == null ? null
                                     : validation.validation().receiptSetDigest(),
                             candidateRequired(binding, step)), now);
            return ProductChainExecutorPump.Result.stepResultCommitted(
                    ready.proposal().proposalId());
        }
        return pump.execute(task.taskId(), outcome, now);
    }

    /** Invokes the Executor's real STEP_RESULT proposal after a candidate was committed. */
    public ChainPersistenceRecords.CandidateStepResultRecord advanceStepResult(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String body,
            ProductChainPlanTransitionDriver.Result transition,
            Instant now) {
        ChainPersistenceRecords.WorkspaceCandidateRecord candidate =
                currentWorkspaceCandidate(task.taskId());
        ChainStepAuthorityPort.StepEvent activation = activeActivation(
                task.taskId(), transition.planBinding().planRevisionId());
        ChainPersistenceRecords.PlanBindingRecord binding =
                transition.planBinding();
        List<ChainPersistenceRecords.ActionBindingRecord> candidateActions =
                candidate == null ? List.of() : workflow
                        .findActionBindings(task.taskId()).stream()
                        .filter(value -> value.actionId().equals(
                                candidate.actionId()))
                        .toList();
        if (candidateActions.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_SOURCE_ACTION_INVALID");
        }
        ChainPersistenceRecords.ActionBindingRecord candidateAction =
                candidateActions.get(0);
        if (!Objects.equals(session.getId(), task.sessionId())
                || !Objects.equals(session.getUserId(), task.userId())
                || !Objects.equals(session.getProjectId(), task.projectId())
                || !instruction.instructionId().equals(
                        binding.instructionId())
                || !candidate.taskId().equals(task.taskId())
                || !candidateAction.instructionId().equals(
                        instruction.instructionId())
                || !candidateAction.taskFrameId().equals(binding.taskFrameId())
                || !candidateAction.planId().equals(binding.planId())
                || !candidateAction.planRevisionId().equals(
                        binding.planRevisionId())
                || !candidateAction.stepId().equals(
                        activation.command().stepId())
                || !candidateAction.activationEventId().equals(
                        activation.command().activationEventId())
                || !candidateAction.workspaceId().equals(
                        candidate.workspaceId())
                || !candidateAction.versionFenceSha256().equals(
                        candidate.versionFenceSha256())) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_SOURCE_IDENTITY_INVALID");
        }
        PlanStep step = step(transition.planBinding(), activation.command().stepId());
        String workspaceId = "product-workspace." + sha256(
                "workspace\0AGENT_TURN:" + task.turnId());
        String candidateRef = candidate == null ? "NONE" : candidate.workspaceCandidateId();
        String stepBody = Objects.toString(body, "");
        String contextId = identity("context", task.taskId() + "\0"
                + activation.command().activationEventId() + "\0STEP_RESULT\0"
                + stepBody + "\0" + candidateRef + "\0" + Objects.toString(
                        null,
                        ChainIdentity.NONE));
        String invocationId = identity("invocation", contextId);
        ProductChainModelCallIdentity.Binding callIdentity =
                modelCallIdentity.bind(
                        task.taskId(), contextId, invocationId);
        ProductChainContextSourceAdapter source = contextSources.source();
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, source);
        ChainPersistenceRecords.PlanBindingRecord stepBinding = transition.planBinding();
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        callIdentity.contextRevisionId(), task.taskId(),
                        callIdentity.parentContextRevisionId(),
                        ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, "STEP_RESULT",
                        instruction.instructionId(), stepBinding.taskFrameId(),
                        stepBinding.planId(), stepBinding.planRevisionId(),
                        stepBinding.planRevisionNumber(), activation.command().stepId(),
                        activation.command().activationEventId(), task.projectId(),
                        task.initialProjectVersion(), workspaceId,
                        candidate == null ? null : candidate.artifactId(),
                        candidate == null ? null : candidate.candidateFingerprint(),
                        null, null, null,
                        "chain-product-projector-v1", "v1",
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId()).policyVersion(),
                        ChainContextRevisionStatus.BUILDING, 0, null, null, null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context.ChainContextFreezeRequest(
                        building, ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw new IllegalStateException("Executor STEP_RESULT context input is blocked");
        }
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                task.userId(), session.getModelProviderSnapshot(), session.getModelSnapshot());
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) -> {
            ProviderRoleOutput output = requireStepResultOutput(
                    new io.paperagent.v2.chain.model
                            .StrictChainProviderOutputParser()
                            .parse(raw, role, state, gap));
            validateExecutorStepResultValidationBindings(
                    output, step.validationRequirementIds());
            return output;
        };
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(models, models, models, transactions),
                new ProductChainChatModelAdapter(provider,
                        request -> new ProductChainModelEndpoint(endpoint.providerKey(),
                                endpoint.modelName(), endpoint.apiKey(), endpoint.apiUrl())),
                decoder);
        ChainModelProtocolOutcome outcome = protocol.invoke(new ChainModelProtocolRequest(
                task.taskId(), callIdentity.invocationId(),
                callIdentity.contextRevisionId(),
                complete.context().revision().completionToken(), ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "STEP_RESULT", endpoint.providerKey(),
                endpoint.modelName(), callIdentity.invocationOrdinal(), null,
                now));
        ChainValidationRuntime.CommitResult validation = outcome
                instanceof ChainModelProtocolOutcome.ProposalReady ready
                ? commitValidation(transition.planBinding(), activation,
                step, ready, now) : null;
        return pump.commitStepResult(task.taskId(), outcome,
                new ProductChainExecutorPump.StepResultIdentity(
                        instruction.instructionId(), transition.planBinding().taskFrameId(),
                        transition.planBinding().planId(), transition.planBinding().planRevisionId(),
                        transition.planBinding().planRevisionNumber(), activation.command().stepId(),
                        activation.command().activationEventId(), candidate == null
                                ? sha256("step-result\0" + task.taskId() + "\0"
                                + activation.command().activationEventId())
                                : candidate.versionFenceSha256(),
                        candidate == null ? null : candidate.workspaceCandidateId(),
                        candidate == null ? null : candidate.artifactId(),
                         candidate == null ? null : candidate.candidateFingerprint(),
                         candidate == null ? null : candidate.diffDigest(),
                         firstReceipt(validation),
                         validation == null ? null
                                 : validation.validation().validationId(),
                         validation == null ? null
                                 : validation.validation().requestDigest(),
                         validation == null ? null
                                 : validation.validation().receiptSetDigest(),
                         candidateRequired(transition.planBinding(), step)), now);
    }

    static ProviderRoleOutput requireStepResultOutput(
            ProviderRoleOutput output) {
        if (!(output.payload() instanceof ExecutorPayload.StepResult)) {
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.kind",
                    "this post-execution reporting call requires STEP_RESULT; "
                            + "do not request another action or workspace change");
        }
        return output;
    }

    static void validateReflectorCandidateReference(
            ProviderRoleOutput output, String expectedCandidateRef) {
        ReflectorPayload.AcceptStep acceptance =
                output.payload() instanceof ReflectorPayload.AcceptStep value
                        ? value
                        : output.payload() instanceof
                                ReflectorPayload.AcceptStepAndReadyToFinalize value
                                ? value.acceptance() : null;
        if (acceptance == null) {
            return;
        }
        if (!required(expectedCandidateRef, "expectedCandidateRef")
                .equals(acceptance.candidateRef())) {
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.payload.candidateRef",
                    "must exactly equal \"" + expectedCandidateRef
                            + "\" from the frozen Candidate binding; use the literal NONE "
                            + "when the reviewed result has no WorkspaceCandidate");
        }
    }

    private String exactWorkspaceCandidateRef(
            String taskId,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> matches =
                workflow.findWorkspaceCandidates(taskId).stream()
                        .filter(value -> candidate.artifactId() != null
                                && value.artifactId() == candidate.artifactId())
                        .filter(value -> value.candidateFingerprint().equals(
                                candidate.candidateFingerprint()))
                        .filter(value -> value.diffDigest().equals(
                                candidate.diffDigest()))
                        .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_WORKSPACE_CANDIDATE_MISSING_OR_AMBIGUOUS");
        }
        return matches.get(0).workspaceCandidateId();
    }

    /**
     * Invokes the Reflector for one exact candidate and persists only the
     * accepted proposal. Formal ReviewDecision consumption is a separate,
     * replayable boundary.
     */
    public ReflectorProposal invokeReflectorReview(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainPlanTransitionDriver.Result transition,
            String candidateResultId,
            Instant now) {
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates = workflow
                .findCandidateStepResults(task.taskId()).stream()
                .filter(value -> value.candidateResultId().equals(
                        required(candidateResultId, "candidateResultId")))
                .filter(value -> value.planRevisionId().equals(
                        transition.planBinding().planRevisionId()))
                .toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(candidates.isEmpty()
                    ? "CHAIN_REFLECTOR_CANDIDATE_MISSING"
                    : "CHAIN_REFLECTOR_CANDIDATE_AMBIGUOUS");
        }
        ChainPersistenceRecords.CandidateStepResultRecord candidate =
                candidates.get(0);
        ChainStepAuthorityPort.StepEvent activation = activeActivation(
                task.taskId(), transition.planBinding().planRevisionId());
        PlanStep step = step(transition.planBinding(), activation.command().stepId());
        var planSteps = formalPlanRevision(
                steps, transition.planBinding()).steps();
        boolean finalStep = !planSteps.isEmpty()
                && planSteps.get(planSteps.size() - 1).id().value().equals(
                activation.command().stepId());
        boolean formalPublishRequired = formalPublishRequired(
                transition.planBinding());
        boolean formalValidationRequired = formalValidationRequired(
                transition.planBinding());
        String candidateRef = candidate.candidateFingerprint() == null
                ? ChainIdentity.NONE : candidate.candidateFingerprint();
        String expectedAcceptanceCandidateRef = candidate.candidateFingerprint() == null
                ? ChainIdentity.NONE : exactWorkspaceCandidateRef(
                        task.taskId(), candidate);
        String body = candidateRef + "\0" + finalStep + "\0"
                + formalPublishRequired + "\0" + formalValidationRequired + "\0"
                + Objects.toString(candidate.validationId(), ChainIdentity.NONE);
        String workspaceId = "product-workspace." + sha256(
                "workspace\0AGENT_TURN:" + task.turnId());
        String contextId = identity("context", task.taskId() + "\0"
                + candidate.candidateResultId() + "\0REFLECTOR\0" + body);
        String invocationId = identity("invocation", contextId);
        ProductChainModelCallIdentity.Binding callIdentity =
                modelCallIdentity.bind(
                        task.taskId(), contextId, invocationId);
        Map<String, String> reflectorRefs = candidate.validationId() == null
                ? Map.of()
                : Map.of("validation", candidate.validationId());
        DefaultChainContextManager manager = new DefaultChainContextManager(
                contexts, contexts, contextSources.source());
        ChainPersistenceRecords.ContextRevisionRecord building =
                new ChainPersistenceRecords.ContextRevisionRecord(
                        callIdentity.contextRevisionId(), task.taskId(),
                        callIdentity.parentContextRevisionId(),
                        ChainRole.REFLECTOR,
                        ChainWorkState.AWAITING_REVIEW, "STEP_REVIEW",
                        instruction.instructionId(), transition.planBinding().taskFrameId(),
                        transition.planBinding().planId(), transition.planBinding().planRevisionId(),
                        transition.planBinding().planRevisionNumber(), activation.command().stepId(),
                        activation.command().activationEventId(), task.projectId(),
                        task.initialProjectVersion(), workspaceId, candidate.artifactId(),
                        candidate.candidateFingerprint(), candidate.validationId(),
                        candidate.validationRequestDigest(), candidate.validationReceiptDigest(),
                        "chain-product-projector-v1", "v1",
                        ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId()).policyVersion(),
                        ChainContextRevisionStatus.BUILDING, 0, null, null, null, null, null, now, null);
        ChainContextFreezeOutcome frozen = manager.freeze(
                new io.paperagent.v2.chain.context.ChainContextFreezeRequest(
                        building, ProductChainRuntimePolicySource.forTask(
                                contexts, task.taskId())
                                .contextRequestCharactersMax()));
        if (!(frozen instanceof ChainContextFreezeOutcome.Complete complete)) {
            throw new IllegalStateException("Reflector context input is blocked");
        }
        UserSettingsService.ModelEndpoint endpoint = settings.resolveModelEndpoint(
                task.userId(), session.getModelProviderSnapshot(), session.getModelSnapshot());
        ChainRoleOutputDecoder decoder = (raw, role, state, gap) -> {
            ProviderRoleOutput output =
                    new io.paperagent.v2.chain.model.StrictChainProviderOutputParser()
                            .parse(raw, role, state, gap);
            validateReflectorFinalizationAuthority(
                    output, finalStep,
                    formalValidationRequired, formalPublishRequired,
                    transition.planBinding().taskFrameId());
            validateReflectorCandidateReference(
                    output, expectedAcceptanceCandidateRef);
            return output;
        };
        ChainModelProtocolService protocol = new ChainModelProtocolService(
                manager, models, models,
                new ProductChainModelMaterializationAdapter(models, models, models, transactions),
                new ProductChainChatModelAdapter(provider, request ->
                        new ProductChainModelEndpoint(endpoint.providerKey(), endpoint.modelName(),
                                endpoint.apiKey(), endpoint.apiUrl())), decoder);
        ChainModelProtocolOutcome outcome = protocol.invoke(new ChainModelProtocolRequest(
                task.taskId(), callIdentity.invocationId(),
                callIdentity.contextRevisionId(),
                complete.context().revision().completionToken(), ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, "STEP_REVIEW", endpoint.providerKey(),
                endpoint.modelName(), callIdentity.invocationOrdinal(), null,
                now));
        if (!(outcome instanceof ChainModelProtocolOutcome.ProposalReady ready)
                || ready.proposal().role() != ChainRole.REFLECTOR) {
            throw new IllegalStateException("CHAIN_REFLECTOR_PROPOSAL_MISSING");
        }
        String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                + ready.proposal().proposalKind().wireName() + "\",\"payload\":"
                + ready.proposal().payload().json() + "}";
        ReflectorPayload payload = (ReflectorPayload) new io.paperagent.v2.chain.model.StrictChainProviderOutputParser()
                .parse(encoded, ChainRole.REFLECTOR, ChainWorkState.AWAITING_REVIEW, null).payload();
        ProductChainProposalAdmissionAdapter admission =
                new ProductChainProposalAdmissionAdapter(jdbc, transactions, models, models);
        admission.admit(new io.paperagent.v2.chain.model.ChainProposalAdmissionService.AdmissionRequest(
                ready.proposal().proposalId(), task.taskId(),
                identity("reflector-proposal-accepted", ready.proposal().proposalId()),
                true, null, ready.proposal().payload().sha256(), now));
        return new ReflectorProposal(ready.proposal().proposalId(),
                ready.proposal().proposalKind(), callIdentity.invocationId(),
                candidate.candidateResultId());
    }

    /** Compatibility composition for the synchronous product entrypoint. */
    public ChainReviewRuntime.CommitResult advanceReflectorReview(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainPlanTransitionDriver.Result transition,
            Instant now) {
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates =
                workflow.findCandidateStepResults(task.taskId()).stream()
                        .filter(value -> value.planRevisionId().equals(
                                transition.planBinding().planRevisionId()))
                        .filter(value -> value.stepId().equals(
                                activeActivation(task.taskId(),
                                        transition.planBinding()
                                                .planRevisionId())
                                        .command().stepId()))
                        .toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_CANDIDATE_MISSING_OR_AMBIGUOUS");
        }
        ReflectorProposal proposal = invokeReflectorReview(
                session, task, instruction, transition,
                candidates.get(0).candidateResultId(), now);
        return consumeAcceptedReflectorProposal(task, instruction,
                transition, proposal.proposalId(), now);
    }

    /**
     * Consumes one exact accepted Reflector proposal without another model
     * call. A proposal already bound to its exact ReviewDecision is recovered
     * read-only before any successor transition is resumed.
     */
    public ChainReviewRuntime.CommitResult consumeAcceptedReflectorProposal(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            String proposalId,
            Instant now) {
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_CONTEXT_MISSING"));
        List<ChainPersistenceRecords.PlanBindingRecord> bindings = workflow
                .findPlanBindings(task.taskId()).stream()
                .filter(value -> Objects.equals(value.taskFrameId(),
                        context.taskFrameId()))
                .filter(value -> Objects.equals(value.planId(),
                        context.planId()))
                .filter(value -> Objects.equals(value.planRevisionId(),
                        context.planRevisionId()))
                .filter(value -> Objects.equals(value.planRevisionNumber(),
                        context.planRevisionNumber()))
                .toList();
        if (bindings.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_PLAN_BINDING_INVALID");
        }
        ChainPersistenceRecords.PlanBindingRecord binding = bindings.get(0);
        List<ChainPersistenceRecords.TransitionStageRecord> complete = workflow
                .findTransitionStages(binding.transitionId()).stream()
                .filter(value -> value.stageCode()
                        == ChainTransitionStage.COMPLETE)
                .filter(value -> value.taskId().equals(task.taskId()))
                .toList();
        if (complete.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_PLAN_TRANSITION_INCOMPLETE");
        }
        ProductChainPlanTransitionDriver.Result transition =
                new ProductChainPlanTransitionDriver.Result(
                        binding.transitionId(), binding,
                        complete.get(0).eventId(),
                        latestActivation(task.taskId(),
                                binding.planRevisionId()));
        return consumeAcceptedReflectorProposal(task, instruction,
                transition, proposalId, now);
    }

    public ChainReviewRuntime.CommitResult consumeAcceptedReflectorProposal(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainPlanTransitionDriver.Result transition,
            String proposalId,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(now, "now");
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(required(proposalId, "proposalId"))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_PROPOSAL_MISSING"));
        ChainPersistenceRecords.ModelInvocationRecord invocation = models
                .findInvocation(proposal.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_INVOCATION_MISSING"));
        ChainPersistenceRecords.ContextRevisionRecord context = contexts
                .findContextRevision(invocation.contextRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_CONTEXT_MISSING"));
        validateReflectorInvocationIdentity(task, instruction, transition,
                proposal, invocation, context);
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                acceptedReflectorStatePrefix(proposal,
                        models.findProposalStateEvents(proposal.proposalId()));
        ReflectorPayload payload = decodeReflectorProposal(proposal);
        ChainPersistenceRecords.CandidateStepResultRecord candidate =
                exactReflectorCandidate(task, context, payload);
        boolean finalStep = isFinalStep(transition, context.stepId());
        boolean publishRequired = formalPublishRequired(
                transition.planBinding());
        boolean validationRequired = formalValidationRequired(
                transition.planBinding());
        String candidateRef = candidate.candidateFingerprint() == null
                ? ChainIdentity.NONE : candidate.candidateFingerprint();
        String expectedBody = candidateRef + "\0" + finalStep + "\0"
                + publishRequired + "\0" + validationRequired + "\0"
                + Objects.toString(candidate.validationId(),
                        ChainIdentity.NONE);
        String expectedContextId = identity("context", task.taskId() + "\0"
                + candidate.candidateResultId() + "\0REFLECTOR\0"
                + expectedBody);
        if (!context.contextRevisionId().equals(expectedContextId)
                || !invocation.invocationId().equals(
                        identity("invocation", expectedContextId))) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_CONTEXT_SELECTOR_INVALID");
        }
        validateReflectorFinalizationAuthority(
                new ProviderRoleOutput("1", payload.kind().wireName(), payload),
                finalStep,
                validationRequired, publishRequired,
                transition.planBinding().taskFrameId());
        String reviewObjectId = payload instanceof ReflectorPayload.AcceptStep accept
                ? accept.candidateResultId()
                : payload instanceof ReflectorPayload.AcceptStepAndReadyToFinalize accept
                ? accept.acceptance().candidateResultId()
                : candidate.candidateResultId();

        ChainReviewRuntime.CommitResult committed;
        if (states.size() == 2) {
            String decisionId = states.get(1).officialAuthorityRef();
            List<ChainPersistenceRecords.ReviewDecisionRecord> decisions =
                    workflow.findReviewDecisions(task.taskId()).stream()
                            .filter(value -> value.reviewDecisionId().equals(
                                    decisionId))
                            .filter(value -> value.proposalId().equals(
                                    proposal.proposalId()))
                            .filter(value -> value.reviewObjectType().equals(
                                    "CANDIDATE_STEP_RESULT"))
                            .filter(value -> value.reviewObjectId().equals(
                                    reviewObjectId))
                            .filter(value -> value.decisionKind()
                                    == proposal.proposalKind())
                            .filter(value -> value.versionFenceSha256().equals(
                                    candidate.versionFenceSha256()))
                            .toList();
            if (decisions.size() != 1) {
                throw new IllegalStateException(
                        "CHAIN_REFLECTOR_BOUND_REVIEW_INVALID");
            }
            committed = new ChainReviewRuntime.CommitResult(decisions.get(0),
                    true, reflectorSuccessor(payload));
        } else {
            ProductChainProposalAdmissionAdapter admission =
                    new ProductChainProposalAdmissionAdapter(
                            jdbc, transactions, models, models);
            ChainReviewRuntime runtime = new ChainReviewRuntime(
                    workflow, workflow,
                    ignored -> new ChainReviewRuntime.FormalReviewProposal(
                            proposal, states.get(0), payload,
                            candidate.versionFenceSha256()),
                    (ignoredTask, ignoredProposal, type, ref) ->
                            admission.replaceByOfficialResult(
                                    new io.paperagent.v2.chain.model
                                            .ChainProposalAdmissionService
                                            .OfficialReplacement(
                                            proposal.proposalId(), task.taskId(),
                                            identity("review-bound", ref),
                                            ChainPersistenceRecords
                                                    .ProposalOfficialAuthorityType
                                                    .REVIEW_DECISION,
                                            ref, null,
                                            proposal.payload().sha256(), now)));
            committed = runtime.commit(new ChainReviewRuntime.CommitRequest(
                    task.taskId(), proposal.proposalId(),
                    identity("review-event", proposal.proposalId()),
                    "CANDIDATE_STEP_RESULT", reviewObjectId, now));
        }
        if (payload.kind() == io.paperagent.v2.chain.ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE) {
            acceptStepAndCommitReadiness(task, transition, committed, payload, now);
        } else if (payload.kind() == io.paperagent.v2.chain.ChainProposalKind
                .REFLECTOR_ACCEPT_STEP) {
            acceptStepAndActivateNext(task, transition, committed, payload, now);
        }
        return committed;
    }

    private void validateReflectorInvocationIdentity(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            ProductChainPlanTransitionDriver.Result transition,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        ChainPersistenceRecords.PlanBindingRecord binding =
                transition.planBinding();
        if (!proposal.taskId().equals(task.taskId())
                || proposal.role() != ChainRole.REFLECTOR
                || proposal.proposalKind().role() != ChainRole.REFLECTOR
                || !proposal.invocationId().equals(invocation.invocationId())
                || !invocation.taskId().equals(task.taskId())
                || invocation.role() != ChainRole.REFLECTOR
                || invocation.workState() != ChainWorkState.AWAITING_REVIEW
                || !"STEP_REVIEW".equals(invocation.callReason())
                || !context.contextRevisionId().equals(
                        invocation.contextRevisionId())
                || !context.taskId().equals(task.taskId())
                || context.role() != ChainRole.REFLECTOR
                || context.workState() != ChainWorkState.AWAITING_REVIEW
                || !"STEP_REVIEW".equals(context.callReason())
                || context.status() != ChainContextRevisionStatus.COMPLETE
                || !context.instructionId().equals(
                        instruction.instructionId())
                || !Objects.equals(context.taskFrameId(),
                        binding.taskFrameId())
                || !Objects.equals(context.planId(), binding.planId())
                || !Objects.equals(context.planRevisionId(),
                        binding.planRevisionId())
                || !Objects.equals(context.planRevisionNumber(),
                        binding.planRevisionNumber())
                || !Objects.equals(context.projectId(), task.projectId())
                || !Objects.equals(context.projectVersion(),
                        task.initialProjectVersion())
                || context.stepId() == null
                || context.activationEventId() == null
                || proposal.bodyAuthorityType() != null
                || proposal.bodyAuthorityRef() != null) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_INVOCATION_IDENTITY_INVALID");
        }
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            acceptedReflectorStatePrefix(
                    ChainPersistenceRecords.ModelProposalRecord proposal,
                    List<ChainPersistenceRecords.ProposalStateEventRecord>
                            events) {
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = events
                .stream().sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        if (states.isEmpty() || states.size() > 2) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_PROPOSAL_STATE_INVALID");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(proposal.taskId())
                    || !state.proposalId().equals(proposal.proposalId())
                    || state.stateSequence() != index + 1L) {
                throw new IllegalStateException(
                        "CHAIN_REFLECTOR_PROPOSAL_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "CHAIN_REFLECTOR_PROPOSAL_STATE_INVALID", invalid);
            }
            prefix.add(state.stateKind());
        }
        if (states.get(0).stateKind() != ChainProposalState.ACCEPTED
                || states.get(0).officialAuthorityType() != null
                || states.get(0).officialAuthorityRef() != null
                || (states.size() == 2
                && !"REVIEW_DECISION".equals(
                        states.get(1).officialAuthorityType()))) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_PROPOSAL_NOT_ACCEPTED_OR_BOUND_ELSEWHERE");
        }
        return states;
    }

    private ReflectorPayload decodeReflectorProposal(
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        String encoded = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        return (ReflectorPayload) new StrictChainProviderOutputParser().parse(
                encoded, ChainRole.REFLECTOR,
                ChainWorkState.AWAITING_REVIEW, null).payload();
    }

    private ChainPersistenceRecords.CandidateStepResultRecord
            exactReflectorCandidate(
                    ChainPersistenceRecords.TaskRecord task,
                    ChainPersistenceRecords.ContextRevisionRecord context,
                    ReflectorPayload payload) {
        String acceptedId = payload instanceof ReflectorPayload.AcceptStep value
                ? value.candidateResultId()
                : payload instanceof ReflectorPayload
                        .AcceptStepAndReadyToFinalize value
                ? value.acceptance().candidateResultId() : null;
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates =
                workflow.findCandidateStepResults(task.taskId()).stream()
                        .filter(value -> acceptedId == null
                                || value.candidateResultId().equals(acceptedId))
                        .filter(value -> value.planRevisionId().equals(
                                context.planRevisionId()))
                        .filter(value -> value.stepId().equals(context.stepId()))
                        .filter(value -> value.activationEventId().equals(
                                context.activationEventId()))
                        .filter(value -> Objects.equals(value.artifactId(),
                                context.candidateArtifactId()))
                        .filter(value -> Objects.equals(
                                value.candidateFingerprint(),
                                context.candidateFingerprint()))
                        .filter(value -> Objects.equals(value.validationId(),
                                context.validationId()))
                        .filter(value -> Objects.equals(
                                value.validationRequestDigest(),
                                context.validationRequestDigest()))
                        .filter(value -> Objects.equals(
                                value.validationReceiptDigest(),
                                context.validationReceiptDigest()))
                        .filter(value -> payload.review().reviewedObjectRefs()
                                .contains(value.candidateResultId()))
                        .toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_CANDIDATE_IDENTITY_INVALID");
        }
        return candidates.get(0);
    }

    private boolean isFinalStep(
            ProductChainPlanTransitionDriver.Result transition,
            String stepId) {
        ChainStepAuthorityPort.PlanSnapshot plan = steps.findPlan(
                        transition.planBinding().taskId(),
                        transition.planBinding().planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_REFLECTOR_PLAN_MISSING"));
        int maxOrder = plan.steps().stream().mapToInt(
                ChainStepAuthorityPort.StepDefinition::stableOrder).max()
                .orElseThrow();
        return plan.steps().stream().anyMatch(value ->
                value.stepId().equals(stepId)
                        && value.stableOrder() == maxOrder);
    }

    private static ChainReviewRuntime.SuccessorRequirement reflectorSuccessor(
            ReflectorPayload payload) {
        return switch (payload.kind()) {
            case REFLECTOR_CONTINUE_STEP ->
                    ChainReviewRuntime.SuccessorRequirement.STEP_CONTINUATION;
            case REFLECTOR_ACCEPT_STEP ->
                    ChainReviewRuntime.SuccessorRequirement
                            .ACCEPTED_RESULT_AND_STEP;
            case REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE ->
                    ChainReviewRuntime.SuccessorRequirement
                            .ACCEPTED_RESULT_STEP_AND_READINESS;
            case REFLECTOR_REPLAN_REQUIRED ->
                    ChainReviewRuntime.SuccessorRequirement.PLAN_REVISION;
            case REFLECTOR_NEED_USER_INPUT ->
                    ChainReviewRuntime.SuccessorRequirement.USER_PENDING_ITEM;
            case REFLECTOR_NEED_PERMISSION ->
                    ChainReviewRuntime.SuccessorRequirement
                            .PERMISSION_PENDING_ITEM;
            case REFLECTOR_READY_TO_FINALIZE ->
                    ChainReviewRuntime.SuccessorRequirement.STEP_READINESS;
            case REFLECTOR_TASK_FAILED ->
                    ChainReviewRuntime.SuccessorRequirement.FAILED_TASK_OUTCOME;
            default -> throw new IllegalStateException(
                    "CHAIN_REFLECTOR_PROPOSAL_KIND_INVALID");
        };
    }

    /** Commits a non-final acceptance and activates the next formally READY Step. */
    public ChainStepStateMachine.ActivationOutcome acceptStepAndActivateNext(
            ChainPersistenceRecords.TaskRecord task,
            ProductChainPlanTransitionDriver.Result transition,
            ChainReviewRuntime.CommitResult reviewResult,
            ReflectorPayload payload,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(reviewResult, "reviewResult");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(now, "now");
        if (!(payload instanceof ReflectorPayload.AcceptStep accept)
                || payload.kind() != io.paperagent.v2.chain.ChainProposalKind.REFLECTOR_ACCEPT_STEP
                || reviewResult.decision().decisionKind()
                != io.paperagent.v2.chain.ChainProposalKind.REFLECTOR_ACCEPT_STEP) {
            throw new IllegalStateException("CHAIN_ACCEPT_STEP_REVIEW_REQUIRED");
        }
        ChainPersistenceRecords.CandidateStepResultRecord candidate = workflow
                .findCandidateStepResults(task.taskId()).stream()
                .filter(value -> value.candidateResultId().equals(accept.candidateResultId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ACCEPT_STEP_CANDIDATE_MISSING"));
        ChainPersistenceRecords.PlanBindingRecord binding = transition.planBinding();
        String targetCandidateKey = steps.findPlan(task.taskId(), binding.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_ACCEPT_STEP_PLAN_MISSING"))
                .targetCandidateKey();
        String acceptedIdentity = sha256(candidate.candidateResultId() + "\0"
                + reviewResult.decision().reviewDecisionId() + "\0" + candidate.contentId());
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.ACCEPT_STEP, task.taskId(),
                reviewResult.decision().reviewDecisionId(), acceptedIdentity).transitionId();
        ChainStepResultRuntime resultRuntime = new ChainStepResultRuntime(
                models, contexts, workflow, workflow, workflow, bindingRequest -> null);
        ChainStepStateMachine machine = new ChainStepStateMachine(
                steps, workflow, foundations, models, contexts);
        ChainApplicabilityRuntime applicability = new ChainApplicabilityRuntime(
                workflow, workflow, query -> {
                    if (query.sourceType() != ChainApplicability.SourceType.ACCEPT_STEP
                            || !query.sourceDecisionId().equals(transitionId)) {
                        throw new IllegalStateException("CHAIN_ACCEPT_STEP_APPLICABILITY_SOURCE_INVALID");
                    }
                    var formal = workflow.findTransition(transitionId).orElseThrow(
                            () -> new IllegalStateException("CHAIN_ACCEPT_STEP_TRANSITION_MISSING"));
                    if (formal.transitionType() != ChainTransitionType.ACCEPT_STEP
                            || !formal.taskId().equals(task.taskId())) {
                        throw new IllegalStateException("CHAIN_ACCEPT_STEP_TRANSITION_INVALID");
                    }
                    return new io.paperagent.v2.chain.transition.ChainApplicabilityAuthorityPort.SourceAuthority(
                            query.sourceType(), query.sourceDecisionId(), query.targetIdentity(),
                            transitionId, true);
                });
        ChainReadinessAuthorityPort readinessAuthority = query -> {
            throw new IllegalStateException("CHAIN_ACCEPT_STEP_READINESS_UNEXPECTED");
        };
        ChainStepRuntime stepRuntime = new ChainStepRuntime(
                machine, workflow, finalization, finalization,
                readinessAuthority, authorityGate);
        String completedEventId = "step.completed." + sha256(
                task.taskId() + "\0" + binding.planRevisionId() + "\0"
                        + candidate.stepId() + "\0" + candidate.activationEventId()
                        + "\0" + transitionId);
        AcceptStepHolder holder = new AcceptStepHolder(completedEventId);
        ChainCompositeTransitionRuntime transitions = new ChainCompositeTransitionRuntime(
                workflow, workflow, query -> verifyAcceptStepStage(query, holder, candidate));
        ChainCompositeTransitionRuntime.TransitionRequest request =
                new ChainCompositeTransitionRuntime.TransitionRequest(
                        ChainTransitionType.ACCEPT_STEP, task.taskId(),
                        reviewResult.decision().reviewDecisionId(), acceptedIdentity,
                        ChainCompositeTransitionRuntime.Branch.STANDARD, now);
        ChainCompositeTransitionRuntime.RecoveryOutcome outcome = transitions.resume(request, command -> {
            return switch (command.stage()) {
                case ACCEPTED_RESULT_COMMITTED -> {
                    AcceptedResultRecord acceptedResult = new AcceptedResultRecord(
                            "accepted." + sha256(candidate.candidateResultId() + "\0"
                                    + reviewResult.decision().reviewDecisionId()),
                            task.taskId(), "accepted-event." + sha256(transitionId),
                            candidate.candidateResultId(), reviewResult.decision().reviewDecisionId(),
                            transitionId, candidate.contentId(), acceptedIdentity, now);
                    resultRuntime.accept(acceptedResult);
                    holder.acceptedId = acceptedResult.acceptedResultId();
                    yield ChainCompositeTransitionRuntime.StageCommitResult.successor(
                            "ACCEPTED_RESULT", acceptedResult.acceptedResultId());
                }
                case APPLICABILITY_COMMITTED -> {
                    ChainApplicability.Identity identity = new ChainApplicability.Identity(
                             holder.acceptedId, ChainApplicability.SourceType.ACCEPT_STEP,
                             transitionId, binding.taskFrameId(), binding.planId(),
                             binding.planRevisionId(), targetCandidateKey,
                             binding.instructionId());
                    var fact = applicability.commit(new ChainApplicabilityRuntime.CommitRequest(
                            task.taskId(), identity, ChainApplicability.Outcome.APPLICABLE,
                            "accepted Step result applies to the current Plan revision", now)).fact();
                    holder.applicabilityId = fact.applicabilityId();
                    yield ChainCompositeTransitionRuntime.StageCommitResult.successor(
                            "RESULT_APPLICABILITY", fact.applicabilityId());
                }
                case STEP_COMPLETED -> completeStepAndBindEvent(stepRuntime, task, binding,
                        candidate, reviewResult, transitionId, completedEventId, now);
                case NEXT_STEP_ACTIVATED_OR_NONE -> {
                    ChainStepStateMachine.ActivationOutcome activated = stepRuntime.activateNext(
                            task.taskId(), binding.planRevisionId(),
                            reviewResult.decision().reviewDecisionId(), transitionId, now);
                    holder.activation = activated;
                    if (activated.kind() == ChainStepStateMachine.ActivationKind.ACTIVATED) {
                        yield ChainCompositeTransitionRuntime.StageCommitResult.successor(
                                "STEP_EVENT", activated.append().value().command().eventId());
                    }
                    if (activated.kind() == ChainStepStateMachine.ActivationKind.NO_STEP) {
                        yield ChainCompositeTransitionRuntime.StageCommitResult.none();
                    }
                    throw new IllegalStateException("CHAIN_ACCEPT_STEP_NEXT_ALREADY_ACTIVE");
                }
                case OPEN, COMPLETE -> throw new IllegalStateException("CHAIN_ACCEPT_STEP_STAGE_UNSUPPORTED");
                default -> throw new IllegalStateException("CHAIN_ACCEPT_STEP_STAGE_INVALID");
            };
        });
        if (!outcome.complete()) {
            throw new IllegalStateException("CHAIN_ACCEPT_STEP_TRANSITION_INCOMPLETE");
        }
        return holder.activation == null
                ? stepRuntime.activateNext(task.taskId(), binding.planRevisionId(),
                reviewResult.decision().reviewDecisionId(), transitionId, now)
                : holder.activation;
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyAcceptStepStage(
            ChainCompositeTransitionRuntime.StageAuthorityQuery query,
            AcceptStepHolder holder,
            ChainPersistenceRecords.CandidateStepResultRecord candidate) {
        String type = query.stage().successorAuthorityType();
        String ref = query.stage().successorAuthorityRef();
        if (query.stage().stageCode() == ChainTransitionStage.NEXT_STEP_ACTIVATED_OR_NONE
                && type == null && ref == null) {
            ChainStepStateMachine.PlanState state = new ChainStepStateMachine(
                    steps, workflow, foundations, models, contexts).derive(
                    query.transition().taskId(), candidate.planRevisionId());
            boolean noSuccessor = state.activeStep().isEmpty()
                    && state.steps().stream().noneMatch(value ->
                    value.status() == io.paperagent.v2.chain.ChainStepStatus.READY);
            return noSuccessor
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verifiedEmpty()
                    : fail("CHAIN_ACCEPT_STEP_NEXT_STEP_MISSING");
        }
        if ("ACCEPTED_RESULT".equals(type)) {
            return workflow.findAcceptedResults(query.transition().taskId()).stream()
                    .anyMatch(value -> value.acceptedResultId().equals(ref))
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : fail("CHAIN_ACCEPT_STEP_ACCEPTED_RESULT_MISSING");
        }
        if ("RESULT_APPLICABILITY".equals(type)) {
            return workflow.findApplicabilityDecisions(query.transition().taskId()).stream()
                    .anyMatch(value -> value.applicabilityId().equals(ref)
                            && value.sourceType() == ChainApplicability.SourceType.ACCEPT_STEP
                            && value.sourceDecisionId().equals(query.transition().transitionId())
                            && value.conclusion() == ChainApplicability.Outcome.APPLICABLE)
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : fail("CHAIN_ACCEPT_STEP_APPLICABILITY_MISSING");
        }
        if ("STEP_EVENT".equals(type)) {
            ChainStepAuthorityPort.StepEventKind expectedKind =
                    query.stage().stageCode() == ChainTransitionStage.STEP_COMPLETED
                            ? ChainStepAuthorityPort.StepEventKind.COMPLETED
                            : query.stage().stageCode()
                            == ChainTransitionStage.NEXT_STEP_ACTIVATED_OR_NONE
                            ? ChainStepAuthorityPort.StepEventKind.ACTIVATED : null;
            return workflow.findPlanBindings(query.transition().taskId()).stream()
                    .flatMap(value -> steps.findStepEvents(query.transition().taskId(), value.planRevisionId()).stream())
                    .anyMatch(value -> value.command().eventId().equals(ref)
                            && value.command().eventKind() == expectedKind
                            && value.command().transitionId().equals(query.transition().transitionId()))
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : fail("CHAIN_ACCEPT_STEP_COMPLETION_MISSING");
        }
        return ChainCompositeTransitionRuntime.AuthorityVerification.verifiedEmpty();
    }

    private static final class AcceptStepHolder {
        private final String stepEventId;
        private String acceptedId;
        private String applicabilityId;
        private ChainStepStateMachine.ActivationOutcome activation;
        private AcceptStepHolder(String stepEventId) { this.stepEventId = stepEventId; }
    }

    /**
     * Wires the combined accepting ReviewDecision to the existing formal
     * result/readiness authorities.  This method deliberately refuses to
     * synthesize a completed Step: the product Step adapter must expose a
     * formal terminal completion first.
     */
    public ChainPersistenceRecords.FinalizationReadinessRecord
            acceptStepAndCommitReadiness(
                    ChainPersistenceRecords.TaskRecord task,
                    ProductChainPlanTransitionDriver.Result transition,
                    ChainReviewRuntime.CommitResult reviewResult,
                    ReflectorPayload payload,
                    Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(reviewResult, "reviewResult");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(now, "now");
        if (payload.kind() != io.paperagent.v2.chain.ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE
                || reviewResult.decision().decisionKind()
                != io.paperagent.v2.chain.ChainProposalKind
                .REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE) {
            throw new IllegalStateException(
                    "CHAIN_READINESS_REVIEW_NOT_COMBINED_ACCEPTANCE");
        }
        ReflectorPayload.AcceptStepAndReadyToFinalize combined =
                (ReflectorPayload.AcceptStepAndReadyToFinalize) payload;
        ChainPersistenceRecords.CandidateStepResultRecord candidate = workflow
                .findCandidateStepResults(task.taskId()).stream()
                .filter(value -> value.candidateResultId().equals(
                        reviewResult.decision().reviewObjectId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "CHAIN_READINESS_CANDIDATE_MISSING"));
        ChainPersistenceRecords.PlanBindingRecord binding = transition.planBinding();
        String acceptedIdentity = sha256(
                candidate.candidateResultId() + "\0"
                        + reviewResult.decision().reviewDecisionId() + "\0"
                        + candidate.contentId());
        String transitionId = new ChainIdentity.Transition(
                ChainTransitionType.FINAL_STEP_READINESS, task.taskId(),
                reviewResult.decision().reviewDecisionId(), acceptedIdentity)
                .transitionId();
        ChainStepResultRuntime resultRuntime = new ChainStepResultRuntime(
                models, contexts, workflow, workflow, workflow,
                bindingRequest -> null);
        ChainStepStateMachine machine = new ChainStepStateMachine(
                steps, workflow, foundations, models, contexts);
        ChainReadinessAuthorityPort readinessAuthority = query -> readinessMaterial(
                task, binding, candidate, reviewResult, combined,
                acceptedIdentity, now);
        ChainStepRuntime stepRuntime = new ChainStepRuntime(
                machine, workflow, finalization, finalization,
                readinessAuthority, authorityGate);
        String completedEventId = "step.completed." + sha256(
                task.taskId() + "\0" + binding.planRevisionId() + "\0"
                        + candidate.stepId() + "\0" + candidate.activationEventId()
                        + "\0" + transitionId);
        Holder holder = new Holder(completedEventId);
        ChainCompositeTransitionRuntime transitions =
                new ChainCompositeTransitionRuntime(workflow, workflow,
                        query -> verifyReadinessStage(query, holder));
        ChainCompositeTransitionRuntime.TransitionRequest readinessRequest =
                new ChainCompositeTransitionRuntime.TransitionRequest(
                        ChainTransitionType.FINAL_STEP_READINESS, task.taskId(),
                        reviewResult.decision().reviewDecisionId(), acceptedIdentity,
                        ChainCompositeTransitionRuntime.Branch.STANDARD, now);
        ChainCompositeTransitionRuntime.RecoveryOutcome outcome;
        try {
            outcome = transitions.resume(readinessRequest, command -> {
                    return switch (command.stage()) {
                        case ACCEPTED_RESULT_COMMITTED_OR_VERIFIED -> {
                            AcceptedResultRecord accepted = new AcceptedResultRecord(
                                    "accepted." + sha256(candidate.candidateResultId()
                                            + "\0" + reviewResult.decision().reviewDecisionId()),
                                    task.taskId(),
                                    identity("accepted-event", transitionId),
                                    candidate.candidateResultId(),
                                    reviewResult.decision().reviewDecisionId(),
                                    transitionId, candidate.contentId(), acceptedIdentity, now);
                            resultRuntime.accept(accepted);
                            holder.acceptedId = accepted.acceptedResultId();
                            yield ChainCompositeTransitionRuntime.StageCommitResult
                                    .successor("ACCEPTED_RESULT", accepted.acceptedResultId());
                        }
                        case APPLICABILITY_COMMITTED_OR_EMPTY ->
                                ChainCompositeTransitionRuntime.StageCommitResult.none();
                        case STEP_COMPLETED_OR_VERIFIED ->
                                completeStepAndBindEvent(stepRuntime, task, binding,
                                        candidate, reviewResult, transitionId,
                                        holder.stepEventId, now);
                        case READINESS_COMMITTED -> {
                            if (!holder.allowReadiness) {
                                throw new IllegalStateException(
                                        "CHAIN_READINESS_STAGE_DEFERRED_AFTER_STEP_COMPLETION");
                            }
                            ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                                    stepRuntime.commitReadiness(
                                            new ChainStepRuntime.ReadinessCommand(
                                                    task.taskId(), transitionId,
                                                    reviewResult.decision().reviewDecisionId(), now))
                                            .fact();
                            holder.readinessId = readiness.readinessId();
                            yield ChainCompositeTransitionRuntime.StageCommitResult
                                    .successor("FINALIZATION_READINESS", readiness.readinessId());
                        }
                        case OPEN, COMPLETE ->
                                throw new IllegalStateException(
                                        "CHAIN_READINESS_STAGE_NOT_COMMITTED");
                        default -> throw new IllegalStateException(
                                "CHAIN_READINESS_STAGE_UNSUPPORTED");
                    };
                });
        } catch (IllegalStateException deferred) {
            if (!"CHAIN_READINESS_STAGE_DEFERRED_AFTER_STEP_COMPLETION"
                    .equals(deferred.getMessage())) {
                throw deferred;
            }
            holder.allowReadiness = true;
            outcome = transitions.resume(readinessRequest, command -> {
                if (command.stage() != ChainTransitionStage.READINESS_COMMITTED) {
                    throw new IllegalStateException("CHAIN_READINESS_REPLAY_STAGE_INVALID");
                }
                ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                        stepRuntime.commitReadiness(new ChainStepRuntime.ReadinessCommand(
                                task.taskId(), transitionId,
                                reviewResult.decision().reviewDecisionId(), now)).fact();
                holder.readinessId = readiness.readinessId();
                return ChainCompositeTransitionRuntime.StageCommitResult
                        .successor("FINALIZATION_READINESS", readiness.readinessId());
            });
        }
        if (holder.readinessId == null) {
            holder.readinessId = outcome.committedStages().stream()
                    .filter(stage -> stage.stageCode()
                            == ChainTransitionStage.READINESS_COMMITTED)
                    .map(ChainPersistenceRecords.TransitionStageRecord
                            ::successorAuthorityRef)
                    .findFirst().orElse(null);
        }
        if (!outcome.complete() || holder.readinessId == null) {
            throw new IllegalStateException("CHAIN_READINESS_TRANSITION_INCOMPLETE");
        }
        return finalization.findReadinessById(holder.readinessId).orElseThrow();
    }

    private static ChainCompositeTransitionRuntime.StageCommitResult
            completeStepAndBindEvent(
                    ChainStepRuntime stepRuntime,
                    ChainPersistenceRecords.TaskRecord task,
                    ChainPersistenceRecords.PlanBindingRecord binding,
                    ChainPersistenceRecords.CandidateStepResultRecord candidate,
                    ChainReviewRuntime.CommitResult review,
                    String transitionId,
                    String eventId,
                    Instant now) {
        stepRuntime.completeAcceptedStep(new ChainStepStateMachine.StepTerminalCommand(
                task.taskId(), binding.planRevisionId(), candidate.stepId(),
                candidate.activationEventId(), review.decision().reviewDecisionId(),
                transitionId, now));
        return ChainCompositeTransitionRuntime.StageCommitResult
                .successor("STEP_EVENT", eventId);
    }

    private ChainReadinessAuthorityPort.VerifiedReadinessMaterial readinessMaterial(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainPersistenceRecords.CandidateStepResultRecord candidate,
            ChainReviewRuntime.CommitResult review,
            ReflectorPayload.AcceptStepAndReadyToFinalize combined,
            String acceptedIdentity,
            Instant now) {
        var finalRevision = steps.findPlanRevision(
                        task.taskId(), binding.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_READINESS_PLAN_REVISION_MISSING"));
        String acceptedId = "accepted." + sha256(candidate.candidateResultId()
                + "\0" + review.decision().reviewDecisionId());
        List<String> acceptedIds = List.of(acceptedId);
        CanonicalJson acceptedSet = canonicalArray(acceptedIds);
        boolean publishRequired = formalPublishRequired(binding);
        boolean validationRequired = formalValidationRequired(binding);
        var publishAssessment = combined.finalization()
                .publishRequirementAssessment();
        boolean modelPublishMatches = publishRequired
                ? publishAssessment.status()
                        == io.paperagent.v2.chain.ProposalFields.AssessmentStatus.BOUND
                        && binding.taskFrameId().equals(
                                publishAssessment.authorityRef())
                : publishAssessment.status()
                        == io.paperagent.v2.chain.ProposalFields.AssessmentStatus.NOT_REQUIRED;
        if (!modelPublishMatches) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_PUBLISH_REQUIREMENT_MISMATCH");
        }
        var validationAssessment = combined.finalization()
                .validationAssessment();
        boolean modelValidationMatches = validationRequired
                ? validationAssessment.status()
                        == io.paperagent.v2.chain.ProposalFields.AssessmentStatus.BOUND
                        && binding.taskFrameId().equals(
                                validationAssessment.authorityRef())
                : !validationRequired && validationAssessment.status()
                        == io.paperagent.v2.chain.ProposalFields.AssessmentStatus.NOT_REQUIRED;
        if (!modelValidationMatches) {
            throw new IllegalStateException(
                    "CHAIN_REFLECTOR_VALIDATION_REQUIREMENT_MISMATCH");
        }
        ChainPublishRequirement publish = publishRequired
                ? ChainPublishRequirement.REQUIRED
                : ChainPublishRequirement.NOT_REQUIRED;
        var bundleOutcome = validationBundles.build(
                new ProductChainValidationBundleAuthority.BuildCommand(
                        task, binding, finalRevision, candidate.stepId(),
                        identity("validation-bundle", task.taskId() + "\0"
                                + finalRevision.id().value()), now));
        ReadinessValidationBundle bundle = readinessValidationBundle(
                bundleOutcome);
        String candidateKey = workspaceCandidateKey(task.taskId());
        String workspaceId = workflow.findWorkspaceCandidates(task.taskId())
                .stream()
                .filter(value -> value.workspaceCandidateId().equals(candidateKey))
                .map(ChainPersistenceRecords.WorkspaceCandidateRecord::workspaceId)
                .findFirst()
                .orElse(ChainIdentity.NONE);
        return new ChainReadinessAuthorityPort.VerifiedReadinessMaterial(
                binding.taskFrameId(), binding.planId(), finalRevision.id().value(),
                finalRevision.number(), candidate.stepId(),
                candidate.activationEventId(), acceptedIds, acceptedSet, 0L,
                candidate.artifactId(), candidateKey,
                workspaceId, bundle.validationId(),
                bundle.requestDigest(), bundle.receiptSetDigest(),
                candidate.evidenceRefs(), publish,
                sha256("publish\0" + publish.name()), binding.instructionId(),
                Objects.requireNonNullElse(
                        task.initialProjectVersion(), ChainIdentity.NONE));
    }

    static ReadinessValidationBundle readinessValidationBundle(
            ChainValidationBundleRuntime.Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome instanceof ChainValidationBundleRuntime.Committed committed) {
            var bundle = committed.bundle();
            return new ReadinessValidationBundle(
                    bundle.validationBundleId(), bundle.requestDigest(),
                    bundle.receiptSetDigest());
        }
        return new ReadinessValidationBundle(
                ChainIdentity.NONE, null, null);
    }

    /** V73 compatibility shape carrying the plan-level V82 Bundle identity. */
    record ReadinessValidationBundle(
            String validationId, String requestDigest,
            String receiptSetDigest) {
    }

    private ChainValidationRuntime.CommitResult commitValidation(
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainStepAuthorityPort.StepEvent activation,
            PlanStep step,
            ChainModelProtocolOutcome.ProposalReady ready,
            Instant now) {
        ExecutorPayload.StepResult payload = stepResultPayload(ready);
        var requirements = stepRequirements(binding, step);
        if (requirements.isEmpty()) {
            if (!payload.validationSources().isEmpty()) {
                throw failure("CHAIN_STEP_VALIDATION_NOT_REQUIRED");
            }
            return null;
        }
        return validations.commit(new ChainValidationRuntime.CommitCommand(
                validationScope(binding, activation, now), requirements,
                payload.validationSources()));
    }

    private static ChainValidationRuntime.Scope validationScope(
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainStepAuthorityPort.StepEvent activation,
            Instant now) {
        return new ChainValidationRuntime.Scope(binding.taskId(),
                binding.taskFrameId(), binding.planId(),
                binding.planRevisionId(), binding.planRevisionNumber(),
                activation.command().stepId(),
                activation.command().activationEventId(),
                "chain-validation:" + sha256(binding.taskId() + "\0"
                        + binding.planRevisionId() + "\0"
                        + activation.command().stepId() + "\0"
                        + activation.command().activationEventId()), now);
    }

    static ChainValidationRuntime.CommitResult recoverStepResultValidation(
            ChainValidationRuntime runtime,
            String preInvocationValidationId,
            String preInvocationRequestDigest,
            String preInvocationReceiptDigest,
            ChainValidationRuntime.Scope scope,
            List<io.paperagent.v2.contracts.ValidationRequirement>
                    requirements,
            List<io.paperagent.v2.chain.ProposalFields.ValidationSource>
                    sources) {
        if (preInvocationValidationId != null
                || preInvocationRequestDigest != null
                || preInvocationReceiptDigest != null) {
            throw failure("CHAIN_EXECUTOR_RECOVERY_VALIDATION_INVALID");
        }
        if (requirements.isEmpty()) {
            if (!sources.isEmpty()) {
                throw failure("CHAIN_STEP_VALIDATION_NOT_REQUIRED");
            }
            return null;
        }
        return runtime.commit(new ChainValidationRuntime.CommitCommand(
                scope, requirements, sources));
    }

    private List<io.paperagent.v2.contracts.ValidationRequirement>
            stepRequirements(
                    ChainPersistenceRecords.PlanBindingRecord binding,
                    PlanStep step) {
        var declared = formalRequirements(binding).validationRequirements();
        Map<String, io.paperagent.v2.contracts.ValidationRequirement> byId =
                declared.stream().collect(java.util.stream.Collectors.toMap(
                        io.paperagent.v2.contracts.ValidationRequirement
                                ::requirementId,
                        value -> value));
        return step.validationRequirementIds().stream().map(id -> {
            var requirement = byId.get(id);
            if (requirement == null) {
                throw failure("CHAIN_STEP_VALIDATION_REQUIREMENT_UNKNOWN");
            }
            return requirement;
        }).toList();
    }

    private boolean candidateRequired(
            ChainPersistenceRecords.PlanBindingRecord binding,
            PlanStep step) {
        return stepRequirements(binding, step).stream().anyMatch(value ->
                value.subject()
                        == io.paperagent.v2.contracts.ValidationSubject.CANDIDATE);
    }

    private boolean receiptValidationStep(
            ChainPersistenceRecords.PlanBindingRecord binding,
            PlanStep step) {
        var requirements = stepRequirements(binding, step);
        return !step.mayChangeCandidate()
                && !requirements.isEmpty()
                && requirements.stream().allMatch(value -> value.subject()
                == io.paperagent.v2.contracts.ValidationSubject
                        .ACTION_RECEIPT);
    }

    private SuccessfulReceipt latestSuccessfulReceipt(
            String taskId, String stepId, String activationEventId) {
        return workflow.findActionBindings(taskId).stream()
                .filter(action -> action.stepId().equals(stepId)
                        && action.activationEventId().equals(
                        activationEventId))
                .max(Comparator.comparingInt(
                        ChainPersistenceRecords.ActionBindingRecord::attemptNo))
                .flatMap(action -> {
                    var outcome = effectOutcomes.findResult(
                            new ToolCallId(action.actionId()));
                    if (!outcome.successful()
                            || outcome.value().orElseThrow().receipt().status()
                            != ReceiptStatus.SUCCESS) {
                        return java.util.Optional.empty();
                    }
                    return java.util.Optional.of(new SuccessfulReceipt(
                            action.actionId(), outcome.value().orElseThrow()
                                    .receipt().id().value()));
                }).orElse(null);
    }

    private boolean isObservedActionFailure(
            ProductChainExecutorActionContextProjection.Failure failure) {
        if (failure.receiptStatus() != ReceiptStatus.FAILURE) {
            return false;
        }
        return isObservedActionFailure(failure,
                effectOutcomes.findResult(
                        new ToolCallId(failure.actionId())));
    }

    static boolean isObservedActionFailure(
            ProductChainExecutorActionContextProjection.Failure failure,
            io.paperagent.v2.persistence.PersistenceResult<
                    io.paperagent.v2.persistence.PersistedEffectResult> result) {
        if (failure.receiptStatus() != ReceiptStatus.FAILURE
                || !result.successful()) {
            return false;
        }
        var receipt = result.value().orElseThrow().receipt();
        return receipt.id().value().equals(failure.errorRef())
                && receipt.status() == ReceiptStatus.FAILURE
                && receipt.exitCode().filter(code -> code != 0).isPresent();
    }

    static void validateReceiptValidationSuccess(
            ProviderRoleOutput output,
            SuccessfulReceipt success) {
        if (!(output.payload() instanceof ExecutorPayload.StepResult result)
                || !result.receiptRefs().contains(success.receiptRef())
                || result.validationSources().stream().noneMatch(source ->
                source.receiptRef().equals(success.receiptRef()))) {
            throw new IllegalArgumentException(
                    "a completed read-only validation action requires "
                            + "STEP_RESULT bound to its exact successful "
                            + "Receipt; expected receiptRefs and "
                            + "validationSources to contain receiptRef="
                            + success.receiptRef());
        }
    }

    record SuccessfulReceipt(String actionId, String receiptRef) {
        SuccessfulReceipt {
            required(actionId, "actionId");
            required(receiptRef, "receiptRef");
        }
    }

    static void validateReceiptValidationFailure(
            ProviderRoleOutput output,
            ProductChainExecutorActionContextProjection.Failure failure) {
        if (!(output.payload() instanceof ExecutorPayload.StepBlocked blocked)
                || !blocked.errorRef().equals(failure.errorRef())
                || !blocked.attemptedActionOrRepairRefs().contains(
                failure.actionId())
                || !blocked.attemptedActionOrRepairRefs().contains(
                failure.errorRef())) {
            throw new IllegalArgumentException(
                    "a failed read-only validation action requires "
                            + "STEP_BLOCKED bound to its exact Action and Receipt; "
                            + "expected errorRef=" + failure.errorRef()
                            + " and attemptedActionOrRepairRefs containing "
                            + "actionRef=" + failure.actionId()
                            + " and errorRef=" + failure.errorRef()
                            + "; do not retry or modify the Project");
        }
    }

    private static String firstReceipt(
            ChainValidationRuntime.CommitResult validation) {
        if (validation == null) return null;
        return java.util.stream.Stream.concat(
                        validation.candidateItems().stream().map(
                                ChainPersistenceRecords
                                        .CandidateValidationItemRecord
                                        ::receiptId),
                        validation.actionReceiptItems().stream().map(
                                ChainPersistenceRecords
                                        .ActionReceiptValidationItemRecord
                                        ::receiptId))
                .sorted().findFirst().orElseThrow();
    }

    private static ExecutorPayload.StepResult stepResultPayload(
            ChainModelProtocolOutcome.ProposalReady ready) {
        ProviderRoleOutput parsed = ProductChainPersistedProposalDecoder.decode(
                ready, ChainWorkState.EXECUTING, null);
        if (!(parsed.payload() instanceof ExecutorPayload.StepResult value)) {
            throw failure("CHAIN_EXECUTOR_STEP_RESULT_PROPOSAL_REQUIRED");
        }
        return value;
    }

    private boolean formalPublishRequired(
            ChainPersistenceRecords.PlanBindingRecord binding) {
        return taskFrameRequiresPublish(formalRequirements(binding));
    }

    static boolean taskFrameRequiresPublish(
            io.paperagent.v2.contracts.TaskRequirements requirements) {
        if (requirements == null
                || requirements.declarationMode()
                != io.paperagent.v2.contracts.RequirementDeclarationMode.EXPLICIT) {
            throw new IllegalStateException(
                    "CHAIN_TASK_REQUIREMENTS_LEGACY_UNSPECIFIED");
        }
        return switch (requirements.publishRequirement()) {
            case REQUIRED -> true;
            case NOT_REQUIRED -> false;
            case LEGACY_UNSPECIFIED -> throw new IllegalStateException(
                    "CHAIN_PUBLISH_REQUIREMENT_LEGACY_UNSPECIFIED");
        };
    }

    private boolean formalValidationRequired(
            ChainPersistenceRecords.PlanBindingRecord binding) {
        return !formalRequirements(binding).validationRequirements().isEmpty();
    }

    private io.paperagent.v2.contracts.TaskRequirements formalRequirements(
            ChainPersistenceRecords.PlanBindingRecord binding) {
        var requirements = bootstraps.find(new PlanId(binding.planId()))
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_PLAN_BOOTSTRAP_MISSING"))
                .taskFrame().requirements();
        if (requirements.declarationMode()
                != io.paperagent.v2.contracts.RequirementDeclarationMode.EXPLICIT) {
            throw new IllegalStateException(
                    "CHAIN_TASK_REQUIREMENTS_LEGACY_UNSPECIFIED");
        }
        return requirements;
    }

    static void validateReflectorFinalizationAuthority(
            ProviderRoleOutput output,
            boolean finalStep,
            boolean validationRequired,
            boolean publishRequired,
            String taskFrameId) {
        if (finalStep && output.payload()
                instanceof ReflectorPayload.AcceptStep) {
            throw new IllegalArgumentException(
                    "The final Plan Step must be accepted and become ready to finalize");
        }
        if (!(output.payload()
                instanceof ReflectorPayload.AcceptStepAndReadyToFinalize combined)) {
            return;
        }
        if (!finalStep) {
            throw new IllegalArgumentException(
                    "Only the final Plan Step may become ready to finalize");
        }
        var validation = combined.finalization().validationAssessment();
        if (!validationRequired) {
            if (validation.status()
                    != io.paperagent.v2.chain.ProposalFields.AssessmentStatus.NOT_REQUIRED) {
                throw new IllegalArgumentException(
                        "validationAssessment must be NOT_REQUIRED without formal Validation");
            }
        } else if (validation.status()
                != io.paperagent.v2.chain.ProposalFields.AssessmentStatus.BOUND
                || !taskFrameId.equals(validation.authorityRef())) {
            throw new IllegalArgumentException(
                    "validationAssessment must bind the frozen TaskFrame requirements");
        }
        var publish = combined.finalization().publishRequirementAssessment();
        if (!publishRequired) {
            if (publish.status()
                    != io.paperagent.v2.chain.ProposalFields.AssessmentStatus.NOT_REQUIRED) {
                throw new IllegalArgumentException(
                        "publishRequirementAssessment must be NOT_REQUIRED");
            }
        } else if (publish.status()
                != io.paperagent.v2.chain.ProposalFields.AssessmentStatus.BOUND
                || !taskFrameId.equals(publish.authorityRef())) {
            throw new IllegalArgumentException(
                    "publishRequirementAssessment must bind the exact TaskFrame");
        }
    }

    static void validateStepMutationBoundary(
            ProviderRoleOutput output, PlanStep step) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(step, "step");
        if (step.mayChangeCandidate()) {
            return;
        }
        if (output.payload() instanceof ExecutorPayload.WorkspaceChange
                || output.payload() instanceof ExecutorPayload.ToolAction action
                && !action.writeScopes().isEmpty()) {
            throw new IllegalArgumentException(
                    "the active Step forbids Candidate and Workspace mutation");
        }
    }

    static void validateExecutorCandidateBase(
            ProviderRoleOutput output, String expectedBaseCandidateRef) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(expectedBaseCandidateRef,
                "expectedBaseCandidateRef");
        if (output.payload() instanceof ExecutorPayload.WorkspaceChange change
                && !expectedBaseCandidateRef.equals(change.baseCandidateRef())) {
            throw new IllegalArgumentException(
                    "WORKSPACE_CHANGE baseCandidateRef must exactly equal the frozen "
                            + "Context candidateBaseRef (the current Candidate fingerprint); "
                            + "do not use workspaceCandidateRef or an earlier Candidate reference");
        }
    }

    static void validateExecutorStepResultValidationBindings(
            ProviderRoleOutput output,
            List<String> activeStepValidationRequirementIds) {
        Objects.requireNonNull(output, "output");
        List<String> expected = List.copyOf(Objects.requireNonNull(
                activeStepValidationRequirementIds,
                "activeStepValidationRequirementIds"));
        if (!(output.payload() instanceof ExecutorPayload.StepResult result)) {
            return;
        }
        List<String> actual = result.validationSources().stream()
                .map(io.paperagent.v2.chain.ProposalFields.ValidationSource
                        ::requirementId)
                .toList();
        if (actual.size() != expected.size()
                || !new java.util.HashSet<>(actual).equals(
                new java.util.HashSet<>(expected))) {
            throw new IllegalArgumentException(
                    "validationSources requirementIds must exactly match active Step "
                            + "validationRequirementIds; bind every required ID once and no other ID");
        }
    }

    private static String repairProtocolFeedback(String rejection) {
        return switch (rejection) {
            case "REPAIR_AUTHORITY_MISSING" ->
                    "failed-action repair must copy the exact visible priorActionRef and priorErrorRef; "
                            + "changeFromPriorAction and expectedProgress must also be non-null";
            case "REPAIR_AUTHORITY_MISMATCH" ->
                    "failed-action repair priorActionRef and priorErrorRef must exactly equal the current "
                            + "visible formal failure authorities";
            default -> throw new IllegalArgumentException(
                    "unsupported repair rejection: " + rejection);
        };
    }

    private ChainCompositeTransitionRuntime.AuthorityVerification verifyReadinessStage(
            ChainCompositeTransitionRuntime.StageAuthorityQuery query, Holder holder) {
        if (query.stage().stageCode()
                == ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY) {
            // This task has no predecessor applicability facts.  The empty
            // barrier is still a formal authority fact and must be verified as
            // an explicitly empty set, not as an ordinary non-empty stage.
            return ChainCompositeTransitionRuntime.AuthorityVerification.verifiedEmpty();
        }
        String type = query.stage().successorAuthorityType();
        String ref = query.stage().successorAuthorityRef();
        if ("ACCEPTED_RESULT".equals(type)) {
            return workflow.findAcceptedResults(query.transition().taskId()).stream()
                    .anyMatch(value -> value.acceptedResultId().equals(ref))
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : fail("CHAIN_READINESS_ACCEPTED_RESULT_MISSING");
        }
        if ("FINALIZATION_READINESS".equals(type)) {
            return finalization.findReadinessById(ref).isPresent()
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : fail("CHAIN_READINESS_FACT_MISSING");
        }
        if ("STEP_EVENT".equals(type)) {
            if (!ref.equals(holder.stepEventId)) {
                return fail("CHAIN_READINESS_STEP_EVENT_ID_MISMATCH");
            }
            long matches = workflow.findPlanBindings(query.transition().taskId())
                    .stream()
                    .flatMap(binding -> steps.findStepEvents(
                                    query.transition().taskId(),
                                    binding.planRevisionId()).stream())
                    .filter(event -> event.command().eventKind()
                            == ChainStepAuthorityPort.StepEventKind.COMPLETED)
                    .filter(event -> event.command().eventId().equals(ref))
                    .filter(event -> event.command().transitionId().equals(
                            query.transition().transitionId()))
                    .filter(event -> event.command().sourceDecisionId().equals(
                            query.transition().sourceDecisionId()))
                    .count();
            return matches == 1
                    ? ChainCompositeTransitionRuntime.AuthorityVerification.verified()
                    : fail("CHAIN_READINESS_STEP_COMPLETION_AUTHORITY_MISSING");
        }
        return ChainCompositeTransitionRuntime.AuthorityVerification.verified();
    }

    private static ChainCompositeTransitionRuntime.AuthorityVerification fail(String code) {
        throw new IllegalStateException(code);
    }

    private static CanonicalJson canonicalArray(List<String> values) {
        String json = values.stream().sorted().map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return new CanonicalJson(1, sha256(json), json);
    }

    private static final class Holder {
        private final String stepEventId;
        private boolean allowReadiness;
        private String acceptedId;
        private String readinessId;
        private Holder(String stepEventId) { this.stepEventId = stepEventId; }
    }

    ChainStepAuthorityPort.StepEvent activeActivation(
            String taskId, String revisionId) {
        ChainStepAuthorityPort.StepEvent active = activeActivationOrNull(
                taskId, revisionId);
        if (active == null) {
            throw new IllegalStateException("CHAIN_EXECUTOR_ACTIVE_STEP_MISSING");
        }
        return active;
    }

    private ChainPersistenceRecords.WorkspaceCandidateRecord
            currentWorkspaceCandidate(String taskId) {
        List<ChainPersistenceRecords.WorkspaceCandidateRecord> values =
                workflow.findWorkspaceCandidates(taskId);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private ChainPersistenceRecords.ActionBindingRecord currentCandidateAction(
            String taskId,
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate) {
        List<ChainPersistenceRecords.ActionBindingRecord> matches = workflow
                .findActionBindings(taskId).stream()
                .filter(value -> value.actionId().equals(candidate.actionId()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "CHAIN_EXECUTOR_CANDIDATE_ACTION_INVALID");
        }
        return matches.get(0);
    }

    static boolean candidateBelongsToActiveStep(
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate,
            ChainPersistenceRecords.ActionBindingRecord action,
            ChainPersistenceRecords.PlanBindingRecord binding,
            ChainStepAuthorityPort.StepEvent activation) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(activation, "activation");
        return candidate.taskId().equals(binding.taskId())
                && candidate.actionId().equals(action.actionId())
                && candidate.workspaceId().equals(action.workspaceId())
                && candidate.versionFenceSha256().equals(
                        action.versionFenceSha256())
                && action.taskId().equals(binding.taskId())
                && action.instructionId().equals(binding.instructionId())
                && action.taskFrameId().equals(binding.taskFrameId())
                && action.planId().equals(binding.planId())
                && action.planRevisionId().equals(binding.planRevisionId())
                && action.stepId().equals(activation.command().stepId())
                && action.activationEventId().equals(
                        activation.command().activationEventId());
    }

    ChainStepAuthorityPort.StepEvent activeActivationOrNull(
            String taskId, String revisionId) {
        List<ChainStepAuthorityPort.StepEvent> events = steps.findStepEvents(
                taskId, revisionId);
        ChainStepAuthorityPort.StepEvent active = null;
        for (ChainStepAuthorityPort.StepEvent event : events) {
            if (active != null && active.command().stepId().equals(event.command().stepId())
                    && event.command().eventKind() != ChainStepAuthorityPort.StepEventKind.ACTIVATED) {
                active = null;
            }
            if (event.command().eventKind() == ChainStepAuthorityPort.StepEventKind.ACTIVATED) {
                active = event;
            }
        }
        return active;
    }

    ChainStepAuthorityPort.StepEvent latestActivation(
            String taskId, String revisionId) {
        return steps.findStepEvents(taskId, revisionId).stream()
                .filter(event -> event.command().eventKind()
                        == ChainStepAuthorityPort.StepEventKind.ACTIVATED)
                .reduce((left, right) -> right)
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_EXECUTOR_STEP_ACTIVATION_MISSING"));
    }

    PlanStep step(ChainPersistenceRecords.PlanBindingRecord binding, String stepId) {
        return formalPlanRevision(steps, binding).steps().stream()
                .filter(value -> value.id().value().equals(stepId))
                .findFirst().orElseThrow(() -> new IllegalStateException("CHAIN_EXECUTOR_STEP_MISSING"));
    }

    static io.paperagent.v2.contracts.PlanRevision formalPlanRevision(
            ProductChainStepAuthorityAdapter authority,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        var revision = authority.findPlanRevision(
                        binding.taskId(), binding.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "CHAIN_EXECUTOR_PLAN_REVISION_IDENTITY_MISMATCH"));
        if (!revision.id().value().equals(binding.planRevisionId())
                || revision.number() != binding.planRevisionNumber()
                || !revision.taskFrameId().value().equals(
                binding.taskFrameId())) {
            throw new IllegalStateException(
                    "CHAIN_EXECUTOR_PLAN_REVISION_IDENTITY_MISMATCH");
        }
        return revision;
    }

    private String workspaceCandidateKey(String taskId) {
        return workspaceCandidateRef(taskId);
    }

    private String workspaceCandidateRef(String taskId) {
        return workflow.findWorkspaceCandidates(taskId).stream()
                .reduce((left, right) -> right)
                .map(ChainPersistenceRecords.WorkspaceCandidateRecord::workspaceCandidateId)
                .orElse("NONE");
    }

    private static String identity(String prefix, String material) {
        return prefix + "." + sha256(material);
    }

    public record ReflectorProposal(
            String proposalId,
            ChainProposalKind proposalKind,
            String invocationId,
            String candidateResultId) {
        public ReflectorProposal {
            required(proposalId, "proposalId");
            Objects.requireNonNull(proposalKind, "proposalKind");
            if (proposalKind.role() != ChainRole.REFLECTOR) {
                throw new IllegalArgumentException(
                        "proposalKind must belong to Reflector");
            }
            required(invocationId, "invocationId");
            required(candidateResultId, "candidateResultId");
        }
    }

    static String executorContextId(
            String taskId,
            String activationEventId,
            String callReason,
            String executionBody,
            String candidateFingerprint,
            String actionAttemptTable) {
        return identity("context", Objects.requireNonNull(taskId, "taskId") + "\0"
                + Objects.requireNonNull(activationEventId, "activationEventId") + "\0"
                + Objects.requireNonNull(callReason, "callReason") + "\0"
                + Objects.requireNonNull(executionBody, "executionBody") + "\0"
                + Objects.toString(candidateFingerprint, "NONE") + "\0"
                + Objects.toString(actionAttemptTable, "NO_FORMAL_ACTION_ATTEMPTS"));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
