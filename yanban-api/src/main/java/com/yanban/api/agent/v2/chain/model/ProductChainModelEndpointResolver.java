package com.yanban.api.agent.v2.chain.model;

import io.paperagent.v2.chain.model.ChainModelCallRequest;

/** Resolves transient product credentials without persisting them in V2. */
@FunctionalInterface
public interface ProductChainModelEndpointResolver {
    ProductChainModelEndpoint resolve(ChainModelCallRequest request);
}
