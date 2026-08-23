package com.yanban.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "kb_documents")
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType = "USER_UPLOAD";

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "lineage_id", length = 64)
    private String lineageId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo = 1;

    @Column(name = "version_status", nullable = false, length = 32)
    private String versionStatus = "ACTIVE";

    @Column(name = "source_task_type", length = 64)
    private String sourceTaskType;

    @Column(name = "source_task_id")
    private Long sourceTaskId;

    @Column(name = "source_artifact_id")
    private Long sourceArtifactId;

    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Column(name = "canonical_key", length = 128)
    private String canonicalKey;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "mime_type", length = 255)
    private String mimeType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "upload_id", length = 64)
    private String uploadId;

    @Column(name = "file_digest", length = 64)
    private String fileDigest;

    @Column(name = "processing_event_id", length = 64)
    private String processingEventId;

    @Column(name = "processing_revision", nullable = false)
    private Long processingRevision = 0L;

    @Column(name = "processed_at")
    private Instant processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbDocument() {
    }

    public KbDocument(Long userId, String filename, String status, Boolean isPublic) {
        this.userId = userId;
        this.filename = filename;
        this.status = status;
        this.isPublic = isPublic;
        this.sourceType = "USER_UPLOAD";
        this.versionNo = 1;
        this.versionStatus = "ACTIVE";
    }

    @PrePersist
    void applyVersionDefaults() {
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = "USER_UPLOAD";
        }
        if (versionNo == null || versionNo < 1) {
            versionNo = 1;
        }
        if (versionStatus == null || versionStatus.isBlank()) {
            versionStatus = "ACTIVE";
        }
        if (effectiveAt == null) {
            effectiveAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getFilename() { return filename; }
    public String getStatus() { return status; }
    public Boolean getIsPublic() { return isPublic; }
    public String getSourceType() { return sourceType; }
    public Long getProjectId() { return projectId; }
    public String getLineageId() { return lineageId; }
    public Integer getVersionNo() { return versionNo; }
    public String getVersionStatus() { return versionStatus; }
    public String getSourceTaskType() { return sourceTaskType; }
    public Long getSourceTaskId() { return sourceTaskId; }
    public Long getSourceArtifactId() { return sourceArtifactId; }
    public Long getSourceDocumentId() { return sourceDocumentId; }
    public String getCanonicalKey() { return canonicalKey; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public Instant getSupersededAt() { return supersededAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getObjectKey() { return objectKey; }
    public String getMimeType() { return mimeType; }
    public Long getFileSize() { return fileSize; }
    public String getErrorMessage() { return errorMessage; }
    public String getUploadId() { return uploadId; }
    public String getFileDigest() { return fileDigest; }
    public String getProcessingEventId() { return processingEventId; }
    public Long getProcessingRevision() { return processingRevision; }
    public Instant getProcessedAt() { return processedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType == null || sourceType.isBlank() ? "USER_UPLOAD" : sourceType; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public void setLineageId(String lineageId) { this.lineageId = blankToNull(lineageId); }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo == null || versionNo < 1 ? 1 : versionNo; }
    public void setVersionStatus(String versionStatus) { this.versionStatus = versionStatus == null || versionStatus.isBlank() ? "ACTIVE" : versionStatus; }
    public void setSourceTaskType(String sourceTaskType) { this.sourceTaskType = blankToNull(sourceTaskType); }
    public void setSourceTaskId(Long sourceTaskId) { this.sourceTaskId = sourceTaskId; }
    public void setSourceArtifactId(Long sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }
    public void setSourceDocumentId(Long sourceDocumentId) { this.sourceDocumentId = sourceDocumentId; }
    public void setCanonicalKey(String canonicalKey) { this.canonicalKey = blankToNull(canonicalKey); }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public void setSupersededAt(Instant supersededAt) { this.supersededAt = supersededAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setUploadId(String uploadId) { this.uploadId = blankToNull(uploadId); }
    public void setFileDigest(String fileDigest) { this.fileDigest = blankToNull(fileDigest); }
    public void setProcessingEventId(String processingEventId) { this.processingEventId = blankToNull(processingEventId); }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public void startProcessing(String eventId) {
        this.processingEventId = eventId;
        this.processingRevision = (processingRevision == null ? 0L : processingRevision) + 1L;
        this.status = "PROCESSING";
        this.errorMessage = null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
