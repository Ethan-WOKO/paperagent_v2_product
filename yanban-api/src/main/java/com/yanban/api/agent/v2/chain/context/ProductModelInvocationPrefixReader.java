package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Proves that the exact Context lineage explains the full task prefix. */
final class ProductModelInvocationPrefixReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS;
    private final ProductChainModelRepositoryAdapter models;

    ProductModelInvocationPrefixReader(
            ProductChainModelRepositoryAdapter models) {
        this.models = models;
    }

    List<ChainPersistenceRecords.ModelInvocationRecord> exactInvocations(
            String taskId,
            List<ChainPersistenceRecords.ContextRevisionRecord> lineage) {
        List<ChainPersistenceRecords.ModelInvocationRecord> exact =
                new ArrayList<>();
        for (var context : lineage) {
            for (var value : models.findInvocationsByContextRevisionId(
                    taskId, context.contextRevisionId())) {
                requireContextIdentity(taskId, context, value);
                exact.add(value);
            }
        }
        validateOrdinals(exact);
        long taskCut = models.highestInvocationOrdinal(taskId);
        if (taskCut != exact.size()) {
            throw blocked("lineage does not explain the task invocation cut");
        }
        var prefix = models.findInvocations(taskId, taskCut);
        validateOrdinals(prefix);
        if (!prefix.equals(exact)) {
            throw blocked("lineage does not explain the full invocation prefix");
        }
        return List.copyOf(exact);
    }

    private static void requireContextIdentity(
            String taskId,
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord value) {
        if (!value.taskId().equals(taskId)
                || !value.contextRevisionId().equals(
                context.contextRevisionId())
                || value.role() != context.role()
                || value.workState() != context.workState()
                || !value.callReason().equals(context.callReason())
                || !value.completionToken().equals(context.completionToken())
                || !value.runtimePolicyVersion().equals(
                context.runtimePolicyVersion())) {
            throw blocked("invocation mismatches its Context lineage");
        }
    }

    private static void validateOrdinals(
            List<ChainPersistenceRecords.ModelInvocationRecord> values) {
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            if (value.invocationOrdinal() != index + 1L
                    || !identities.add(value.invocationId())) {
                throw blocked("invocation prefix is not unique and continuous");
            }
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }
}
