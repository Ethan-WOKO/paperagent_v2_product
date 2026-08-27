package com.yanban.api.demo;

import com.yanban.core.agent.AgentSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "demo_chat_archive_sessions")
public class DemoChatArchiveSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_session_id", nullable = false, unique = true)
    private Long sourceSessionId;

    @Column(length = 255)
    private String title;

    @Column(nullable = false, length = 32)
    private String scope;

    @Column(name = "source_project_id")
    private Long sourceProjectId;

    @Column(name = "model_provider_snapshot", nullable = false, length = 64)
    private String modelProviderSnapshot;

    @Column(name = "model_snapshot", nullable = false, length = 128)
    private String modelSnapshot;

    @Column(name = "session_created_at", nullable = false)
    private Instant sessionCreatedAt;

    @Column(name = "session_updated_at", nullable = false)
    private Instant sessionUpdatedAt;

    @Column(name = "archived_at", nullable = false, updatable = false)
    private Instant archivedAt;

    protected DemoChatArchiveSession() {
    }

    DemoChatArchiveSession(AgentSession session, Instant archivedAt) {
        this.userId = session.getUserId();
        this.sourceSessionId = session.getId();
        this.title = session.getTitle();
        this.scope = session.getScope().name();
        this.sourceProjectId = session.getProjectId();
        this.modelProviderSnapshot = session.getModelProviderSnapshot();
        this.modelSnapshot = session.getModelSnapshot();
        this.sessionCreatedAt = session.getCreatedAt();
        this.sessionUpdatedAt = session.getUpdatedAt();
        this.archivedAt = archivedAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSourceSessionId() { return sourceSessionId; }
    public String getTitle() { return title; }
    public String getScope() { return scope; }
    public Long getSourceProjectId() { return sourceProjectId; }
    public String getModelProviderSnapshot() { return modelProviderSnapshot; }
    public String getModelSnapshot() { return modelSnapshot; }
    public Instant getSessionCreatedAt() { return sessionCreatedAt; }
    public Instant getSessionUpdatedAt() { return sessionUpdatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
