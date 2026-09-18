package com.yanban.api.attachment;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_session_attachments")
public class SessionAttachment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable=false) Long userId;
    @Column(nullable=false) Long sessionId;
    @Column(nullable=false, length=255) String filename;
    @Column(nullable=false, length=128) String mimeType;
    @Column(nullable=false, length=512) String objectKey;
    @Column(nullable=false) long fileSize;
    @Column(nullable=false, length=20) String status;
    @Lob @Column(columnDefinition="LONGTEXT") String extractedText;
    @Column(length=512) String errorMessage;
    @Column(nullable=false) boolean active = true;
    Long firstMessageId;
    Long knowledgeDocumentId;
    @Column(nullable=false) Instant createdAt = Instant.now();
    protected SessionAttachment() {}
    SessionAttachment(Long userId, Long sessionId, String filename, String mimeType, String key, long size) {
        this.userId=userId; this.sessionId=sessionId; this.filename=filename;
        this.mimeType=mimeType; this.objectKey=key; this.fileSize=size; this.status="PROCESSING";
    }
    boolean image() { return mimeType.startsWith("image/"); }
    public record View(Long id, String filename, String mimeType, long fileSize, String status,
                       String errorMessage, Long firstMessageId, Long knowledgeDocumentId) {}
    View view() { return new View(id,filename,mimeType,fileSize,status,errorMessage,firstMessageId,knowledgeDocumentId); }
}
