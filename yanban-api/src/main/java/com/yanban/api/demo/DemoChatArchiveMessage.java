package com.yanban.api.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "demo_chat_archive_messages")
public class DemoChatArchiveMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "archive_session_id", nullable = false)
    private Long archiveSessionId;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(nullable = false, length = 32)
    private String role;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "message_created_at", nullable = false)
    private Instant messageCreatedAt;

    @Column(nullable = false)
    private Boolean deletable;

    protected DemoChatArchiveMessage() {
    }

    DemoChatArchiveMessage(Long archiveSessionId,
                           Long sourceMessageId,
                           String role,
                           String content,
                           Instant messageCreatedAt,
                           boolean deletable) {
        this.archiveSessionId = archiveSessionId;
        this.sourceMessageId = sourceMessageId;
        this.role = role;
        this.content = content;
        this.messageCreatedAt = messageCreatedAt;
        this.deletable = deletable;
    }

    public Long getId() { return id; }
    public Long getArchiveSessionId() { return archiveSessionId; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getMessageCreatedAt() { return messageCreatedAt; }
    public Boolean getDeletable() { return deletable; }
}
