package com.yanban.api.agent.v2.adaptive;

import java.time.Instant;

public record V2AdaptiveTurnSnapshot(
        V2AdaptiveTurnResponse response,
        Instant createdAt,
        Instant updatedAt) {
}
