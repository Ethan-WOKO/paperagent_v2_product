package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.recovery.ChainRecoveryRuntime;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Resumes the exact persisted transition; it never creates another identity. */
public final class ProductChainCompositeTransitionRecovery
        implements ChainRecoveryRuntime.CompositeTransitionRecovery {
    private final ChainWorkflowRepository workflow;
    private final ChainCompositeTransitionRuntime transitions;
    private final StageContinuation continuation;
    private final ProductChainFinalizationRecoverySource finalization;
    private final ProductChainMechanicalFinalizationPort mechanicalFinalization;
    private final Clock clock;

    public ProductChainCompositeTransitionRecovery(
            ChainWorkflowRepository workflow,
            ChainCompositeTransitionRuntime transitions,
            StageContinuation continuation,
            ProductChainFinalizationRecoverySource finalization,
            ProductChainMechanicalFinalizationPort mechanicalFinalization,
            Clock clock) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.transitions = Objects.requireNonNull(transitions, "transitions");
        this.continuation = Objects.requireNonNull(continuation, "continuation");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.mechanicalFinalization = Objects.requireNonNull(
                mechanicalFinalization, "mechanicalFinalization");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ChainRecoveryRuntime.TransitionRecoveryResult resume(
            ChainRecoveryRuntime.TransitionRef ref) {
        Objects.requireNonNull(ref, "ref");
        ChainPersistenceRecords.TransitionRecord transition = workflow
                .findTransition(ref.transitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "incomplete transition no longer exists"));
        if (!transition.taskId().equals(ref.taskId())
                || transition.transitionType() != ref.transitionType()) {
            throw new IllegalStateException(
                    "recovery transition identity changed");
        }
        ChainTransitionStage persisted = persistedStage(transition);
        if (persisted != ref.persistedStage()) {
            throw new IllegalStateException(
                    "recovery transition stage changed before continuation");
        }

        ChainCompositeTransitionRuntime.Branch branch =
                ChainCompositeTransitionRuntime.Branch.STANDARD;
        if (transition.transitionType() == ChainTransitionType.FINALIZATION) {
            var state = finalization.inspect(transition);
            if (state instanceof ProductChainFinalizationRecoverySource
                    .RequiresMechanicalFinalization required) {
                Objects.requireNonNull(mechanicalFinalization.finalizeReadiness(
                                required.readinessId(), clock.instant()),
                        "mechanical finalization result");
                ChainTransitionStage afterMechanical = persistedStage(transition);
                if (afterMechanical == ChainTransitionStage.COMPLETE) {
                    return new ChainRecoveryRuntime.TransitionRecoveryResult(
                            transition.transitionId(), transition.transitionType(),
                            ChainTransitionStage.COMPLETE);
                }
                state = finalization.inspect(transition);
                if (state instanceof ProductChainFinalizationRecoverySource
                        .RequiresMechanicalFinalization) {
                    throw new IllegalStateException(
                            "mechanical finalization made no formal progress");
                }
            }
            if (state instanceof ProductChainFinalizationRecoverySource
                    .CheckFailure failure) {
                return ChainRecoveryRuntime.TransitionRecoveryResult
                        .waitingForFormalSuccessor(
                                transition.transitionId(), failure.reason());
            }
            if (state instanceof ProductChainFinalizationRecoverySource
                    .PublishFailureState failure) {
                return ChainRecoveryRuntime.TransitionRecoveryResult
                        .waitingForFormalSuccessor(
                                transition.transitionId(), failure.reason());
            }
            branch = ((ProductChainFinalizationRecoverySource.Continue) state)
                    .branch();
        }
        Instant committedAt = clock.instant();
        var outcome = transitions.resume(
                new ChainCompositeTransitionRuntime.TransitionRequest(
                        transition.transitionType(), transition.taskId(),
                        transition.sourceDecisionId(),
                        transition.targetIdentityDigest(), branch, committedAt),
                continuation::commit);
        if (!outcome.transition().equals(transition)
                || !outcome.complete()) {
            throw new IllegalStateException(
                    "same-transition continuation did not complete");
        }
        return new ChainRecoveryRuntime.TransitionRecoveryResult(
                transition.transitionId(), transition.transitionType(),
                ChainTransitionStage.COMPLETE);
    }

    private ChainTransitionStage persistedStage(
            ChainPersistenceRecords.TransitionRecord transition) {
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(
                        ChainPersistenceRecords.TransitionStageRecord::stageOrdinal))
                .toList();
        List<ChainTransitionStage> prefix = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            if (!stage.taskId().equals(transition.taskId())
                    || !stage.transitionId().equals(transition.transitionId())
                    || stage.stageOrdinal() != index) {
                throw new IllegalStateException("transition stage prefix is corrupt");
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "transition stage prefix is corrupt", invalid);
            }
            prefix.add(stage.stageCode());
        }
        return stages.isEmpty()
                ? ChainTransitionStage.OPEN
                : stages.get(stages.size() - 1).stageCode();
    }

    @FunctionalInterface
    public interface StageContinuation {
        ChainCompositeTransitionRuntime.StageCommitResult commit(
                ChainCompositeTransitionRuntime.StageCommand command);
    }

}
