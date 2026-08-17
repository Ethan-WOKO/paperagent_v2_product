package com.yanban.api.agent.engine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "agent_engine_product_turns")
class ProductEngineTurnEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(name = "engine_mode", nullable = false, length = 16)
    private ProductEngineMode engineMode;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "project_version", nullable = false, length = 64) private String projectVersion;
    @Column(name = "root_client_request_id", nullable = false, length = 128) private String rootClientRequestId;
    @Column(name = "engine_task_id", nullable = false, length = 69) private String engineTaskId;
    @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @Column(name = "product_request_digest", nullable = false, length = 64) private String productRequestDigest;
    @Lob @Column(name = "authority_json", nullable = false, columnDefinition = "LONGTEXT") private String authorityJson;
    @Lob @Column(name = "question", nullable = false, columnDefinition = "LONGTEXT") private String question;
    @Column(name = "user_message_id", nullable = false) private Long userMessageId;
    @Column(name = "agent_turn_id", nullable = false) private Long agentTurnId;
    @Column(name = "assistant_message_id") private Long assistantMessageId;
    @Column(name = "engine_state", nullable = false, length = 32) private String engineState;
    @Column(name = "last_sequence", nullable = false) private Long lastSequence;
    @Column(name = "pending_question_id", length = 128) private String pendingQuestionId;
    @Lob @Column(name = "pending_question_text", columnDefinition = "TEXT") private String pendingQuestionText;
    @Lob @Column(name = "final_text", columnDefinition = "LONGTEXT") private String finalText;
    @Lob @Column(name = "receipt_refs_json", columnDefinition = "LONGTEXT") private String receiptRefsJson;
    @Column(name = "failure_category", length = 32) private String failureCategory;
    @Column(name = "failure_code", length = 96) private String failureCode;
    @Column(name = "last_answer_client_request_id", length = 128) private String lastAnswerClientRequestId;
    @Column(name = "last_answer_digest", length = 64) private String lastAnswerDigest;
    @Column(name = "last_answer_question_id", length = 128) private String lastAnswerQuestionId;
    @Column(name = "last_cancel_client_request_id", length = 128) private String lastCancelClientRequestId;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ProductEngineTurnEntity() { }

    ProductEngineTurnEntity(ProductEngineMode mode, long userId, long sessionId, long projectId,
                            String projectVersion, String rootClientRequestId, String engineTaskId,
                            String requestDigest, String productRequestDigest, String authorityJson, String question,
                            long userMessageId, long agentTurnId) {
        this.engineMode = mode; this.userId = userId; this.sessionId = sessionId;
        this.projectId = projectId; this.projectVersion = projectVersion;
        this.rootClientRequestId = rootClientRequestId; this.engineTaskId = engineTaskId;
        this.requestDigest = requestDigest; this.productRequestDigest = productRequestDigest;
        this.authorityJson = authorityJson; this.question = question;
        this.userMessageId = userMessageId; this.agentTurnId = agentTurnId;
        this.engineState = "queued"; this.lastSequence = 0L; this.receiptRefsJson = "[]";
    }

    void applyEvent(ProductEngineDtos.Event event, String receiptsJson) {
        lastSequence = event.sequence();
        if ("status".equals(event.type())) {
            engineState = event.state();
            if (event.error() != null) {
                failureCategory = event.error().category();
                failureCode = event.error().code();
            }
            if (!"waiting_user".equals(event.state())) {
                pendingQuestionId = null;
                pendingQuestionText = null;
            }
        } else if ("question".equals(event.type())) {
            pendingQuestionId = event.questionId(); pendingQuestionText = event.text();
        } else if ("delivery".equals(event.type())) {
            finalText = event.conclusion(); receiptRefsJson = receiptsJson;
        }
    }

    void recordAnswer(String clientRequestId, String questionId, String digest) {
        lastAnswerClientRequestId = clientRequestId; lastAnswerQuestionId = questionId; lastAnswerDigest = digest;
    }
    void recordCancel(String clientRequestId) { lastCancelClientRequestId = clientRequestId; }
    void bindAssistant(long messageId) { assistantMessageId = messageId; }

    Long id() { return id; } ProductEngineMode mode() { return engineMode; }
    Long userId() { return userId; } Long sessionId() { return sessionId; } Long projectId() { return projectId; }
    String projectVersion() { return projectVersion; } String rootClientRequestId() { return rootClientRequestId; }
    String engineTaskId() { return engineTaskId; } String requestDigest() { return requestDigest; }
    String productRequestDigest() { return productRequestDigest; }
    String authorityJson() { return authorityJson; } String question() { return question; }
    Long userMessageId() { return userMessageId; } Long agentTurnId() { return agentTurnId; }
    Long assistantMessageId() { return assistantMessageId; } String engineState() { return engineState; }
    long lastSequence() { return lastSequence; } String pendingQuestionId() { return pendingQuestionId; }
    String pendingQuestionText() { return pendingQuestionText; } String finalText() { return finalText; }
    String receiptRefsJson() { return receiptRefsJson; } String failureCategory() { return failureCategory; }
    String failureCode() { return failureCode; } String lastAnswerClientRequestId() { return lastAnswerClientRequestId; }
    String lastAnswerDigest() { return lastAnswerDigest; } String lastAnswerQuestionId() { return lastAnswerQuestionId; }
    String lastCancelClientRequestId() { return lastCancelClientRequestId; }
    Instant createdAt() { return createdAt; } Instant updatedAt() { return updatedAt; }
}
