package com.yanban.api.agent.v2.chain.recovery;

import com.yanban.api.agent.v2.chain.api.ProductChainExecutorProgression;
import com.yanban.api.agent.v2.chain.api.ProductChainPlanTransitionDriver;
import com.yanban.api.agent.v2.chain.finalization.ProductChainFinalizationCoordinator;
import com.yanban.api.agent.v2.chain.progression.ProductChainPendingItemValidationProgression;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Dispatches recovery to the owner of each persisted transition successor.
 * It never substitutes an empty result for a missing formal authority.
 */
@Component
public final class ProductChainRecoveryStageContinuation
        implements ProductChainCompositeTransitionRecovery.StageContinuation {
    private final StageOwner planChanges;
    private final StageOwner stepProgression;
    private final StageOwner finalization;
    private final StageOwner pendingItems;

    @Autowired
    public ProductChainRecoveryStageContinuation(
            ProductChainPlanTransitionDriver planChanges,
            ProductChainExecutorProgression stepProgression,
            ProductChainFinalizationCoordinator finalization,
            ProductChainPendingItemValidationProgression pendingItems) {
        this(planChanges::recoverCommittedStage,
                stepProgression::recoverCommittedStage,
                finalization::recoverCommittedStage,
                pendingItems::recoverCommittedStage);
    }

    ProductChainRecoveryStageContinuation(
            StageOwner planChanges,
            StageOwner stepProgression,
            StageOwner finalization) {
        this(planChanges, stepProgression, finalization, command -> {
            throw new IllegalStateException(
                    "CHAIN_GAP_NORMAL_SUCCESSOR_AUTHORITY_SOURCE_MISSING");
        });
    }

    ProductChainRecoveryStageContinuation(
            StageOwner planChanges,
            StageOwner stepProgression,
            StageOwner finalization,
            StageOwner pendingItems) {
        this.planChanges = Objects.requireNonNull(
                planChanges, "planChanges");
        this.stepProgression = Objects.requireNonNull(
                stepProgression, "stepProgression");
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.pendingItems = Objects.requireNonNull(
                pendingItems, "pendingItems");
    }

    @Override
    public ChainCompositeTransitionRuntime.StageCommitResult commit(
            ChainCompositeTransitionRuntime.StageCommand command) {
        Objects.requireNonNull(command, "command");
        return switch (command.transition().transitionType()) {
            case PLAN_CHANGE -> planChanges.commit(command);
            case ACCEPT_STEP, FINAL_STEP_READINESS ->
                    stepProgression.commit(command);
            case FINALIZATION -> finalization.commit(command);
            case GAP_RESOLUTION -> pendingItems.commit(command);
        };
    }

    @FunctionalInterface
    interface StageOwner {
        ChainCompositeTransitionRuntime.StageCommitResult commit(
                ChainCompositeTransitionRuntime.StageCommand command);
    }
}
