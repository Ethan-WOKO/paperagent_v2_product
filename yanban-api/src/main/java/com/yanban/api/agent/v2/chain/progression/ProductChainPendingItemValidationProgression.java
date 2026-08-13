package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.model.ProductChainProposalAdmissionAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFoundationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.model.ChainProposalAdmissionService;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/** Applies one accepted validation proposal through the formal PendingItem runtime. */
@Component
public final class ProductChainPendingItemValidationProgression {
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductChainFoundationRepositoryAdapter foundations;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainNormalSuccessorAuthority successors;
    private final ProductChainProposalAdmissionAdapter admissions;

    public ProductChainPendingItemValidationProgression(
            ProductChainWorkflowRepositoryAdapter workflow,
            ProductChainFoundationRepositoryAdapter foundations,
            ProductChainModelRepositoryAdapter models,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainNormalSuccessorAuthority successors,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.successors = Objects.requireNonNull(successors, "successors");
        this.admissions = new ProductChainProposalAdmissionAdapter(
                jdbc, transactions, models, models);
    }

    public void consume(
            String taskId, String proposalId, Instant committedAt) {
        required(taskId, "taskId");
        required(proposalId, "proposalId");
        Objects.requireNonNull(committedAt, "committedAt");
        AcceptedSource accepted = accepted(taskId, proposalId);
        ChainPendingItemRuntime runtime = runtime(accepted, committedAt);
        String resolvedEventId = "pending.validation." + sha256(
                taskId + "\0" + accepted.item().gapId() + "\0"
                        + accepted.invocation().invocationId());
        runtime.applyValidation(new ChainPendingItemRuntime.ValidationRequest(
                taskId, accepted.item().gapId(), resolvedEventId,
                proposalId, committedAt));

        if (accepted.payload().gapValidation().outcome()
                == io.paperagent.v2.chain.GapValidation.Outcome.RESOLVED) {
            String transitionId = ChainPendingItemRuntime.gapTransitionId(
                    accepted.item(), accepted.responseRound(),
                    accepted.invocation().invocationId());
            successors.completeResolved(
                    taskId, transitionId, resolvedEventId, committedAt);
        }
    }

