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
        boolean writeWorkspace,
        boolean executeSandbox,
        String modelProvider,
        String modelName,
        Instant expiresAt) {

    public EngineTaskAuthority {
        if (taskId == null || !taskId.matches("task\\.[a-f0-9]{64}")
                || requestDigest == null || !requestDigest.matches("[a-f0-9]{64}")
                || userId <= 0 || turnId <= 0 || sessionId <= 0 || projectId <= 0
                || projectVersion == null || !projectVersion.matches("[a-f0-9]{64}")
                || !readProject || !executeSandbox
                || modelProvider == null || modelProvider.isBlank() || modelProvider.length() > 120
                || modelName == null || modelName.isBlank() || modelName.length() > 240
                || expiresAt == null) {
            throw new IllegalArgumentException("engine task authority is invalid");
        }
    }

    public EngineTaskAuthority(String taskId, String requestDigest, long userId, long turnId,
                               long sessionId, long projectId, String projectVersion,
                               boolean readProject, boolean writeWorkspace,
                               boolean executeSandbox, Instant expiresAt) {
        this(taskId, requestDigest, userId, turnId, sessionId, projectId, projectVersion,
                readProject, writeWorkspace, executeSandbox, "test", "test-model", expiresAt);
    }

    public EngineTaskAuthority(String taskId, String requestDigest, long userId, long turnId,
                               long sessionId, long projectId, String projectVersion,
                               boolean readProject, boolean executeSandbox, Instant expiresAt) {
        this(taskId, requestDigest, userId, turnId, sessionId, projectId, projectVersion,
                readProject, false, executeSandbox, "test", "test-model", expiresAt);
    }
}
