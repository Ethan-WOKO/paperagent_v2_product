package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_v2_project_analysis_deliveries")
class ProjectAnalysisDeliveryEntity {
    @EmbeddedId
    private ProjectAnalysisDeliveryKey id;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "objective_text", nullable = false, length = 2000)
    private String objective;
    @Column(name = "paths_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pathsJson;
    @Column(name = "search_query", length = 256)
    private String searchQuery;
    @Column(name = "max_search_results", nullable = false)
    private Integer maxSearchResults;
    @Column(name = "project_version_id", nullable = false, length = 128)
    private String projectVersionId;
    @Column(name = "user_message_id", nullable = false)
    private Long userMessageId;
    @Column(name = "turn_id", nullable = false)
    private Long turnId;
    @Column(name = "lease_owner_id", nullable = false, length = 128)
    private String leaseOwnerId;
    @Column(name = "lease_token", nullable = false, length = 128)
    private String leaseToken;
    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;
    @Column(name = "plan_id", length = 128)
    private String planId;
    @Column(name = "workspace_id", length = 128)
    private String workspaceId;
    @Column(name = "synthesis_id", length = 128)
    private String synthesisId;
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;
    @Column(name = "status", nullable = false, length = 32)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProjectAnalysisDeliveryEntity() {
    }

    ProjectAnalysisDeliveryEntity(
            ProjectAnalysisDeliveryKey id, String requestSha256,
            String objective, String pathsJson, String searchQuery,
            int maxSearchResults, String projectVersionId,
            Long userMessageId, Long turnId, String leaseOwnerId,
            String leaseToken, Instant leaseExpiresAt, Instant now) {
        this.id = id;
        this.requestSha256 = requestSha256;
        this.objective = objective;
        this.pathsJson = pathsJson;
        this.searchQuery = searchQuery;
        this.maxSearchResults = maxSearchResults;
        this.projectVersionId = projectVersionId;
        this.userMessageId = userMessageId;
        this.turnId = turnId;
        this.leaseOwnerId = leaseOwnerId;
        this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt;
        this.status = "RUNNING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    ProjectAnalysisDeliveryKey id() { return id; }
    String requestSha256() { return requestSha256; }
    String objective() { return objective; }
    String pathsJson() { return pathsJson; }
    String searchQuery() { return searchQuery; }
    Integer maxSearchResults() { return maxSearchResults; }
    String projectVersionId() { return projectVersionId; }
    Long userMessageId() { return userMessageId; }
    Long turnId() { return turnId; }
    String leaseOwnerId() { return leaseOwnerId; }
    String leaseToken() { return leaseToken; }
    Instant leaseExpiresAt() { return leaseExpiresAt; }
    String planId() { return planId; }
    String workspaceId() { return workspaceId; }
    String synthesisId() { return synthesisId; }
    Long assistantMessageId() { return assistantMessageId; }
    String status() { return status; }
    Instant createdAt() { return createdAt; }

    void bindPlan(String value) {
        if (planId != null && !planId.equals(value)) throw conflict();
        planId = value;
        updatedAt = Instant.now();
    }

    void bindWorkspace(String value) {
        if (workspaceId != null && !workspaceId.equals(value)) throw conflict();
        workspaceId = value;
        updatedAt = Instant.now();
    }

    void complete(String plan, String synthesis, Long assistant) {
        bindPlan(plan);
        if ("SUCCEEDED".equals(status)) {
            if (!synthesis.equals(synthesisId) || !assistant.equals(assistantMessageId)) {
                throw conflict();
            }
            return;
        }
        synthesisId = synthesis;
        assistantMessageId = assistant;
        status = "SUCCEEDED";
        updatedAt = Instant.now();
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("project analysis delivery conflict");
    }
}
