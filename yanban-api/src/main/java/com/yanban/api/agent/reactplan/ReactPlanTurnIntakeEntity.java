package com.yanban.api.agent.reactplan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(name = "reactplan_turn_intakes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reactplan_turn_intake_request",
                columnNames = {"user_id", "session_id", "client_request_id"}),
        @UniqueConstraint(name = "uk_reactplan_turn_intake_turn", columnNames = "turn_id"),
        @UniqueConstraint(name = "uk_reactplan_turn_intake_task", columnNames = "task_id")})
final class ReactPlanTurnIntakeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "client_request_id", nullable = false, length = 128)
    private String clientRequestId;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @Column(name = "turn_id", nullable = false)
    private Long turnId;
    @Column(name = "user_message_id", nullable = false)
    private Long userMessageId;
    @Column(name = "task_id", nullable = false, length = 69)
    private String taskId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ReactPlanTurnIntakeEntity() { }

    ReactPlanTurnIntakeEntity(long userId, long sessionId, String clientRequestId,
                              String requestDigest, long turnId, long userMessageId,
                              String taskId, LocalDateTime createdAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.clientRequestId = clientRequestId;
        this.requestDigest = requestDigest;
        this.turnId = turnId;
        this.userMessageId = userMessageId;
        this.taskId = taskId;
        this.createdAt = createdAt;
    }

    String requestDigest() { return requestDigest; }
    long turnId() { return turnId; }
    String taskId() { return taskId; }
}
