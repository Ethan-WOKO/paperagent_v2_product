package com.yanban.api.agent.v2.compatibility.project;

public record V2ProjectCandidateResponse(
        Long projectId,
        Long sessionId,
        String clientRequestId,
        String status,
        boolean terminal,
        Long turnId,
        String planId,
        String projectVersion,
        Long candidateArtifactId,
        String candidateFingerprint,
        String diffFingerprint,
        Long assistantMessageId,
        String errorCode,
        boolean replayed) {
}
