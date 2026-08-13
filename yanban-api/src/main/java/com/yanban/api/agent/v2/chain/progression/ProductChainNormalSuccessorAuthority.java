package com.yanban.api.agent.v2.chain.progression;

import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProjectChainPlannerProgression;
import com.yanban.api.agent.v2.chain.recovery.ProductChainRecoveryStageAuthorityVerifier;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Commits the role-owned formal successor of one resolved PendingItem and
 * records it in the stable GAP_RESOLUTION transition.
 */
public final class ProductChainNormalSuccessorAuthority
        implements ChainPendingItemRuntime.NormalSuccessorPort {
    private final ChainFoundationRepository foundations;
    private final ChainModelRepository models;
    private final ChainWorkflowRepository workflow;
    private final ChainCompositeTransitionRuntime transitions;
    private final ProductChainRecoveryStageAuthorityVerifier verifier;
    private final ProjectChainPlannerProgression planner;
    private final ProductChainExecutorProgression executor;
    private final Clock clock;

    public ProductChainNormalSuccessorAuthority(
            ChainFoundationRepository foundations,
            ChainModelRepository models,
            ChainWorkflowRepository workflow,
            ChainCompositeTransitionRuntime transitions,
            ProductChainRecoveryStageAuthorityVerifier verifier,
            ProjectChainPlannerProgression planner,
            ProductChainExecutorProgression executor,
            Clock clock) {
        this.foundations = Objects.requireNonNull(
                foundations, "foundations");
        this.models = Objects.requireNonNull(models, "models");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.transitions = Objects.requireNonNull(
                transitions, "transitions");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ChainPendingItemRuntime.OfficialSuccessor commit(
            ChainPendingItemRuntime.NormalSuccessorRequest request) {
        Objects.requireNonNull(request, "request");
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(request.taskId())
                .orElseThrow(() -> failure(
                        "CHAIN_GAP_SUCCESSOR_TASK_MISSING"));
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(request.validationProposalId())
                .orElseThrow(() -> failure(
                        "CHAIN_GAP_SUCCESSOR_PROPOSAL_MISSING"));
        if (!proposal.taskId().equals(task.taskId())
                || !proposal.invocationId().equals(
                request.validationInvocationId())
                || proposal.role() != request.payload().role()
                || proposal.proposalKind() != request.payload().kind()) {
            throw failure("CHAIN_GAP_SUCCESSOR_PROPOSAL_IDENTITY_INVALID");
        }
        GapIdentity identity = exactGapIdentity(request);
        Instant committedAt = clock.instant();
        ChainCompositeTransitionRuntime.TransitionRequest transitionRequest =
                new ChainCompositeTransitionRuntime.TransitionRequest(
                        ChainTransitionType.GAP_RESOLUTION, task.taskId(),
                        request.validationInvocationId(), identity.digest(),
                        ChainCompositeTransitionRuntime.Branch.STANDARD,
                        committedAt);
        String computedId = new io.paperagent.v2.chain.ChainIdentity.Transition(
                transitionRequest.type(), transitionRequest.taskId(),
                transitionRequest.sourceDecisionId(),
                transitionRequest.targetIdentityDigest()).transitionId();
        if (!computedId.equals(request.transitionId())) {
            throw failure("CHAIN_GAP_SUCCESSOR_TRANSITION_IDENTITY_INVALID");
        }
        transitions.resumeThrough(
                transitionRequest,
                ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
                command -> officialSuccessor(task, proposal, committedAt));
        return findCommitted(task.taskId(), request.transitionId())
                .orElseThrow(() -> failure(
                        "CHAIN_GAP_SUCCESSOR_MARKER_MISSING"));
    }

    @Override
    public Optional<ChainPendingItemRuntime.OfficialSuccessor> findCommitted(
            String taskId, String transitionId) {
        required(taskId, "taskId");
        required(transitionId, "transitionId");
        ChainPersistenceRecords.TransitionRecord transition = workflow
                .findTransition(transitionId).orElse(null);
        if (transition == null) return Optional.empty();
        if (!transition.taskId().equals(taskId)
                || transition.transitionType()
                != ChainTransitionType.GAP_RESOLUTION) {
            throw failure("CHAIN_GAP_SUCCESSOR_TRANSITION_INVALID");
        }
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(transitionId).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord
                                ::stageOrdinal))
                .toList();
        if (stages.isEmpty()) return Optional.empty();
        if (stages.get(0).stageCode() != ChainTransitionStage.OPEN
                || stages.get(0).stageOrdinal() != 0) {
            throw failure("CHAIN_GAP_SUCCESSOR_STAGE_PREFIX_INVALID");
        }
        List<ChainPersistenceRecords.TransitionStageRecord> normal = stages
                .stream().filter(value -> value.stageCode()
                        == ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED)
                .toList();
        if (normal.isEmpty()) return Optional.empty();
        if (normal.size() != 1 || normal.get(0).stageOrdinal() != 1) {
            throw failure("CHAIN_GAP_SUCCESSOR_STAGE_PREFIX_INVALID");
        }
        var stage = normal.get(0);
        var verified = verifier.verify(
                new ChainCompositeTransitionRuntime.StageAuthorityQuery(
                        transition, stage));
        if (!verified.formalAuthorityVerified()
                || stage.successorAuthorityType() == null
                || stage.successorAuthorityRef() == null) {
            throw failure("CHAIN_GAP_SUCCESSOR_AUTHORITY_INVALID");
        }
        return Optional.of(new ChainPendingItemRuntime.OfficialSuccessor(
                transitionId, stage.successorAuthorityType(),
                stage.successorAuthorityRef()));
    }

    /**
     * Finishes the same GAP_RESOLUTION transition after the PendingItem
     * runtime has durably appended its RESOLVED event. Replays verify the
     * persisted stage authorities through the shared transition runtime.
     */
    public void completeResolved(
            String taskId, String transitionId, String resolvedEventId,
            Instant committedAt) {
        required(taskId, "taskId");
        required(transitionId, "transitionId");
        required(resolvedEventId, "resolvedEventId");
        Objects.requireNonNull(committedAt, "committedAt");
        ChainPersistenceRecords.TransitionRecord stored = workflow
                .findTransition(transitionId)
                .orElseThrow(() -> failure(
                        "CHAIN_GAP_TRANSITION_MISSING"));
        if (!stored.taskId().equals(taskId)
                || stored.transitionType()
                != ChainTransitionType.GAP_RESOLUTION) {
            throw failure("CHAIN_GAP_TRANSITION_IDENTITY_INVALID");
        }
        ChainCompositeTransitionRuntime.TransitionRequest request =
                new ChainCompositeTransitionRuntime.TransitionRequest(
                        stored.transitionType(), stored.taskId(),
                        stored.sourceDecisionId(),
                        stored.targetIdentityDigest(),
                        ChainCompositeTransitionRuntime.Branch.STANDARD,
                        committedAt);
        var outcome = transitions.resumeThrough(
                request, ChainTransitionStage.COMPLETE, command -> {
                    if (command.stage()
                            == ChainTransitionStage.PENDING_RESOLVED) {
                        return ChainCompositeTransitionRuntime
                                .StageCommitResult.successor(
                                        "PENDING_ITEM_EVENT",
                                        resolvedEventId);
                    }
                    throw failure("CHAIN_GAP_COMPLETION_STAGE_INVALID");
                });
        if (!outcome.complete()) {
            throw failure("CHAIN_GAP_TRANSITION_INCOMPLETE");
        }
    }

    private ChainCompositeTransitionRuntime.StageCommitResult officialSuccessor(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            Instant committedAt) {
        if (proposal.role() == ChainRole.PLANNER) {
            ChainPersistenceRecords.InstructionRecord instruction =
                    currentInstruction(task);
            ProjectChainPlannerProgression.OfficialSuccessor successor =
                    planner.commitAcceptedProposal(
                            task, instruction, proposal.proposalId(),
                            committedAt);
            return ChainCompositeTransitionRuntime.StageCommitResult.successor(
                    successor.authorityType(), successor.authorityRef());
        }
        if (proposal.role() == ChainRole.EXECUTOR) {
            var successor = executor.consumeAcceptedProposal(
                    task.taskId(), proposal.proposalId(), committedAt);
            return ChainCompositeTransitionRuntime.StageCommitResult.successor(
                    successor.authorityType(), successor.authorityRef());
        }
        throw failure("CHAIN_GAP_SUCCESSOR_ROLE_INVALID");
    }

    private ChainPersistenceRecords.InstructionRecord currentInstruction(
            ChainPersistenceRecords.TaskRecord task) {
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                foundations.findTaskInstructions(
                        task.taskId(), task.nextEventSequence()).stream()
                        .sorted(Comparator.comparingLong(
                                ChainPersistenceRecords
                                        .TaskInstructionBindingRecord
                                        ::taskInstructionSequence))
                        .toList();
        if (bindings.isEmpty()) {
            throw failure("CHAIN_GAP_SUCCESSOR_INSTRUCTION_MISSING");
        }
        for (int index = 0; index < bindings.size(); index++) {
            if (!bindings.get(index).taskId().equals(task.taskId())
                    || bindings.get(index).taskInstructionSequence()
                    != index + 1L) {
                throw failure(
                        "CHAIN_GAP_SUCCESSOR_INSTRUCTION_PREFIX_INVALID");
            }
        }
        String instructionId = bindings.get(bindings.size() - 1)
                .instructionId();
        ChainPersistenceRecords.InstructionRecord instruction = foundations
                .findInstruction(instructionId)
                .orElseThrow(() -> failure(
                        "CHAIN_GAP_SUCCESSOR_INSTRUCTION_MISSING"));
        if (instruction.sessionId() != task.sessionId()
                || !instruction.originTaskId().equals(task.taskId())) {
            throw failure("CHAIN_GAP_SUCCESSOR_INSTRUCTION_INVALID");
        }
        return instruction;
    }

    private GapIdentity exactGapIdentity(
            ChainPendingItemRuntime.NormalSuccessorRequest request) {
        List<ChainPersistenceRecords.PendingItemRecord> items = workflow
                .findPendingItems(request.taskId()).stream()
                .filter(value -> value.gapId().equals(request.gapId()))
                .toList();
        if (items.size() != 1) {
            throw failure("CHAIN_GAP_SUCCESSOR_PENDING_ITEM_INVALID");
        }
        List<GapIdentity> matches = workflow.findPendingItemEvents(
                        request.gapId()).stream()
                .filter(value -> value.eventKind()
                        == ChainPendingItemStatus.RESPONSE_RECEIVED)
                .map(value -> new GapIdentity(value.responseRound(), sha256(
                        request.taskId() + "\0" + request.gapId() + "\0"
                                + value.responseRound() + "\0"
                                + request.validationInvocationId())))
                .filter(value -> new io.paperagent.v2.chain.ChainIdentity
                        .Transition(ChainTransitionType.GAP_RESOLUTION,
                                request.taskId(),
                                request.validationInvocationId(),
                                value.digest()).transitionId().equals(
                                request.transitionId()))
                .toList();
        if (matches.size() != 1) {
            throw failure("CHAIN_GAP_SUCCESSOR_RESPONSE_IDENTITY_INVALID");
        }
        return matches.get(0);
    }

    private record GapIdentity(int responseRound, String digest) {
        private GapIdentity {
            if (responseRound < 1) {
                throw new IllegalArgumentException(
                        "responseRound must be positive");
            }
            required(digest, "digest");
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
