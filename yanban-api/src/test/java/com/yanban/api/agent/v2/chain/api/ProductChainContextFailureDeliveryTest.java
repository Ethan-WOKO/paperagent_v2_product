package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.v2.chain.context.ProductChainContextIdentity;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkState;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductChainContextFailureDeliveryTest {
    private static final Instant NOW = Instant.parse(
            "2026-08-09T00:00:00.123456789Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void answerContextFailureWritesDeliveryAndOneRealTerminalEvent() {
        var workflow = mock(ProductChainWorkflowRepositoryAdapter.class);
        var finalization = mock(
                ProductChainFinalizationRepositoryAdapter.class);
        var outcome = outcome();
        when(finalization.findTaskOutcome("task-1"))
                .thenReturn(Optional.of(outcome));
        when(finalization.appendDelivery(any())).thenAnswer(invocation -> {
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryRecord> requested =
                    invocation.getArgument(0);
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    authority(requested.event()), requested.fact(), false);
        });
        when(finalization.appendDeliveryEvent(any())).thenAnswer(invocation -> {
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.DeliveryEventRecord> requested =
                    invocation.getArgument(0);
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    authority(requested.event()), requested.fact(), false);
        });
        var delivery = new ProductChainContextFailureDelivery(
                workflow, finalization, transactions());
        var context = context(outcome);
        var failure = failure(context);

        var result = delivery.fail(
                task(), instruction(), context, failure, NOW);

        assertEquals(outcome.outcomeId(),
                result.delivery().taskOutcomeId());
        assertEquals(instruction().commandId(),
                result.delivery().sourceCommandId());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS),
                result.delivery().createdAt());
        assertEquals(NOW.truncatedTo(ChronoUnit.MICROS),
                result.failed().committedAt());
        assertEquals(ChainDeliveryStatus.DELIVERY_FAILED,
                result.failed().eventKind());
        assertEquals(1, result.failed().attemptNo());
        assertEquals(1L, result.failed().eventSequence());
        assertEquals("CONTEXT_INPUT_BLOCKED",
                result.failed().errorCode());
        verify(finalization).appendDelivery(any());
        verify(finalization).appendDeliveryEvent(any());
        verify(workflow, never()).findRouteDecisions(any());
    }

    private static ChainPersistenceRecords.ContextRevisionRecord context(
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                ProductChainContextIdentity.taskOutcomeAnswer(
                        "task-1", outcome.outcomeId()),
                "task-1", null, ChainRole.ANSWER,
                ChainWorkState.TERMINAL, "TASK_OUTCOME", "instruction-1",
                "frame-1", "plan-1", "revision-1", 1L,
                null, null, 1L, "project-version-1", null,
                null, null, null, null, null,
                "chain-product-projector-v1", "v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ContextBuildFailureRecord failure(
            ChainPersistenceRecords.ContextRevisionRecord context) {
        return new ChainPersistenceRecords.ContextBuildFailureRecord(
                "failure-1", "task-1", "failure-event-1",
                context.contextRevisionId(), ChainRole.ANSWER,
                ChainWorkState.TERMINAL, "TASK_OUTCOME", "instruction-1",
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS,
                "CONTEXT_INPUT_BLOCKED", context.projectorSetVersion(),
                context.paginationVersion(), context.runtimePolicyVersion(),
                NOW);
    }

    private static ChainPersistenceRecords.TaskOutcomeRecord outcome() {
        var empty = new ChainPersistenceRecords.CanonicalJson(
                1, sha256("[]"), "[]");
        return new ChainPersistenceRecords.TaskOutcomeRecord(
                "outcome-1", "task-1", "outcome-event-1", "command-1",
                ChainTaskOutcomeStatus.FAILED, "instruction-1",
                "frame-1", "plan-1", "revision-1", empty, empty,
                null, "NONE", "NONE", null, null, null, null,
                empty, empty, empty, "EXECUTION", "FAILED",
                "failure-source-1", NOW);
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
                HASH, "message-1", ChainInstructionRelation.INITIAL,
                null, null, HASH, NOW);
    }

    private static ChainPersistenceRecords.AuthorityEventRecord authority(
            ChainPersistenceRecords.AuthorityEventRequest request) {
        return new ChainPersistenceRecords.AuthorityEventRecord(
                request.eventId(), request.taskId(), 1L,
                request.eventType(), request.transitionId(),
                request.sourceIdentitySha256(), request.committedAt());
    }

    private static PlatformTransactionManager transactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(
                    TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
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
