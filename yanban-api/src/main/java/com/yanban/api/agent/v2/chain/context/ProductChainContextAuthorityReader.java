package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.context.ChainContextProjectionRequest;

/** Reads one module from its concrete product authority at the requested cut. */
@FunctionalInterface
public interface ProductChainContextAuthorityReader {
    ProductChainContextAuthorityProjection read(
            ChainContextProjectionRequest request);
}
