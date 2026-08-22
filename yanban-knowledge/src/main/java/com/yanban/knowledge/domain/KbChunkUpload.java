package com.yanban.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "kb_chunk_uploads")
public class KbChunkUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "upload_id", nullable = false, length = 64)
    private String uploadId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "chunk_number", nullable = false)
    private Integer chunkNumber;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    @Column(name = "chunk_md5", length = 64)
    private String chunkMd5;

    @Column(name = "chunk_digest", length = 64)
    private String chunkDigest;

    @Column(name = "temp_object_key", nullable = false, length = 512)
    private String tempObjectKey;

    @Column(nullable = false, length = 32)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbChunkUpload() {
    }

    public KbChunkUpload(Long userId, String uploadId, String filename, Integer chunkNumber, Integer totalChunks,
                         Long chunkSize, String chunkMd5, String tempObjectKey, String status) {
        this(userId, uploadId, filename, chunkNumber, totalChunks, chunkSize, chunkMd5, null, tempObjectKey, status);
    }

    public KbChunkUpload(Long userId, String uploadId, String filename, Integer chunkNumber, Integer totalChunks,
                         Long chunkSize, String chunkMd5, String chunkDigest, String tempObjectKey, String status) {
        this.userId = userId;
        this.uploadId = uploadId;
        this.filename = filename;
        this.chunkNumber = chunkNumber;
        this.totalChunks = totalChunks;
        this.chunkSize = chunkSize;
        this.chunkMd5 = chunkMd5;
        this.chunkDigest = chunkDigest;
        this.tempObjectKey = tempObjectKey;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getUploadId() { return uploadId; }
    public String getFilename() { return filename; }
    public Integer getChunkNumber() { return chunkNumber; }
    public Integer getTotalChunks() { return totalChunks; }
    public Long getChunkSize() { return chunkSize; }
    public String getChunkMd5() { return chunkMd5; }
    public String getChunkDigest() { return chunkDigest; }
    public String getTempObjectKey() { return tempObjectKey; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void markMerged() { this.status = "MERGED"; }
}
