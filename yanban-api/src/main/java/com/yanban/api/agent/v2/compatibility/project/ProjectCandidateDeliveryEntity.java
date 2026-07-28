package com.yanban.api.agent.v2.compatibility.project;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_v2_project_candidate_deliveries")
class ProjectCandidateDeliveryEntity {
    @EmbeddedId private ProjectCandidateDeliveryKey id;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Column(name = "objective_text", nullable = false, length = 2000)
    private String objective;
    @Column(name = "paths_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pathsJson;
    @Column(name = "project_version_id", nullable = false, length = 128)
    private String projectVersionId;
    @Column(name = "user_message_id", nullable = false) private Long userMessageId;
    @Column(name = "turn_id", nullable = false) private Long turnId;
    @Column(name = "lease_owner_id", nullable = false, length = 128)
    private String leaseOwnerId;
    @Column(name = "lease_token", nullable = false, length = 128)
    private String leaseToken;
    @Column(name = "lease_expires_at", nullable = false) private Instant leaseExpiresAt;
    @Column(name = "plan_id", length = 128) private String planId;
    @Column(name = "workspace_id", length = 128) private String workspaceId;
    @Column(name = "artifact_id") private Long artifactId;
    @Column(name = "candidate_fingerprint", length = 64) private String candidateFingerprint;
    @Column(name = "diff_fingerprint", length = 64) private String diffFingerprint;
    @Column(name = "assistant_message_id") private Long assistantMessageId;
    @Column(name = "status", nullable = false, length = 32) private String status;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ProjectCandidateDeliveryEntity() {}

    ProjectCandidateDeliveryEntity(ProjectCandidateDeliveryKey id, String requestSha256,
            String objective, String pathsJson, String projectVersionId,
            Long userMessageId, Long turnId, String leaseOwnerId,
            String leaseToken, Instant leaseExpiresAt, Instant now) {
        this.id = id; this.requestSha256 = requestSha256; this.objective = objective;
        this.pathsJson = pathsJson; this.projectVersionId = projectVersionId;
        this.userMessageId = userMessageId; this.turnId = turnId;
        this.leaseOwnerId = leaseOwnerId; this.leaseToken = leaseToken;
        this.leaseExpiresAt = leaseExpiresAt; this.status = "RUNNING";
        this.createdAt = now; this.updatedAt = now;
    }

    ProjectCandidateDeliveryKey id() { return id; }
    String requestSha256() { return requestSha256; }
    String objective() { return objective; }
    String pathsJson() { return pathsJson; }
    String projectVersionId() { return projectVersionId; }
    Long turnId() { return turnId; }
    String leaseOwnerId() { return leaseOwnerId; }
    String leaseToken() { return leaseToken; }
    Instant leaseExpiresAt() { return leaseExpiresAt; }
    String planId() { return planId; }
    String workspaceId() { return workspaceId; }
    Long artifactId() { return artifactId; }
    String candidateFingerprint() { return candidateFingerprint; }
    String diffFingerprint() { return diffFingerprint; }
    Long assistantMessageId() { return assistantMessageId; }
    String status() { return status; }
    String errorCode() { return errorCode; }

    void bindPlan(String value) {
        if (planId != null && !planId.equals(value)) throw conflict();
        planId = value; updatedAt = Instant.now();
    }
    void bindWorkspace(String value) {
        if (workspaceId != null && !workspaceId.equals(value)) throw conflict();
        workspaceId = value; updatedAt = Instant.now();
    }
    void rotateLease(String token, Instant expiresAt) {
        leaseToken = token; leaseExpiresAt = expiresAt; updatedAt = Instant.now();
    }
    void bindCandidate(Long artifact, String candidate, String diff) {
        if (artifactId != null && (!artifactId.equals(artifact)
                || !candidateFingerprint.equals(candidate)
                || !diffFingerprint.equals(diff))) throw conflict();
        artifactId = artifact; candidateFingerprint = candidate;
        diffFingerprint = diff; updatedAt = Instant.now();
    }
    void complete(Long assistant) {
        if (artifactId == null || candidateFingerprint == null || diffFingerprint == null) {
            throw conflict();
        }
        if ("SUCCEEDED".equals(status)) {
            if (!assistant.equals(assistantMessageId)) throw conflict();
            return;
        }
        assistantMessageId = assistant; status = "SUCCEEDED";
        errorCode = null; updatedAt = Instant.now();
    }
    void fail(String code) {
        if ("SUCCEEDED".equals(status) || artifactId != null) throw conflict();
        if ("FAILED".equals(status) && !code.equals(errorCode)) throw conflict();
        status = "FAILED"; errorCode = code; updatedAt = Instant.now();
    }
    private static IllegalStateException conflict() {
        return new IllegalStateException("project candidate delivery conflict");
    }
}
