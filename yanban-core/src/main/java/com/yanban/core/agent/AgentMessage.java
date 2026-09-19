package com.yanban.core.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@EntityListeners(AgentMessageChangeListener.class)
@Table(name = "agent_messages")
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String role;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Lob
    @Column(name = "tool_calls_json", columnDefinition = "LONGTEXT")
    private String toolCallsJson;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "paper_task_id")
    private Long paperTaskId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentMessage() {
    }

    public AgentMessage(Long sessionId, Long userId, String role, String content, String toolCallsJson, Long paperTaskId) {
        this(sessionId, userId, role, content, toolCallsJson, null, paperTaskId);
    }

    public AgentMessage(Long sessionId,
                        Long userId,
                        String role,
                        String content,
                        String toolCallsJson,
                        String toolCallId,
                        Long paperTaskId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.paperTaskId = paperTaskId;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCallsJson() { return toolCallsJson; }
    public String getToolCallId() { return toolCallId; }
    public Long getPaperTaskId() { return paperTaskId; }
    public Instant getCreatedAt() { return createdAt; }

    public void replaceContent(String content) {
        if (!"assistant".equalsIgnoreCase(role) || content == null || content.isBlank()) {
            throw new IllegalArgumentException("only an assistant message can receive non-blank replacement content");
        }
        this.content = content;
    }
}
