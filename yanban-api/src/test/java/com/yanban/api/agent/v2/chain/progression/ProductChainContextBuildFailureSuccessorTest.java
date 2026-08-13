package com.yanban.api.agent.v2.chain.progression;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import io.paperagent.v2.chain.step.ChainAbnormalSuccessorPolicy;
import com.yanban.api.agent.v2.chain.api.ProductChainContextFailureDelivery;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ProductChainContextBuildFailureSuccessorTest {
    private static final Instant NOW = Instant.parse(
            "2026-08-09T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void plannerAndReflectorCommitFailedOutcomeFromExactFailure() {
        for (ChainRole role : new ChainRole[]{
                ChainRole.PLANNER, ChainRole.REFLECTOR}) {
            var source = source(role, false);
            AtomicReference<ChainTaskOutcomeRuntime.Failed> committed =
                    new AtomicReference<>();
            var successor = successor(source, (command, verifier) -> {
                var failed = (ChainTaskOutcomeRuntime.Failed) command;
                verifier.verifyFailed(failed);
                committed.set(failed);
                return new ChainTaskOutcomeRuntime.CommitResult(
                        outcome(source), false);
            });

            var result = successor.advance(
                    task(), instruction(), source.failure()
                            .contextBuildFailureId(), NOW);

            assertInstanceOf(
                    ProductChainContextBuildFailureSuccessor.TaskFailed.class,
                    result);
            assertEquals(source.failure().contextBuildFailureId(),
                    committed.get().formalFailureSourceId());
            assertEquals("CONTEXT", committed.get().failureCategory());
            assertEquals("CONTEXT_INPUT_BLOCKED",
                    committed.get().failureCode());
        }
    }

    @Test
    void executorRequiresFormalStepBlockWithExactActivationIdentity() {
        var source = source(ChainRole.EXECUTOR, false);
        AtomicInteger outcomeWrites = new AtomicInteger();
        var successor = successor(source, (command, verifier) -> {
            outcomeWrites.incrementAndGet();
            throw new AssertionError("Executor must not write TaskOutcome");
        });

        var result = assertInstanceOf(
                ProductChainContextBuildFailureSuccessor
                        .StepBlockRequired.class,
                successor.advance(task(), instruction(),
                        source.failure().contextBuildFailureId(), NOW));

        assertEquals("step-1", result.stepId());
        assertEquals("activation-1", result.activationEventId());
        assertEquals(ChainRole.REFLECTOR,
                result.reflectorDirective().role());
        assertEquals(source.failure().contextBuildFailureId(),
                result.reflectorDirective().sourceAuthorityRef());
        assertEquals(0, outcomeWrites.get());
    }

    @Test
    void answerRequiresDeliveryFailureWithoutChangingTaskOutcome() {
        var source = source(ChainRole.ANSWER, false);
        AtomicInteger outcomeWrites = new AtomicInteger();
        var successor = successor(source, (command, verifier) -> {
            outcomeWrites.incrementAndGet();
            throw new AssertionError("Answer must not write TaskOutcome");
        });

        var result = assertInstanceOf(
                ProductChainContextBuildFailureSuccessor
                        .DeliveryFailed.class,
                successor.advance(task(), instruction(),
                        source.failure().contextBuildFailureId(), NOW));

        assertEquals(ChainDeliveryStatus.DELIVERY_FAILED,
                result.delivery().failed().eventKind());
        assertEquals(0, outcomeWrites.get());
    }

    @Test
    void existingChildContextPreventsASecondSuccessor() {
        var source = source(ChainRole.PLANNER, true);
        AtomicInteger outcomeWrites = new AtomicInteger();
        var successor = successor(source, (command, verifier) -> {
            outcomeWrites.incrementAndGet();
            throw new AssertionError("existing successor must be replayed");
        });

        assertInstanceOf(ProductChainContextBuildFailureSuccessor
                        .SuccessorAlreadyPresent.class,
                successor.advance(task(), instruction(),
                        source.failure().contextBuildFailureId(), NOW));
        assertEquals(0, outcomeWrites.get());
    }

    private static ProductChainContextBuildFailureSuccessor successor(
            ProductChainContextBuildFailureAuthority.Source source,
            ProductChainContextBuildFailureSuccessor.OutcomePort outcomes) {
        return new ProductChainContextBuildFailureSuccessor(
                (taskId, failureId) -> {
                    if (!source.failure().taskId().equals(taskId)
                            || !source.failure().contextBuildFailureId()
                            .equals(failureId)) {
                        throw new IllegalStateException("wrong source");
                    }
                    return source;
                }, outcomes, (task, instruction, context, failure, now) ->
                failedDelivery(), new ChainAbnormalSuccessorPolicy(
                ChainRuntimePolicy.V1));
    }

    private static ProductChainContextFailureDelivery.FailedDelivery
            failedDelivery() {
        var delivery = new ChainPersistenceRecords.DeliveryRecord(
                "delivery-1", "task-1", "delivery-event-1", "command-1",
                null, "outcome-source-1", null, null,
                null, null, NOW);
        var failed = new ChainPersistenceRecords.DeliveryEventRecord(
                delivery.deliveryId(), 1L, delivery.taskId(),
                "delivery-failed-1", ChainDeliveryStatus.DELIVERY_FAILED,
                1, "CONTEXT_INPUT_BLOCKED",
                ChainRuntimePolicy.V1.policyVersion(), NOW);
        return new ProductChainContextFailureDelivery.FailedDelivery(
                delivery, failed);
    }

    private static ProductChainContextBuildFailureAuthority.Source source(
            ChainRole role, boolean successorPresent) {
        ChainWorkState state = switch (role) {
            case PLANNER -> ChainWorkState.PLANNING;
            case EXECUTOR -> ChainWorkState.EXECUTING;
            case REFLECTOR -> ChainWorkState.AWAITING_REVIEW;
            case ANSWER -> ChainWorkState.TERMINAL;
        };
        String reason = role == ChainRole.ANSWER
                ? "TASK_OUTCOME" : "CONTEXT_TEST";
        var context = new ChainPersistenceRecords.ContextRevisionRecord(
                "context-1", "task-1", null, role, state, reason,
                "instruction-1", "frame-1", "plan-1", "revision-1",
                1L, role == ChainRole.PLANNER ? null : "step-1",
                role == ChainRole.PLANNER ? null : "activation-1",
                1L, "project-version-1", "workspace-1",
                null, null, null, null, null,
                "chain-product-projector-v1", "v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
        var failure = new ChainPersistenceRecords.ContextBuildFailureRecord(
                "failure-1", "task-1", "failure-event-1",
                context.contextRevisionId(), role, state, reason,
                "instruction-1", ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "CONTEXT_INPUT_BLOCKED", context.projectorSetVersion(),
                context.paginationVersion(), context.runtimePolicyVersion(),
                NOW);
        return new ProductChainContextBuildFailureAuthority.Source(
                context, failure, 3L, successorPresent);
    }

    private static ChainPersistenceRecords.TaskRecord task() {
        return new ChainPersistenceRecords.TaskRecord(
                "task-1", "command-1", "instruction-1", null,
                1L, 2L, 3L, 4L, "client-1", HASH,
                1L, "project-version-1", 3L, NOW);
    }

    private static ChainPersistenceRecords.InstructionRecord instruction() {
        return new ChainPersistenceRecords.InstructionRecord(
                "instruction-1", "command-1", 2L, "task-1", 4L,
                HASH, "message-1",
                io.paperagent.v2.chain.ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord outcome(
            ProductChainContextBuildFailureAuthority.Source source) {
        var empty = new ChainPersistenceRecords.CanonicalJson(
                1, sha256("[]"), "[]");
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "outcome-event-1", "command-1",
                ChainTaskOutcomeStatus.FAILED, "instruction-1",
                source.context().taskFrameId(), source.context().planId(),
                source.context().planRevisionId(), empty, empty,
                null, ChainIdentity.NONE, ChainIdentity.NONE,
                null, null, null, null, empty, empty, empty,
                "CONTEXT", "CONTEXT_INPUT_BLOCKED",
                source.failure().contextBuildFailureId(), NOW);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
