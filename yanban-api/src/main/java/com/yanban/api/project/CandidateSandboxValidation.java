package com.yanban.api.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "candidate_sandbox_validations")
class CandidateSandboxValidation {
    static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "CLEANUP_FAILED");

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "validation_id", nullable = false, length = 36) private String validationId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "artifact_id", nullable = false) private Long artifactId;
    @Column(name = "project_version", nullable = false, length = 64) private String projectVersion;
    @Column(name = "candidate_fingerprint", nullable = false, length = 64) private String candidateFingerprint;
    @Lob @Column(name = "accepted_change_indexes_json", nullable = false, columnDefinition = "LONGTEXT") private String acceptedChangeIndexesJson;
    @Column(name = "selection_digest", nullable = false, length = 64) private String selectionDigest;
    @Column(nullable = false, length = 32) private String profile;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @Column(name = "policy_digest", nullable = false, length = 64) private String policyDigest;
    @Lob @Column(name = "request_json", columnDefinition = "LONGTEXT") private String requestJson;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "broker_execution_id", length = 64) private String brokerExecutionId;
    @Column(name = "receipt_digest", length = 64) private String receiptDigest;
    @Lob @Column(name = "receipt_json", columnDefinition = "LONGTEXT") private String receiptJson;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "repair_origin_validation_id", length = 36) private String repairOriginValidationId;
    @Column(name = "repair_attempt") private Integer repairAttempt;
    @Lob @Column(name = "dependency_coordinates_json", columnDefinition = "TEXT") private String dependencyCoordinatesJson;
    @Lob @Column(name = "analysis_summary", columnDefinition = "TEXT") private String analysisSummary;
    @Column(name = "analysis_disclaimer", length = 255) private String analysisDisclaimer;
    @Column(name = "decision_status", nullable = false, length = 16) private String decisionStatus;
    @Column(name = "application_operation_id") private Long applicationOperationId;
    @Column(name = "applied_revision_id") private Long appliedRevisionId;
    @Column(name = "next_attempt_at") private LocalDateTime nextAttemptAt;
    @Column(name = "claim_token", length = 64) private String claimToken;
    @Column(name = "claim_expires_at") private LocalDateTime claimExpiresAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected CandidateSandboxValidation() { }

    CandidateSandboxValidation(String validationId, Long userId, Long projectId, Long sessionId, Long artifactId,
                               String projectVersion, String candidateFingerprint, String acceptedChangeIndexesJson,
                               String selectionDigest, String profile, String idempotencyKey, String requestHash,
                               String requestDigest, String policyDigest, String requestJson, LocalDateTime now) {
        this.validationId = validationId; this.userId = userId; this.projectId = projectId;
        this.sessionId = sessionId; this.artifactId = artifactId; this.projectVersion = projectVersion;
        this.candidateFingerprint = candidateFingerprint; this.acceptedChangeIndexesJson = acceptedChangeIndexesJson;
        this.selectionDigest = selectionDigest; this.profile = profile; this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash; this.requestDigest = requestDigest; this.policyDigest = policyDigest;
        this.requestJson = requestJson; this.status = "QUEUED"; this.decisionStatus = "PENDING";
        this.createdAt = now; this.updatedAt = now;
    }

    Long id() { return id; } String validationId() { return validationId; } Long userId() { return userId; }
    Long projectId() { return projectId; } Long sessionId() { return sessionId; } Long artifactId() { return artifactId; }
    String projectVersion() { return projectVersion; } String candidateFingerprint() { return candidateFingerprint; }
    String acceptedChangeIndexesJson() { return acceptedChangeIndexesJson; } String selectionDigest() { return selectionDigest; }
    String profile() { return profile; } String idempotencyKey() { return idempotencyKey; } String requestHash() { return requestHash; }
    String requestDigest() { return requestDigest; } String policyDigest() { return policyDigest; } String requestJson() { return requestJson; }
    String status() { return status; } String brokerExecutionId() { return brokerExecutionId; } String receiptDigest() { return receiptDigest; }
    String receiptJson() { return receiptJson; } String errorCode() { return errorCode; } String analysisSummary() { return analysisSummary; }
    String repairOriginValidationId() { return repairOriginValidationId; }
    Integer repairAttempt() { return repairAttempt; }
    String dependencyCoordinatesJson() { return dependencyCoordinatesJson; }
    String analysisDisclaimer() { return analysisDisclaimer; } String decisionStatus() { return decisionStatus; }
    Long applicationOperationId() { return applicationOperationId; } Long appliedRevisionId() { return appliedRevisionId; }
    LocalDateTime nextAttemptAt() { return nextAttemptAt; } LocalDateTime claimExpiresAt() { return claimExpiresAt; }
    LocalDateTime createdAt() { return createdAt; } LocalDateTime updatedAt() { return updatedAt; }

    boolean claimable(LocalDateTime now) {
        return !TERMINAL.contains(status) && (nextAttemptAt == null || !nextAttemptAt.isAfter(now))
                && (claimExpiresAt == null || !claimExpiresAt.isAfter(now));
    }
    String claim(LocalDateTime now) { claimToken = java.util.UUID.randomUUID().toString(); claimExpiresAt = now.plusSeconds(30); updatedAt = now; return claimToken; }
    boolean owns(String token, LocalDateTime now) { return java.util.Objects.equals(claimToken, token) && claimExpiresAt != null && claimExpiresAt.isAfter(now); }
    void dispatched(String executionId, String nextStatus, LocalDateTime now) { brokerExecutionId = executionId; status = nextStatus; errorCode = null; nextAttemptAt = now; release(now); }
    void polled(String nextStatus, LocalDateTime now) {
        if ("CANCEL_REQUESTED".equals(status)) nextAttemptAt = now;
        else { status = nextStatus; nextAttemptAt = now.plusSeconds(1); }
        release(now);
    }
    void retry(String code, LocalDateTime now) { if (!"CANCEL_REQUESTED".equals(status)) status = "RETRY"; errorCode = code; nextAttemptAt = now.plusSeconds(5); release(now); }
    void fail(String code, LocalDateTime now) {
        status = "FAILED"; errorCode = code; requestJson = null; nextAttemptAt = null; release(now);
    }
    void requestCancel(LocalDateTime now) { if (!TERMINAL.contains(status)) { status = "CANCEL_REQUESTED"; nextAttemptAt = now; release(now); } }
    void cancelledBeforeDispatch(LocalDateTime now) { status = "CANCELLED"; errorCode = "USER_CANCELLED"; requestJson = null; nextAttemptAt = null; release(now); }
    void complete(String terminalStatus, String digest, String receipt, String code, LocalDateTime now) {
        status = terminalStatus; receiptDigest = digest; receiptJson = receipt; errorCode = code;
        requestJson = null; nextAttemptAt = null; release(now);
    }
    void markRepair(String originValidationId, int attempt, String coordinatesJson, LocalDateTime now) {
        if (brokerExecutionId != null || !"QUEUED".equals(status) || repairOriginValidationId != null
                || attempt != 1) throw new IllegalStateException("invalid repair validation authority");
        repairOriginValidationId = originValidationId;
        repairAttempt = attempt;
        dependencyCoordinatesJson = coordinatesJson;
        updatedAt = now;
    }
    void completeLocal(String summary, String disclaimer, LocalDateTime now) {
        status = "SUCCEEDED"; receiptDigest = null; receiptJson = null; errorCode = null;
        requestJson = null; nextAttemptAt = null; analysisSummary = summary;
        analysisDisclaimer = disclaimer; release(now);
    }
    void saveAnalysis(String summary, String disclaimer, LocalDateTime now) { if (analysisSummary == null) { analysisSummary = summary; analysisDisclaimer = disclaimer; updatedAt = now; } }
    void reject(LocalDateTime now) { if (!"APPLIED".equals(decisionStatus)) { decisionStatus = "REJECTED"; requestCancel(now); } }
    void applied(Long operationId, Long revisionId, LocalDateTime now) { decisionStatus = "APPLIED"; applicationOperationId = operationId; appliedRevisionId = revisionId; updatedAt = now; }
    private void release(LocalDateTime now) { claimToken = null; claimExpiresAt = null; updatedAt = now; }
}
