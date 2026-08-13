package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductChainModelCallIdentityTest {
    private static final Instant NOW =
            Instant.parse("2026-08-08T00:00:00Z");
    private static final String HASH = "0".repeat(64);
    private ProductChainContextRepositoryAdapter contexts;
    private ProductChainModelRepositoryAdapter models;
    private ProductChainModelCallIdentity identity;

    @BeforeEach
    void setUp() {
        contexts = mock(ProductChainContextRepositoryAdapter.class);
        models = mock(ProductChainModelRepositoryAdapter.class);
        identity = new ProductChainModelCallIdentity(contexts, models);
    }

    @Test
    void firstInvocationStartsAtRoot() {
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.empty());
        when(models.findInvocation("invocation.1"))
                .thenReturn(Optional.empty());
        when(models.highestInvocationOrdinal("task.1")).thenReturn(0L);

        var value = identity.bind(
                "task.1", "context.1", "invocation.1");

        assertNull(value.parentContextRevisionId());
        assertEquals(1, value.invocationOrdinal());
    }

    @Test
    void newInvocationUsesExactLatestContextAsParent() {
        var parent = complete("context.parent", null);
        var first = invocation("invocation.1", parent, 1);
        when(contexts.findContextRevision("context.2"))
                .thenReturn(Optional.empty());
        when(models.findInvocation("invocation.2"))
                .thenReturn(Optional.empty());
        when(models.highestInvocationOrdinal("task.1")).thenReturn(1L);
        when(models.findInvocations("task.1", 1L))
                .thenReturn(List.of(first));
        when(contexts.findContextRevision("context.parent"))
                .thenReturn(Optional.of(parent));

        var value = identity.bind(
                "task.1", "context.2", "invocation.2");

        assertEquals("context.parent", value.parentContextRevisionId());
        assertEquals(2, value.invocationOrdinal());
    }

    @Test
    void replayKeepsOriginalParentAndOrdinal() {
        var existing = complete("context.2", "context.1");
        var invocation = invocation("invocation.2", existing, 2);
        when(contexts.findContextRevision("context.2"))
                .thenReturn(Optional.of(existing));
        when(models.findInvocation("invocation.2"))
                .thenReturn(Optional.of(invocation));
        when(models.highestInvocationOrdinal("task.1")).thenReturn(9L);

        var value = identity.bind(
                "task.1", "context.2", "invocation.2");

        assertEquals("context.1", value.parentContextRevisionId());
        assertEquals(2, value.invocationOrdinal());
    }

    @Test
    void frozenContextWithoutInvocationRetainsItsParent() {
        var existing = complete("context.2", "context.1");
        when(contexts.findContextRevision("context.2"))
                .thenReturn(Optional.of(existing));
        when(models.findInvocation("invocation.2"))
                .thenReturn(Optional.empty());
        when(models.highestInvocationOrdinal("task.1")).thenReturn(1L);

        var value = identity.bind(
                "task.1", "context.2", "invocation.2");

        assertEquals("context.1", value.parentContextRevisionId());
        assertEquals(2, value.invocationOrdinal());
    }

    @Test
    void buildingContextWithoutInvocationCanResumeItsFreeze() {
        var existing = building("context.2", "context.1");
        when(contexts.findContextRevision("context.2"))
                .thenReturn(Optional.of(existing));
        when(models.findInvocation("invocation.2"))
                .thenReturn(Optional.empty());
        when(models.highestInvocationOrdinal("task.1")).thenReturn(1L);

        var value = identity.bind(
                "task.1", "context.2", "invocation.2");

        assertEquals("context.2", value.contextRevisionId());
        assertEquals("context.1", value.parentContextRevisionId());
        assertEquals(2, value.invocationOrdinal());
    }

    @Test
    void exhaustedInvocationDerivesOneDeterministicRetryOnSameContext() {
        var context = complete("context.1", "context.parent");
        var first = invocation("invocation.1", context, 1);
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(context));
        when(models.findInvocation("invocation.1"))
                .thenReturn(Optional.of(first));
        when(models.highestProviderAttemptNo("invocation.1"))
                .thenReturn(3);
        when(models.findProviderAttempts("invocation.1"))
                .thenReturn(failedAttempts("invocation.1"));
        when(models.findInvocationsByContextRevisionId(
                "task.1", "context.1"))
                .thenReturn(List.of(first));
        when(models.highestInvocationOrdinal("task.1")).thenReturn(1L);

        var firstBinding = identity.bind(
                "task.1", "context.1", "invocation.1");
        var replayedBinding = identity.bind(
                "task.1", "context.1", "invocation.1");

        assertEquals("context.1", firstBinding.contextRevisionId());
        assertEquals("context.parent",
                firstBinding.parentContextRevisionId());
        assertEquals(2, firstBinding.invocationOrdinal());
        assertEquals(firstBinding, replayedBinding);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "invocation.1", firstBinding.invocationId());
    }

    @Test
    void invocationRetryStopsAtTheSharedContextLimit() {
        var context = complete("context.1", "context.parent");
        var first = invocation("invocation.1", context, 1);
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(context));
        when(models.findInvocation("invocation.1"))
                .thenReturn(Optional.of(first));
        when(models.highestProviderAttemptNo("invocation.1"))
                .thenReturn(3);
        when(models.findProviderAttempts("invocation.1"))
                .thenReturn(failedAttempts("invocation.1"));
        when(models.findInvocationsByContextRevisionId(
                "task.1", "context.1"))
                .thenReturn(List.of(first));
        when(models.highestInvocationOrdinal("task.1")).thenReturn(1L);
        var retry = identity.bind(
                "task.1", "context.1", "invocation.1");
        var second = invocation(retry.invocationId(), context, 2);
        when(models.findInvocation(retry.invocationId()))
                .thenReturn(Optional.of(second));
        when(models.highestProviderAttemptNo(retry.invocationId()))
                .thenReturn(3);
        when(models.findProviderAttempts(retry.invocationId()))
                .thenReturn(failedAttempts(retry.invocationId()));
        when(models.findInvocationsByContextRevisionId(
                "task.1", "context.1"))
                .thenReturn(List.of(first, second));

        var capped = identity.bind(
                "task.1", "context.1", "invocation.1");

        assertEquals(retry.invocationId(), capped.invocationId());
        assertEquals(2, capped.invocationOrdinal());
        assertEquals("context.1", capped.contextRevisionId());
    }

    @Test
    void successfulAttemptWithoutProposalIsRejectedAsCorruption() {
        var context = complete("context.1", null);
        var invocation = invocation("invocation.1", context, 1);
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(context));
        when(models.findInvocation("invocation.1"))
                .thenReturn(Optional.of(invocation));
        when(models.highestProviderAttemptNo("invocation.1"))
                .thenReturn(1);
        when(models.findProviderAttempts("invocation.1"))
                .thenReturn(List.of(new ChainPersistenceRecords
                        .ProviderAttemptRecord(
                        "invocation.1", 1, "task.1", 1,
                        "STOP",
                        ChainPersistenceRecords.ValidationStatus.PASSED,
                        ChainPersistenceRecords.ValidationStatus.PASSED,
                        null, NOW)));

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> identity.bind(
                        "task.1", "context.1", "invocation.1"));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    @Test
    void rejectedProposalCreatesANewChildInvocationIdentity() {
        var existing = complete("context.1", null);
        var invocation = invocation("invocation.1", existing, 1);
        var proposal = new ChainPersistenceRecords.ModelProposalRecord(
                "proposal.1", "task.1", "invocation.1", 1,
                ChainRole.EXECUTOR, ChainProposalKind.EXECUTOR_STEP_RESULT,
                new ChainPersistenceRecords.CanonicalJson(1, HASH, "{}"),
                new ChainPersistenceRecords.CanonicalJson(1, HASH, "{}"),
                null, null, NOW);
        var rejected = new ChainPersistenceRecords.ProposalStateEventRecord(
                "proposal.1", 1, "task.1", "state.rejected",
                ChainProposalState.REJECTED, null, null, NOW);
        when(contexts.findContextRevision("context.1"))
                .thenReturn(Optional.of(existing));
        when(models.findInvocation("invocation.1"))
                .thenReturn(Optional.of(invocation));
        when(models.findProposalByInvocation("invocation.1"))
                .thenReturn(Optional.of(proposal));
        when(models.findProposalStateEvents("proposal.1"))
                .thenReturn(List.of(rejected));
        when(models.highestInvocationOrdinal("task.1")).thenReturn(1L);
        when(models.findInvocations("task.1", 1L))
                .thenReturn(List.of(invocation));

        var value = identity.bind(
                "task.1", "context.1", "invocation.1");

        assertEquals("context.1", value.parentContextRevisionId());
        assertEquals(2, value.invocationOrdinal());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "context.1", value.contextRevisionId());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                "invocation.1", value.invocationId());
    }

    @Test
    void discontinuousInvocationPrefixBlocks() {
        var parent = complete("context.parent", null);
        when(contexts.findContextRevision("context.2"))
                .thenReturn(Optional.empty());
        when(models.findInvocation("invocation.2"))
                .thenReturn(Optional.empty());
        when(models.highestInvocationOrdinal("task.1")).thenReturn(2L);
        when(models.findInvocations("task.1", 2L)).thenReturn(List.of(
                invocation("invocation.2", parent, 2)));

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> identity.bind(
                        "task.1", "context.2", "invocation.new"));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }

    private static ChainPersistenceRecords.ContextRevisionRecord complete(
            String id, String parent) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                id, "task.1", parent, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "CALL", "instruction.1",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.COMPLETE, 13,
                new ChainPersistenceRecords.FormattedJson(1, "{}"), HASH,
                "completion." + id, null, null, NOW, NOW);
    }

    private static ChainPersistenceRecords.ContextRevisionRecord building(
            String id, String parent) {
        return new ChainPersistenceRecords.ContextRevisionRecord(
                id, "task.1", parent, ChainRole.EXECUTOR,
                ChainWorkState.EXECUTING, "CALL", "instruction.1",
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                "projectors.v1", "pages.v1",
                ChainRuntimePolicy.V1.policyVersion(),
                ChainContextRevisionStatus.BUILDING, 0,
                null, null, null, null, null, NOW, null);
    }

    private static ChainPersistenceRecords.ModelInvocationRecord invocation(
            String id,
            ChainPersistenceRecords.ContextRevisionRecord context,
            int ordinal) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                id, "task.1", context.contextRevisionId(),
                context.completionToken(), context.role(), context.workState(),
                context.callReason(), "provider", "model", ordinal,
                context.runtimePolicyVersion(), NOW);
    }

    private static List<ChainPersistenceRecords.ProviderAttemptRecord>
            failedAttempts(String invocationId) {
        return java.util.stream.IntStream.rangeClosed(
                        1, ChainRuntimePolicy.V1.providerAttemptsTotal())
                .mapToObj(attempt -> new ChainPersistenceRecords
                        .ProviderAttemptRecord(
                        invocationId, attempt, "task.1", attempt,
                        "ERROR",
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        "PROVIDER_UNAVAILABLE", NOW))
                .toList();
    }
}
