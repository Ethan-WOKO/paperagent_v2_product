package com.yanban.api.agent.v2.chain.persistence;

import java.time.Instant;

@FunctionalInterface
interface ProductChainTimeSource {
    Instant now();
}
