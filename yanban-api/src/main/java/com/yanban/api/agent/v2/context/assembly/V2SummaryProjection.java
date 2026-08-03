package com.yanban.api.agent.v2.context.assembly;

public record V2SummaryProjection(
        Long summaryId,
        Long userId,
        Long sessionId,
        Long coveredMessageId,
        int messageCount,
        String content,
        String sourceVersion,
        String digest
) { }
