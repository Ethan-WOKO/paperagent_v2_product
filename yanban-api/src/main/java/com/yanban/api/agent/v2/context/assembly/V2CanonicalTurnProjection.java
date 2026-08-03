package com.yanban.api.agent.v2.context.assembly;

import java.time.Instant;

public record V2CanonicalTurnProjection(
        Long turnId,
        Long userId,
        Long sessionId,
        Long userMessageId,
        Long assistantMessageId,
        Instant startedAt,
        String status,
        String userRole,
        String assistantRole,
        String userContent,
        String assistantContent
) { }
