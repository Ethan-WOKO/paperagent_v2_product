package com.yanban.api.agent.v2.compatibility.literature;

public record V2LiteratureTurnResponse(
        Long sessionId,
        Long turnId,
        Long userMessageId,
        Long assistantMessageId,
        String clientRequestId,
        String planId,
        String synthesisId,
        String assistantContent,
        boolean replayed) {
}
