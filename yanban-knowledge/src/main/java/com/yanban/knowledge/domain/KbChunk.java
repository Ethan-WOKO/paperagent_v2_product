package com.yanban.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "kb_chunks")
public class KbChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Lob
    @Column(name = "chunk_text", nullable = false, columnDefinition = "LONGTEXT")
    private String chunkText;

    @Column(name = "es_doc_id", length = 255)
    private String esDocId;

    @Column(name = "content_digest", length = 64)
    private String contentDigest;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KbChunk() {
    }

    public KbChunk(Long documentId, Integer chunkIndex, String chunkText) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
    }

    public KbChunk(Long id, Long documentId, Integer chunkIndex, String chunkText, String esDocId) {
        this.id = id;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.esDocId = esDocId;
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public String getChunkText() { return chunkText; }
    public Instant getCreatedAt() { return createdAt; }
    public String getEsDocId() { return esDocId; }
    public void setEsDocId(String esDocId) { this.esDocId = esDocId; }
    public String getContentDigest() { return contentDigest; }
    public void setContentDigest(String contentDigest) { this.contentDigest = contentDigest; }
}
