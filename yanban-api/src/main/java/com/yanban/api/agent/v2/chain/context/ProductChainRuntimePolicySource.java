package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextRepository;
import io.paperagent.v2.chain.ChainRuntimePolicy;

import java.util.Objects;

/** Resolves one immutable runtime policy for the lifetime of a Task. */
public final class ProductChainRuntimePolicySource {
    private ProductChainRuntimePolicySource() {
    }

    public static ChainRuntimePolicy forTask(
            ChainContextRepository contexts, String taskId) {
        Objects.requireNonNull(contexts, "contexts");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        var versions = contexts.findContextRevisions(taskId).stream()
                .map(value -> value.runtimePolicyVersion())
                .distinct().toList();
        if (versions.isEmpty()) return ChainRuntimePolicy.current();
        if (versions.size() != 1) {
            throw new IllegalStateException(
                    "Task Context revisions use different runtime policies");
        }
        return ChainRuntimePolicy.requireVersion(versions.get(0));
    }
}
