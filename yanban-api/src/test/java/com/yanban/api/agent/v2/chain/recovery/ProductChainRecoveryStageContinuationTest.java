package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.transition.ChainCompositeTransitionRuntime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductChainRecoveryStageContinuationTest {
    private static final Instant NOW = Instant.parse(
            "2026-08-08T08:08:00Z");
    private static final String SHA = "0".repeat(64);

    @Test
    void dispatchesEverySupportedTypeToItsFormalOwner() {
        AtomicInteger plans = new AtomicInteger();
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        AtomicInteger pendingItems = new AtomicInteger();
        var planResult = successor("PLAN_BINDING", "plan-binding-1");
        var stepResult = successor("ACCEPTED_RESULT", "accepted-1");
        var finalizationResult = new ChainCompositeTransitionRuntime
                .StageCommitResult("FINALIZATION_READINESS", "ready-1",
                null, null);
        var pendingResult = successor(
                "PENDING_ITEM_EVENT", "pending-resolved-1");
        var continuation = new ProductChainRecoveryStageContinuation(
                command -> {
                    plans.incrementAndGet();
                    return planResult;
                },
                command -> {
                    steps.incrementAndGet();
                    return stepResult;
                },
                command -> {
                    finalizations.incrementAndGet();
                    return finalizationResult;
                },
                command -> {
                    pendingItems.incrementAndGet();
                    return pendingResult;
                });

        assertSame(planResult, continuation.commit(command(
                ChainTransitionType.PLAN_CHANGE,
                ChainTransitionStage.TASKFRAME_PLAN_COMMITTED)));
        assertSame(stepResult, continuation.commit(command(
                ChainTransitionType.ACCEPT_STEP,
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED)));
        assertSame(stepResult, continuation.commit(command(
                ChainTransitionType.FINAL_STEP_READINESS,
                ChainTransitionStage.ACCEPTED_RESULT_COMMITTED_OR_VERIFIED)));
        assertSame(finalizationResult, continuation.commit(command(
                ChainTransitionType.FINALIZATION,
                ChainTransitionStage.READINESS_VERIFIED)));
        assertSame(pendingResult, continuation.commit(command(
                ChainTransitionType.GAP_RESOLUTION,
                ChainTransitionStage.PENDING_RESOLVED)));

        assertEquals(1, plans.get());
        assertEquals(2, steps.get());
        assertEquals(1, finalizations.get());
        assertEquals(1, pendingItems.get());
    }

    @Test
    void gapResolutionFailsWithoutAProductionNormalSuccessorAuthority() {
        var continuation = new ProductChainRecoveryStageContinuation(
                command -> unsupported(),
                command -> unsupported(),
                command -> unsupported());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> continuation.commit(command(
                        ChainTransitionType.GAP_RESOLUTION,
                        ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED)));

        assertEquals("CHAIN_GAP_NORMAL_SUCCESSOR_AUTHORITY_SOURCE_MISSING",
                failure.getMessage());
    }

    @Test
    void missingFormalAuthorityFailureIsNotConvertedToSuccess() {
        var continuation = new ProductChainRecoveryStageContinuation(
                command -> {
                    throw new IllegalStateException(
                            "CHAIN_PLAN_BINDING_AUTHORITY_MISSING");
                },
                command -> unsupported(),
                command -> unsupported());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> continuation.commit(command(
                        ChainTransitionType.PLAN_CHANGE,
                        ChainTransitionStage.TASKFRAME_PLAN_COMMITTED)));

        assertEquals("CHAIN_PLAN_BINDING_AUTHORITY_MISSING",
                failure.getMessage());
    }

    private static ChainCompositeTransitionRuntime.StageCommand command(
            ChainTransitionType type, ChainTransitionStage stage) {
        var identity = new ChainIdentity.Transition(
                type, "task-1", "source-1", SHA);
        var transition = new ChainPersistenceRecords.TransitionRecord(
                identity.transitionId(), "task-1", "event-1", type,
                "source-1", SHA, NOW);
        return new ChainCompositeTransitionRuntime.StageCommand(
                transition, stage, 1);
    }

    private static ChainCompositeTransitionRuntime.StageCommitResult successor(
            String type, String ref) {
        return ChainCompositeTransitionRuntime.StageCommitResult.successor(
                type, ref);
    }

    private static ChainCompositeTransitionRuntime.StageCommitResult
            unsupported() {
        throw new AssertionError("owner must not be called");
    }
}
