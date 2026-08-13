package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.finalization.ChainFinalizationRuntime;

import java.time.Instant;

/** Narrow adapter for ProductChainFinalizationCoordinator::finalizeReadiness. */
@FunctionalInterface
public interface ProductChainMechanicalFinalizationPort {
    ChainFinalizationRuntime.Result finalizeReadiness(
            String readinessId, Instant committedAt);
}
