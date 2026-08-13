package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;

/** Product/runtime authority check performed immediately before proposal acceptance. */
@FunctionalInterface
public interface ChainProposalCurrentFence {
    boolean isCurrent(Check check);

    record Check(
            String taskId,
            String invocationId,
            String contextRevisionId,
            ChainRole role,
            ChainWorkState workState) {
        public Check {
            if (taskId == null || taskId.isBlank()
                    || invocationId == null || invocationId.isBlank()
                    || contextRevisionId == null || contextRevisionId.isBlank()) {
                throw new IllegalArgumentException("proposal fence identities must not be blank");
            }
            java.util.Objects.requireNonNull(role, "role");
            java.util.Objects.requireNonNull(workState, "workState");
        }
    }
}
