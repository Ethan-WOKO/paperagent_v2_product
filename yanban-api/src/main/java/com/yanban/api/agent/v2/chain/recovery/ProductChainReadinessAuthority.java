package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainPersistenceRecords;

/** Exact, read-only verification of an already committed readiness authority. */
@FunctionalInterface
public interface ProductChainReadinessAuthority {
    void requireExact(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness);
}
