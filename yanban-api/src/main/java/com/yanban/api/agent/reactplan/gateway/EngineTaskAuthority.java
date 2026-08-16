package com.yanban.api.agent.reactplan.gateway;

import java.time.Instant;

public record EngineTaskAuthority(
        String taskId,
        String requestDigest,
        long userId,
        long turnId,
        long sessionId,
        long projectId,
        String projectVersion,
        boolean readProject,
        boolean executeSandbox,
        Instant expiresAt) {

    public EngineTaskAuthority {
        if (taskId == null || !taskId.matches("task\\.[a-f0-9]{64}")
                || requestDigest == null || !requestDigest.matches("[a-f0-9]{64}")
                || userId <= 0 || turnId <= 0 || sessionId <= 0 || projectId <= 0
                || projectVersion == null || !projectVersion.matches("[a-f0-9]{64}")
                || !readProject || !executeSandbox || expiresAt == null) {
            throw new IllegalArgumentException("engine task authority is invalid");
        }
    }
}
