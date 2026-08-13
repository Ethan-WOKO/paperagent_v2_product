package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProviderRoleOutput;

/** Strict first-layer decoder supplied by the provider integration module. */
@FunctionalInterface
public interface ChainRoleOutputDecoder {
    ProviderRoleOutput decode(
            String rawOutput,
            ChainRole expectedRole,
            ChainWorkState workState,
            String boundGapId);
}
