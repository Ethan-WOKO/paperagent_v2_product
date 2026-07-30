package com.yanban.api.project;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Immutable source authority plus append-only outcome for one bounded Candidate repair. */
@Entity
@Table(name = "candidate_validation_repairs")
class CandidateValidationRepair {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "source_validation_id", nullable = false, unique = true, length = 36) private String sourceValidationId;
    @Column(name = "source_candidate_artifact_id", nullable = false) private Long sourceCandidateArtifactId;
    @Column(name = "source_candidate_fingerprint", nullable = false, length = 64) private String sourceCandidateFingerprint;
    @Column(name = "selected_change_index", nullable = false) private Integer selectedChangeIndex;
    @Column(name = "selected_path", nullable = false, length = 512) private String selectedPath;
    @Column(name = "failed_receipt_digest", nullable = false, length = 64) private String failedReceiptDigest;
    @Column(name = "project_version", nullable = false, length = 64) private String projectVersion;
    @Column(nullable = false) private Integer attempt;
    @Column(name = "max_attempts", nullable = false) private Integer maxAttempts;
    @Lob @Column(name = "source_replacement_text", nullable = false, columnDefinition = "LONGTEXT") private String sourceReplacementText;
    @Column(name = "source_replacement_digest", nullable = false, length = 64) private String sourceReplacementDigest;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "repaired_artifact_id") private Long repairedArtifactId;
    @Column(name = "repaired_validation_id", length = 36) private String repairedValidationId;
    @Lob @Column(name = "dependency_coordinates_json", columnDefinition = "TEXT") private String dependencyCoordinatesJson;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected CandidateValidationRepair() {}

    CandidateValidationRepair(String sourceValidationId, Long sourceCandidateArtifactId,
            String sourceCandidateFingerprint, Integer selectedChangeIndex, String selectedPath,
            String failedReceiptDigest, String projectVersion, String sourceReplacementText,
            String sourceReplacementDigest, LocalDateTime now) {
        this.sourceValidationId = sourceValidationId;
        this.sourceCandidateArtifactId = sourceCandidateArtifactId;
        this.sourceCandidateFingerprint = sourceCandidateFingerprint;
        this.selectedChangeIndex = selectedChangeIndex;
        this.selectedPath = selectedPath;
        this.failedReceiptDigest = failedReceiptDigest;
        this.projectVersion = projectVersion;
        this.attempt = 1;
        this.maxAttempts = 1;
        this.sourceReplacementText = sourceReplacementText;
        this.sourceReplacementDigest = sourceReplacementDigest;
        this.status = "PENDING";
        this.createdAt = now;
        this.updatedAt = now;
    }

    String sourceValidationId() { return sourceValidationId; }
    Long sourceCandidateArtifactId() { return sourceCandidateArtifactId; }
    String sourceCandidateFingerprint() { return sourceCandidateFingerprint; }
    Integer selectedChangeIndex() { return selectedChangeIndex; }
    String selectedPath() { return selectedPath; }
    String failedReceiptDigest() { return failedReceiptDigest; }
    String projectVersion() { return projectVersion; }
    Integer attempt() { return attempt; }
    Integer maxAttempts() { return maxAttempts; }
    String sourceReplacementText() { return sourceReplacementText; }
    String sourceReplacementDigest() { return sourceReplacementDigest; }
    String status() { return status; }

    void completed(long artifactId, String validationId, String coordinatesJson, LocalDateTime now) {
        if (!"PENDING".equals(status)) throw new IllegalStateException("repair outcome already exists");
        repairedArtifactId = artifactId;
        repairedValidationId = validationId;
        dependencyCoordinatesJson = coordinatesJson;
        status = "COMPLETED";
        updatedAt = now;
    }

    void rejected(LocalDateTime now) {
        if ("PENDING".equals(status)) {
            status = "REJECTED";
            updatedAt = now;
        }
    }
}
