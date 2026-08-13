package com.yanban.api.agent.v2.chain.context;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductChainRuntimePolicySourceTest {
    @Test
    void aTaskWithoutContextUsesTheCurrentPolicy() {
        ChainContextRepository contexts = mock(ChainContextRepository.class);
        when(contexts.findContextRevisions("task-1")).thenReturn(List.of());

        assertSame(ChainRuntimePolicy.current(),
                ProductChainRuntimePolicySource.forTask(contexts, "task-1"));
    }

    @Test
    void anExistingTaskUsesItsFrozenPolicyVersion() {
        ChainContextRepository contexts = mock(ChainContextRepository.class);
        var first = context(ChainRuntimePolicy.V1.policyVersion());
        var second = context(ChainRuntimePolicy.V1.policyVersion());
        when(contexts.findContextRevisions("task-1"))
                .thenReturn(List.of(first, second));

        assertSame(ChainRuntimePolicy.V1,
                ProductChainRuntimePolicySource.forTask(contexts, "task-1"));
    }

    @Test
    void rejectsMixedOrUnknownStoredVersions() {
        ChainContextRepository mixed = mock(ChainContextRepository.class);
        var current = context(ChainRuntimePolicy.V1.policyVersion());
        var next = context("chain-runtime-policy-v2");
        when(mixed.findContextRevisions("task-1")).thenReturn(
                List.of(current, next));
        assertThrows(IllegalStateException.class,
                () -> ProductChainRuntimePolicySource.forTask(
                        mixed, "task-1"));

        ChainContextRepository unknown = mock(ChainContextRepository.class);
        var unknownContext = context("chain-runtime-policy-unknown");
        when(unknown.findContextRevisions("task-1")).thenReturn(
                List.of(unknownContext));
        assertThrows(IllegalArgumentException.class,
                () -> ProductChainRuntimePolicySource.forTask(
                        unknown, "task-1"));
    }

    private static ChainPersistenceRecords.ContextRevisionRecord context(
            String policyVersion) {
        var context = mock(
                ChainPersistenceRecords.ContextRevisionRecord.class);
        when(context.runtimePolicyVersion()).thenReturn(policyVersion);
        return context;
    }
}
