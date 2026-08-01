package com.yanban.api.agent.v2.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_v2_turn_intakes")
class V2TurnIntakeEntity {
    static final String RUNNING = "RUNNING";
    static final String DIRECT = "DIRECT";
    static final String PERSISTENT = "PERSISTENT";
    static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "client_request_id", nullable = false, length = 128)
    private String clientRequestId;
    @Column(name = "request_sha256", nullable = false, length = 64)
    private String requestSha256;
    @Lob
    @Column(name = "content_text", nullable = false, columnDefinition = "LONGTEXT")
    private String content;
    @Column(name = "rag_disabled", nullable = false)
    private Boolean ragDisabled;
    @Column(name = "skill_id", length = 128)
    private String skillId;
    @Lob
    @Column(name = "experiment_json", columnDefinition = "LONGTEXT")
    private String experimentJson;
    @Column(name = "user_message_id", nullable = false)
    private Long userMessageId;
    @Column(name = "turn_id", nullable = false)
    private Long turnId;
    @Column(name = "model_provider_snapshot", length = 64)
    private String modelProviderSnapshot;
    @Column(name = "model_snapshot", length = 128)
    private String modelSnapshot;
    @Column(name = "assistant_message_id")
    private Long assistantMessageId;
    @Column(name = "plan_id", length = 128)
    private String planId;
    @Lob
    @Column(name = "planner_output_json", columnDefinition = "LONGTEXT")
    private String plannerOutputJson;
    @Lob
    @Column(name = "capability_bindings_json", columnDefinition = "LONGTEXT")
    private String capabilityBindingsJson;
    @Column(name = "status", nullable = false, length = 32)
    private String status;
    @Column(name = "failure_code", length = 64)
    private String failureCode;
    @Column(name = "history_visible", nullable = false)
    private Boolean historyVisible;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected V2TurnIntakeEntity() {
    }

    V2TurnIntakeEntity(
            Long userId,
            Long sessionId,
            String clientRequestId,
            String requestSha256,
            String content,
            boolean ragDisabled,
            String skillId,
            String experimentJson,
            Long userMessageId,
            Long turnId,
            String modelProviderSnapshot,
            String modelSnapshot,
            Instant now) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.clientRequestId = clientRequestId;
        this.requestSha256 = requestSha256;
        this.content = content;
        this.ragDisabled = ragDisabled;
        this.skillId = skillId;
        this.experimentJson = experimentJson;
        this.userMessageId = userMessageId;
        this.turnId = turnId;
        this.modelProviderSnapshot = modelProviderSnapshot;
        this.modelSnapshot = modelSnapshot;
        this.status = RUNNING;
        this.historyVisible = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    V2TurnIntakeEntity(
            Long userId,
            Long sessionId,
            String clientRequestId,
            String requestSha256,
            String content,
            boolean ragDisabled,
            String skillId,
            String experimentJson,
            Long userMessageId,
            Long turnId,
            Instant now) {
        this(userId, sessionId, clientRequestId, requestSha256, content,
                ragDisabled, skillId, experimentJson, userMessageId, turnId,
                null, null, now);
    }

    Long userId() { return userId; }
    Long id() { return id; }
    Long sessionId() { return sessionId; }
    String clientRequestId() { return clientRequestId; }
    String requestSha256() { return requestSha256; }
    String content() { return content; }
    boolean ragDisabled() { return Boolean.TRUE.equals(ragDisabled); }
    String skillId() { return skillId; }
    String experimentJson() { return experimentJson; }
    Long userMessageId() { return userMessageId; }
    Long turnId() { return turnId; }
    String modelProviderSnapshot() { return modelProviderSnapshot; }
    String modelSnapshot() { return modelSnapshot; }
    Long assistantMessageId() { return assistantMessageId; }
    String planId() { return planId; }
    String plannerOutputJson() { return plannerOutputJson; }
    String capabilityBindingsJson() { return capabilityBindingsJson; }
    String status() { return status; }
    String failureCode() { return failureCode; }
    boolean historyVisible() { return Boolean.TRUE.equals(historyVisible); }
    Instant createdAt() { return createdAt; }
    Instant updatedAt() { return updatedAt; }

    void completeDirect(Long messageId, String outputJson, Instant now) {
        requireRunning();
        assistantMessageId = messageId;
        plannerOutputJson = outputJson;
        status = DIRECT;
        updatedAt = now;
    }

    void completePersistent(
            String value, String outputJson, String bindingsJson, Instant now) {
        requireRunning();
        planId = value;
        plannerOutputJson = outputJson;
        capabilityBindingsJson = bindingsJson;
        status = PERSISTENT;
        updatedAt = now;
    }

    void fail(String code, Instant now) {
        requireRunning();
        failureCode = code;
        status = FAILED;
        updatedAt = now;
    }

    void bindPersistentAssistant(Long messageId, Instant now) {
        if (!PERSISTENT.equals(status)) {
            throw new IllegalStateException(
                    "V2 persistent intake is not ready");
        }
        if (assistantMessageId != null
                && !assistantMessageId.equals(messageId)) {
            throw new IllegalStateException(
                    "V2 assistant message authority conflict");
        }
        assistantMessageId = messageId;
        updatedAt = now;
    }

    private void requireRunning() {
        if (!RUNNING.equals(status)) {
            throw new IllegalStateException("V2 turn intake is already terminal");
        }
    }
}
