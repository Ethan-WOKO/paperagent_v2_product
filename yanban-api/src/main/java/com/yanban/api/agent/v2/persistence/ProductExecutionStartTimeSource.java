package com.yanban.api.agent.v2.persistence;

import java.time.Instant;

interface ProductExecutionStartTimeSource {
    Instant observe();
}
