package com.yanban.api.agent.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

final class ProductEngineDtos {
    private ProductEngineDtos() { }

    record Project(String projectId, String projectVersion) { }
    record Permissions(boolean readProject, boolean writeWorkspace, boolean executeSandbox) { }
    record Model(String provider, String model) { }
    record ProductRequestFingerprint(String content, Boolean ragDisabled, String skillId,
                                     String instructionKind, String targetClientRequestId) { }
    record Authority(String runMode, String sessionRef, Project project, String instruction,
                     Permissions permissions, Model model) { }
    record Gateway(String taskGrant, Instant expiresAt) { }
    record Submission(String contractVersion, String taskId, String requestDigest,
                      Authority authority, Gateway gateway) { }
    record Accepted(String contractVersion, boolean replayed, TaskView task) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record TaskView(String contractVersion, String taskId, String requestDigest, String state,
                    long lastSequence, String pendingQuestionId, Long deliverySequence,
                    Long terminalSequence, Problem error, Instant createdAt, Instant updatedAt) { }
    record Problem(String contractVersion, String code, String category, String message,
                   boolean retryable, String sourceRef) { }
    record Cancel(String contractVersion, String clientRequestId) { }
    record Answer(String contractVersion, String clientRequestId, String questionId,
                  String answer, String answerDigest) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Event(String contractVersion, String taskId, long sequence, Instant occurredAt,
                 String type, String state, Problem error, String content, String questionId,
                 String text, String callId, String name, String inputSummary,
                 String outputSummary, String receiptRef, String conclusion,
                 List<String> receiptRefs) { }
}
