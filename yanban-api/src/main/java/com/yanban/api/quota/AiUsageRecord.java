package com.yanban.api.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "ai_usage_records")
public class AiUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String feature;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiUsageRecord() {
    }

    public AiUsageRecord(Long userId, String feature, long promptTokens, long completionTokens, long totalTokens) {
        this.userId = userId;
        this.feature = feature == null || feature.isBlank() ? "CHAT" : feature.trim().toUpperCase();
        this.promptTokens = Math.max(0L, promptTokens);
        this.completionTokens = Math.max(0L, completionTokens);
        this.totalTokens = Math.max(0L, totalTokens);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getFeature() { return feature; }
    public long getPromptTokens() { return promptTokens; }
    public long getCompletionTokens() { return completionTokens; }
    public long getTotalTokens() { return totalTokens; }
    public Instant getCreatedAt() { return createdAt; }
}
