package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
class ProjectAnalysisDeliveryKey implements Serializable {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "client_request_id", nullable = false, length = 128)
    private String clientRequestId;

    protected ProjectAnalysisDeliveryKey() {
    }

    ProjectAnalysisDeliveryKey(
            Long userId, Long projectId, Long sessionId,
            String clientRequestId) {
        this.userId = userId;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.clientRequestId = clientRequestId;
    }

    Long userId() { return userId; }
    Long projectId() { return projectId; }
    Long sessionId() { return sessionId; }
    String clientRequestId() { return clientRequestId; }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectAnalysisDeliveryKey key
                && Objects.equals(userId, key.userId)
                && Objects.equals(projectId, key.projectId)
                && Objects.equals(sessionId, key.sessionId)
                && Objects.equals(clientRequestId, key.clientRequestId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, projectId, sessionId, clientRequestId);
    }
}
