package io.paperagent.v2.chain.transition;

import io.paperagent.v2.chain.ChainApplicability;

/** Resolves the exact formal source authority for an applicability commit. */
@FunctionalInterface
public interface ChainApplicabilityAuthorityPort {
    SourceAuthority verify(SourceQuery query);

    record SourceQuery(
            String taskId,
            ChainApplicability.SourceType sourceType,
            String sourceDecisionId,
            ChainApplicability.Identity targetIdentity) {
    }

    record SourceAuthority(
            ChainApplicability.SourceType sourceType,
            String sourceDecisionId,
            ChainApplicability.Identity targetIdentity,
            String sourceTransitionId,
            boolean directCommitAuthority) {
    }
}
