package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Binds a new or replayed model call to the exact task-local Context lineage. */
@Component
public final class ProductChainModelCallIdentity {
    private static final ChainContextModule MODULE =
            ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS;
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductChainFailedModelInvocationLineage failedInvocations;

    public ProductChainModelCallIdentity(
            ProductChainContextRepositoryAdapter contexts,
            ProductChainModelRepositoryAdapter models) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.failedInvocations =
                new ProductChainFailedModelInvocationLineage(models);
    }

    public Binding bind(
            String taskId, String contextRevisionId, String invocationId) {
        require(taskId, "taskId");
        require(contextRevisionId, "contextRevisionId");
        require(invocationId, "invocationId");
        String effectiveContextId = contextRevisionId;
        String effectiveInvocationId = invocationId;
        String contextInvocationRootId = invocationId;
        var existingContext = contexts.findContextRevision(effectiveContextId)
                .orElse(null);
        var existingInvocation = models.findInvocation(effectiveInvocationId)
                .orElse(null);
        while (existingContext != null && existingInvocation != null) {
            verifyContext(taskId, existingContext);
            verifyInvocation(taskId, effectiveContextId, existingInvocation);
            String retryEventId = rejectedOrStaleStateEvent(
                    taskId, existingInvocation.invocationId());
            if (retryEventId == null) {
                var failure = failedInvocations.decide(
                        taskId, existingContext, existingInvocation,
                        contextInvocationRootId);
                if (!failure.failed()) {
                    return binding(existingContext, existingInvocation);
                }
                if (failure.capped()) {
                    return binding(existingContext, existingInvocation);
                }
                effectiveInvocationId = failure.retryInvocationId();
                existingInvocation = failure.existingRetry();
                continue;
            }
            effectiveContextId = retryIdentity(
                    "context", contextRevisionId, retryEventId);
            effectiveInvocationId = retryIdentity(
                    "invocation", invocationId, retryEventId);
            contextInvocationRootId = effectiveInvocationId;
            existingContext = contexts.findContextRevision(effectiveContextId)
                    .orElse(null);
            existingInvocation = models.findInvocation(effectiveInvocationId)
                    .orElse(null);
        }
        if (existingContext != null) {
            if (existingInvocation != null) {
                verifyContext(taskId, existingContext);
                verifyInvocation(taskId, effectiveContextId,
                        existingInvocation);
                return new Binding(effectiveContextId, effectiveInvocationId,
                        existingContext.parentContextRevisionId(),
                        existingInvocation.invocationOrdinal());
            }
            verifyReusableContext(taskId, existingContext);
            return new Binding(effectiveContextId, effectiveInvocationId,
                    existingContext.parentContextRevisionId(),
                    nextOrdinal(taskId));
        }
        if (existingInvocation != null) {
            throw blocked("model invocation exists without its ContextRevision");
        }
        long head = models.highestInvocationOrdinal(taskId);
        if (head == 0) return new Binding(
                effectiveContextId, effectiveInvocationId, null, 1);
        List<ChainPersistenceRecords.ModelInvocationRecord> prefix =
                models.findInvocations(taskId, head);
        if (head > Integer.MAX_VALUE || prefix.size() != head) {
            throw blocked("model invocation prefix is incomplete");
        }
        for (int index = 0; index < prefix.size(); index++) {
            var value = prefix.get(index);
            if (!value.taskId().equals(taskId)
                    || value.invocationOrdinal() != index + 1) {
                throw blocked("model invocation prefix is not continuous");
            }
        }
        var previous = prefix.get(prefix.size() - 1);
        var parent = contexts.findContextRevision(previous.contextRevisionId())
                .orElseThrow(() -> blocked(
                        "latest model invocation ContextRevision is missing"));
        verifyContext(taskId, parent);
        return new Binding(effectiveContextId, effectiveInvocationId,
                parent.contextRevisionId(),
                Math.toIntExact(head + 1));
    }

    private static Binding binding(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord invocation) {
        return new Binding(context.contextRevisionId(),
                invocation.invocationId(),
                context.parentContextRevisionId(),
                invocation.invocationOrdinal());
    }

    private String rejectedOrStaleStateEvent(
            String taskId, String invocationId) {
        var proposal = models.findProposalByInvocation(invocationId)
                .orElse(null);
        if (proposal == null) return null;
        if (!proposal.taskId().equals(taskId)
                || !proposal.invocationId().equals(invocationId)) {
            throw blocked("model proposal identity mismatches its invocation");
        }
        var states = models.findProposalStateEvents(proposal.proposalId())
                .stream().sorted(java.util.Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        if (states.isEmpty()) return null;
        List<ChainProposalState> prefix = new java.util.ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(taskId)
                    || !state.proposalId().equals(proposal.proposalId())
                    || state.stateSequence() != index + 1L) {
                throw blocked("model proposal state prefix is invalid");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw blocked("model proposal state prefix is invalid");
            }
            prefix.add(state.stateKind());
        }
        var latest = states.get(states.size() - 1);
        return latest.stateKind() == ChainProposalState.REJECTED
                || latest.stateKind() == ChainProposalState.STALE
                ? latest.eventId() : null;
    }

    private static String retryIdentity(
            String domain, String baseId, String stateEventId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain + "\0proposal-retry\0" + baseId + "\0"
                            + stateEventId).getBytes(StandardCharsets.UTF_8));
            return domain + "." + java.util.HexFormat.of()
                    .formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private int nextOrdinal(String taskId) {
        long head = models.highestInvocationOrdinal(taskId);
        if (head >= Integer.MAX_VALUE) {
            throw blocked("model invocation ordinal is exhausted");
        }
        return Math.toIntExact(head + 1);
    }

    private static void verifyContext(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        if (!context.taskId().equals(taskId)
                || context.status() != ChainContextRevisionStatus.COMPLETE) {
            throw blocked("model call ContextRevision is not a complete task cut");
        }
    }

    private static void verifyReusableContext(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        if (!context.taskId().equals(taskId)) {
            throw blocked("model call ContextRevision belongs to another task");
        }
    }

    private static void verifyInvocation(
            String taskId, String contextRevisionId,
            ChainPersistenceRecords.ModelInvocationRecord invocation) {
        if (!invocation.taskId().equals(taskId)
                || !invocation.contextRevisionId().equals(contextRevisionId)) {
            throw blocked("model invocation identity mismatches its ContextRevision");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    public record Binding(
            String contextRevisionId,
            String invocationId,
            String parentContextRevisionId,
            int invocationOrdinal) {
        public Binding {
            require(contextRevisionId, "contextRevisionId");
            require(invocationId, "invocationId");
            if (invocationOrdinal < 1) {
                throw new IllegalArgumentException(
                        "invocationOrdinal must be positive");
            }
        }
    }
}