    /** Recovers one missing stage of an already-open GAP_RESOLUTION. */
    public ChainCompositeTransitionRuntime.StageCommitResult
            recoverCommittedStage(
                    ChainCompositeTransitionRuntime.StageCommand command) {
        Objects.requireNonNull(command, "command");
        var transition = command.transition();
        var proposal = models.findProposalByInvocation(
                        transition.sourceDecisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_PENDING_RECOVERY_PROPOSAL_MISSING"));
        AcceptedSource accepted = accepted(
                transition.taskId(), proposal.proposalId());
        String expectedTransition = ChainPendingItemRuntime.gapTransitionId(
                accepted.item(), accepted.responseRound(),
                accepted.invocation().invocationId());
        if (!expectedTransition.equals(transition.transitionId())) {
            throw failure("CHAIN_PENDING_RECOVERY_TRANSITION_MISMATCH");
        }
        if (command.stage()
                == ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED) {
            var successor = successors.commit(
                    new ChainPendingItemRuntime.NormalSuccessorRequest(
                            transition.taskId(), accepted.item().gapId(),
                            transition.transitionId(),
                            accepted.proposal().proposalId(),
                            accepted.invocation().invocationId(),
                            accepted.payload()));
            return ChainCompositeTransitionRuntime.StageCommitResult
                    .successor(successor.authorityType(),
                            successor.authorityRef());
        }
        if (command.stage() == ChainTransitionStage.PENDING_RESOLVED) {
            String eventId = "pending.validation." + sha256(
                    transition.taskId() + "\0" + accepted.item().gapId()
                            + "\0" + accepted.invocation().invocationId());
            runtime(accepted, command.transition().createdAt())
                    .applyValidation(
                            new ChainPendingItemRuntime.ValidationRequest(
                                    transition.taskId(),
                                    accepted.item().gapId(), eventId,
                                    accepted.proposal().proposalId(),
                                    command.transition().createdAt()));
            return ChainCompositeTransitionRuntime.StageCommitResult
                    .successor("PENDING_ITEM_EVENT", eventId);
        }
        throw failure("CHAIN_PENDING_RECOVERY_STAGE_INVALID");
    }

    private ChainPendingItemRuntime runtime(
            AcceptedSource accepted, Instant committedAt) {
        ChainPendingItemRuntime.GapValidationProposalSource source = ignored ->
                new ChainPendingItemRuntime.AcceptedGapValidation(
                        accepted.proposal(), accepted.state(),
                        accepted.invocation(), accepted.payload());
        ChainPendingItemRuntime.ProposalOfficialBinder binder =
                (taskId, proposalId, type, ref) -> admissions
                        .replaceByOfficialResult(
                                new ChainProposalAdmissionService
                                        .OfficialReplacement(
                                        proposalId, taskId,
                                        "proposal-bound." + sha256(
                                                proposalId + "\0" + type
                                                        + "\0" + ref),
                                        ChainPersistenceRecords
                                                .ProposalOfficialAuthorityType
                                                .valueOf(type),
                                        ref, null,
                                        accepted.proposal().payload().sha256(),
                                        committedAt));
        return new ChainPendingItemRuntime(
                workflow, foundations, workflow,
                ignored -> { throw failure(
                        "CHAIN_PENDING_OPEN_SOURCE_NOT_AVAILABLE"); },
                source, successors,
                new ChainPendingItemRuntime.PermissionDecisionSource() {
                    @Override
                    public java.util.Optional<ChainPersistenceRecords
                            .PermissionDecisionRecord> find(
                            String taskId, String gapId, String decisionId) {
                        return workflow.findPermissionDecisions(taskId).stream()
                                .filter(value -> value.gapId().equals(gapId)
                                        && value.permissionDecisionId()
                                        .equals(decisionId))
                                .findFirst();
                    }

                    @Override
                    public java.util.Optional<ChainPersistenceRecords
                            .PermissionDecisionRecord> findLatest(
                            String taskId, String gapId) {
                        long cut = foundations
                                .highestAuthorityEventSequence(taskId);
                        var sequences = foundations.findAuthorityEvents(
                                taskId, cut).stream().collect(
                                java.util.stream.Collectors.toMap(
                                        ChainPersistenceRecords
                                                .AuthorityEventRecord::eventId,
                                        ChainPersistenceRecords
                                                .AuthorityEventRecord
                                                ::eventSequence));
                        return workflow.findPermissionDecisions(taskId).stream()
                                .filter(value -> value.gapId().equals(gapId))
                                .max(Comparator.comparingLong(value -> {
                                    Long sequence = sequences.get(
                                            value.eventId());
                                    if (sequence == null) {
                                        throw failure(
                                                "CHAIN_PERMISSION_AUTHORITY_SEQUENCE_MISSING");
                                    }
                                    return sequence;
                                }));
                    }
                }, binder);
    }

    private AcceptedSource accepted(String taskId, String proposalId) {
        var proposal = models.findProposal(proposalId)
                .orElseThrow(() -> failure(
                        "CHAIN_PENDING_VALIDATION_PROPOSAL_MISSING"));
        var invocation = models.findInvocation(proposal.invocationId())
                .orElseThrow(() -> failure(
                        "CHAIN_PENDING_VALIDATION_INVOCATION_MISSING"));
        if (!proposal.taskId().equals(taskId)
                || !invocation.taskId().equals(taskId)
                || invocation.workState()
                != io.paperagent.v2.chain.ChainWorkState
                .VALIDATING_PENDING_ITEM) {
            throw failure("CHAIN_PENDING_VALIDATION_IDENTITY_INVALID");
        }
        var context = contexts.findContextRevision(
                        invocation.contextRevisionId())
                .orElseThrow(() -> failure(
                        "CHAIN_PENDING_VALIDATION_CONTEXT_MISSING"));
        if (!context.taskId().equals(taskId)
                || context.status()
                != io.paperagent.v2.chain.ChainContextRevisionStatus.COMPLETE
                || context.role() != invocation.role()
                || context.workState() != invocation.workState()
                || !context.completionToken().equals(
                invocation.completionToken())
                || !context.runtimePolicyVersion().equals(
                invocation.runtimePolicyVersion())
                || !invocation.runtimePolicyVersion().equals(
                context.runtimePolicyVersion())) {
            throw failure("CHAIN_PENDING_VALIDATION_CONTEXT_INVALID");
        }
        var attempts = models.findProviderAttempts(
                        invocation.invocationId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.ProviderAttemptRecord
                                ::attemptNo)).toList();
        if (attempts.isEmpty()) {
            throw failure("CHAIN_PENDING_VALIDATION_ATTEMPT_MISSING");
        }
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (!attempt.taskId().equals(taskId)
                    || !attempt.invocationId().equals(
                    invocation.invocationId())
                    || attempt.attemptNo() != index + 1) {
                throw failure("CHAIN_PENDING_VALIDATION_ATTEMPT_INVALID");
            }
        }
        var successful = attempts.get(attempts.size() - 1);
        if (successful.schemaValidationStatus()
                != ChainPersistenceRecords.ValidationStatus.PASSED
                || successful.proposalValidationStatus()
                != ChainPersistenceRecords.ValidationStatus.PASSED
                || successful.errorCode() != null) {
            throw failure("CHAIN_PENDING_VALIDATION_ATTEMPT_INVALID");
        }
        var bindings = foundations.findTaskInstructions(
                        taskId, Long.MAX_VALUE).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord
                                ::taskInstructionSequence)).toList();
        if (bindings.isEmpty()
                || !bindings.get(bindings.size() - 1).instructionId()
                .equals(context.instructionId())) {
            throw failure("CHAIN_PENDING_VALIDATION_INSTRUCTION_STALE");
        }
        List<ChainPersistenceRecords.ProposalStateEventRecord> states = models
                .findProposalStateEvents(proposalId).stream()
                .sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        if (states.isEmpty() || states.size() > 2
                || states.get(0).stateKind() != ChainProposalState.ACCEPTED) {
            throw failure("CHAIN_PENDING_VALIDATION_STATE_INVALID");
        }
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposalId)
                    || state.stateSequence() != index + 1L) {
                throw failure("CHAIN_PENDING_VALIDATION_STATE_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw failure("CHAIN_PENDING_VALIDATION_STATE_INVALID");
            }
            prefix.add(state.stateKind());
        }
        if (!proposal.invocationId().equals(invocation.invocationId())
                || proposal.role() != invocation.role()
                || proposal.proposalKind().role() != proposal.role()) {
            throw failure("CHAIN_PENDING_VALIDATION_IDENTITY_INVALID");
        }
        List<AcceptedSource> matches = new ArrayList<>();
        for (var item : workflow.findPendingItems(taskId)) {
            if (item.validationRole() != invocation.role()) continue;
            io.paperagent.v2.chain.ChainProposalPayload payload;
            try {
                String raw = "{\"schemaVersion\":\"1\",\"kind\":\""
                        + proposal.proposalKind().wireName()
                        + "\",\"payload\":" + proposal.payload().json()
                        + "}";
                payload = new StrictChainProviderOutputParser().parse(
                        raw, invocation.role(), invocation.workState(),
                        item.gapId()).payload();
            } catch (io.paperagent.v2.chain.model
                    .ChainProviderProtocolException ignored) {
                // A typed validation proposal may target only one frozen gap.
                continue;
            }
            var events = workflow.findPendingItemEvents(item.gapId());
            if (events.isEmpty()) {
                throw failure("CHAIN_PENDING_VALIDATION_RESPONSE_MISSING");
            }
            var latest = events.get(events.size() - 1);
            var response = latest;
            if (latest.eventKind() == ChainPendingItemStatus.RESOLVED
                    && Objects.equals(latest.validationInvocationId(),
                    invocation.invocationId())) {
                if (events.size() < 2) {
                    throw failure(
                            "CHAIN_PENDING_VALIDATION_RESPONSE_MISSING");
                }
                response = events.get(events.size() - 2);
            }
            var validation = new ChainPendingItemRuntime
                    .AcceptedGapValidation(proposal,
                    states.get(states.size() - 1), invocation, payload);
            ChainPendingItemRuntime.validateGapProposalAuthority(
                    item, response.eventKind(), response.responseRound(),
                    response.answerInstructionId(), validation);
            if (response.eventKind()
                    != ChainPendingItemStatus.RESPONSE_RECEIVED) {
                throw failure("CHAIN_PENDING_VALIDATION_RESPONSE_STALE");
            }
            if (!Objects.equals(response.answerInstructionId(),
                    context.instructionId())) {
                throw failure(
                        "CHAIN_PENDING_VALIDATION_INSTRUCTION_MISMATCH");
            }
            matches.add(new AcceptedSource(
                    item, response.responseRound(), proposal,
                    invocation, states.get(states.size() - 1),
                            payload));
        }
        if (matches.size() != 1) {
            throw failure("CHAIN_PENDING_VALIDATION_GAP_NOT_UNIQUE");
        }
        return matches.get(0);
    }

    private record AcceptedSource(
            ChainPersistenceRecords.PendingItemRecord item,
            int responseRound,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ModelInvocationRecord invocation,
            ChainPersistenceRecords.ProposalStateEventRecord state,
            io.paperagent.v2.chain.ChainProposalPayload payload) { }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static IllegalStateException failure(String code) {
        return new IllegalStateException(code);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
