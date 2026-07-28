package com.yanban.api.agent.v2.persistence;

import java.time.Instant;

@FunctionalInterface
interface ProductActiveStepReplanTimeSource {
    Instant now();
}
