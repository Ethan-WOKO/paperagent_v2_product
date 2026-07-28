package com.yanban.api.agent.v2.compatibility.project;

public record V2ProjectAnalysisResponse(
        Long projectId,
        Long sessionId,
        String clientRequestId,
        String status,
        boolean terminal,
        Long turnId,
        String planId,
        String projectVersion,
        String finalText,
        Long assistantMessageId,
        boolean replayed) {
}
