package com.yanban.api.agent.v2.intake;

public record V2NaturalLanguageTurnResponse(
        Long sessionId,
        Long turnId,
        Long userMessageId,
        Long assistantMessageId,
        String clientRequestId,
        String route,
        String answer,
        String planId,
        boolean replayed,
        String rootClientRequestId) {
}
