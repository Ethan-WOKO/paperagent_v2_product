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
    @Column(name = "repair_source_validation_id", length = 36) private String repairSourceValidationId;
    @Column(name = "repair_source_artifact_id") private Long repairSourceArtifactId;
    @Column(name = "repair_source_fingerprint", length = 64) private String repairSourceFingerprint;
    @Column(name = "repair_selected_index") private Integer repairSelectedIndex;
    @Column(name = "repair_selected_path", length = 512) private String repairSelectedPath;
    @Column(name = "repair_failed_receipt_digest", length = 64) private String repairFailedReceiptDigest;
    @Column(name = "repair_attempt") private Integer repairAttempt;
    @Column(name = "repair_max_attempts") private Integer repairMaxAttempts;
    @Lob @Column(name = "repair_source_replacements_json", columnDefinition = "LONGTEXT")
    private String repairSourceReplacementsJson;
    @Column(name = "repair_source_replacements_sha256", length = 64)
    private String repairSourceReplacementsSha256;
    @Lob @Column(name = "repair_diagnostic", columnDefinition = "TEXT") private String repairDiagnostic;
    @Column(name = "prepared_replacements_json", columnDefinition = "LONGTEXT")
    private String preparedReplacementsJson;
    @Column(name = "prepared_replacements_sha256", length = 64)
    private String preparedReplacementsSha256;
    @Column(name = "prepared_diff_fingerprint", length = 64)
    private String preparedDiffFingerprint;
    @Lob @Column(name = "prepared_maven_coordinates_json", columnDefinition = "TEXT")
    private String preparedMavenCoordinatesJson;
    @Column(name = "prepared_maven_coordinates_sha256", length = 64)
    private String preparedMavenCoordinatesSha256;
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
    String repairSourceValidationId() { return repairSourceValidationId; }
    Long repairSourceArtifactId() { return repairSourceArtifactId; }
    String repairSourceFingerprint() { return repairSourceFingerprint; }
    Integer repairSelectedIndex() { return repairSelectedIndex; }
    String repairSelectedPath() { return repairSelectedPath; }
    String repairFailedReceiptDigest() { return repairFailedReceiptDigest; }
    Integer repairAttempt() { return repairAttempt; }
    Integer repairMaxAttempts() { return repairMaxAttempts; }
    String repairSourceReplacementsJson() { return repairSourceReplacementsJson; }
    String repairSourceReplacementsSha256() { return repairSourceReplacementsSha256; }
    String repairDiagnostic() { return repairDiagnostic; }
    String preparedReplacementsJson() { return preparedReplacementsJson; }
    String preparedReplacementsSha256() { return preparedReplacementsSha256; }
    String preparedDiffFingerprint() { return preparedDiffFingerprint; }
    String preparedMavenCoordinatesJson() { return preparedMavenCoordinatesJson; }
    String preparedMavenCoordinatesSha256() { return preparedMavenCoordinatesSha256; }
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
    void bindRepair(V2ProjectCandidateRepairRequest repair, String replacementsJson,
            String replacementsSha256) {
        if (repairSourceValidationId != null) {
            if (!repairSourceValidationId.equals(repair.sourceValidationId())
                    || !repairSourceArtifactId.equals(repair.sourceCandidateArtifactId())
                    || !repairSourceFingerprint.equals(repair.sourceCandidateFingerprint())
                    || repairSelectedIndex != repair.selectedChangeIndex()
                    || !repairSelectedPath.equals(repair.selectedPath())
                    || !repairFailedReceiptDigest.equals(repair.failedReceiptDigest())
                    || repairAttempt != repair.attempt() || repairMaxAttempts != repair.maxAttempts()
                    || !repairSourceReplacementsJson.equals(replacementsJson)
                    || !repairSourceReplacementsSha256.equals(replacementsSha256)
                    || !java.util.Objects.equals(repairDiagnostic, repair.compilerDiagnostic())) throw conflict();
            return;
        }
        if (planId != null
                || repair.attempt() != 1 || repair.maxAttempts() != 1
                || !projectVersionId.equals(repair.originalProjectVersion())) throw conflict();
        repairSourceValidationId = repair.sourceValidationId();
        repairSourceArtifactId = repair.sourceCandidateArtifactId();
        repairSourceFingerprint = repair.sourceCandidateFingerprint();
        repairSelectedIndex = repair.selectedChangeIndex();
        repairSelectedPath = repair.selectedPath();
        repairFailedReceiptDigest = repair.failedReceiptDigest();
        repairAttempt = repair.attempt();
        repairMaxAttempts = repair.maxAttempts();
        repairSourceReplacementsJson = replacementsJson;
        repairSourceReplacementsSha256 = replacementsSha256;
        repairDiagnostic = repair.compilerDiagnostic();
        updatedAt = Instant.now();
    }
    void bindPrepared(String replacements, String replacementsSha256, String coordinates,
            String coordinatesSha256, String diff) {
        if (preparedReplacementsJson != null
                && (!preparedReplacementsJson.equals(replacements)
                || !preparedReplacementsSha256.equals(replacementsSha256)
                || !preparedMavenCoordinatesJson.equals(coordinates)
                || !preparedMavenCoordinatesSha256.equals(coordinatesSha256)
                || !preparedDiffFingerprint.equals(diff))) throw conflict();
        preparedReplacementsJson = replacements;
        preparedReplacementsSha256 = replacementsSha256;
        preparedMavenCoordinatesJson = coordinates;
        preparedMavenCoordinatesSha256 = coordinatesSha256;
        preparedDiffFingerprint = diff;
        updatedAt = Instant.now();
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
