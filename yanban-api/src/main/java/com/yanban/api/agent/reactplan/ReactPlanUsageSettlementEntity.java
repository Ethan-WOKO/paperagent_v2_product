package com.yanban.api.agent.reactplan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "reactplan_usage_settlements")
final class ReactPlanUsageSettlementEntity {
    static final String PENDING = "PENDING";
    static final String SETTLED = "SETTLED";

    @Id
    @Column(name = "task_id", length = 69)
    private String taskId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;
    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;
    @Column(name = "state", nullable = false, length = 16)
    private String state;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ReactPlanUsageSettlementEntity() { }

    ReactPlanUsageSettlementEntity(String taskId, long userId, long promptTokens,
                                   long completionTokens, LocalDateTime now) {
        this.taskId = taskId;
        this.userId = userId;
        this.promptTokens = Math.max(0L, promptTokens);
        this.completionTokens = Math.max(0L, completionTokens);
        this.state = PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void settle(LocalDateTime now) {
        state = SETTLED;
        updatedAt = now;
    }

    String taskId() { return taskId; }
    long userId() { return userId; }
    long promptTokens() { return promptTokens; }
    long completionTokens() { return completionTokens; }
    String state() { return state; }
}
