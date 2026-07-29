package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.contracts.PlanId;

/** Resolves one transient endpoint from an authoritative persisted Plan owner. */
@FunctionalInterface
public interface ProductModelEndpointResolver {
    ProductModelEndpoint resolve(PlanId planId);
}
