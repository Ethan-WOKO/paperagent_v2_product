package com.yanban.api.ws;

import com.yanban.api.agent.AgentDebugPayload;
import com.yanban.api.agent.AgentStopReason;
import com.yanban.api.agent.CompletionStatus;
import com.yanban.api.agent.ProjectEvidenceResponse;
import java.util.List;

public record WsChatEvent(
        String type,
        String content,
        Long sessionId,
        String error,
        String finishReason,
        String navigationUrl,
        String clientRequestId,
        AgentDebugPayload debug,
        String assistantContent,
        List<ProjectEvidenceResponse> projectEvidence,
        CompletionStatus completionStatus,
        AgentStopReason stopReason,
        String outcome
) {
    public WsChatEvent(String type, String content, Long sessionId, String error, String finishReason,
                       String navigationUrl, String clientRequestId, AgentDebugPayload debug,
                       String assistantContent, List<ProjectEvidenceResponse> projectEvidence) {
        this(type, content, sessionId, error, finishReason, navigationUrl, clientRequestId, debug,
                assistantContent, projectEvidence, null, null, null);
    }

    public static WsChatEvent ack(Long sessionId, String clientRequestId) {
        return new WsChatEvent("ack", null, sessionId, null, null, null, clientRequestId, null, null, null);
    }

    public static WsChatEvent chunk(Long sessionId, String content, String clientRequestId) {
        return new WsChatEvent("chunk", content, sessionId, null, null, null, clientRequestId, null, null, null);
    }

    public static WsChatEvent process(Long sessionId, String content, String clientRequestId) {
        return new WsChatEvent("process", content, sessionId, null, null, null, clientRequestId, null, null, null);
    }

    public static WsChatEvent reset(Long sessionId, String clientRequestId) {
        return new WsChatEvent("reset", null, sessionId, null, null, null, clientRequestId, null, null, null);
    }

    public static WsChatEvent replace(Long sessionId, String assistantContent, String clientRequestId) {
        return new WsChatEvent("replace", null, sessionId, null, null, null, clientRequestId, null,
                assistantContent, null);
    }

    public static WsChatEvent done(Long sessionId, String finishReason, String clientRequestId) {
        return new WsChatEvent("done", null, sessionId, null, finishReason, null, clientRequestId, null, null, null);
    }

    public static WsChatEvent doneWithNavigation(Long sessionId, String finishReason, String navigationUrl, String clientRequestId) {
        return new WsChatEvent("done", null, sessionId, null, finishReason, navigationUrl, clientRequestId, null, null, null);
    }

    public static WsChatEvent projectDone(Long sessionId,
                                          String finishReason,
                                          String navigationUrl,
                                          String clientRequestId,
                                          String assistantContent,
                                          List<ProjectEvidenceResponse> projectEvidence) {
        return projectDone(sessionId, finishReason, navigationUrl, clientRequestId, assistantContent,
                projectEvidence, null, null, null);
    }

    public static WsChatEvent projectDone(Long sessionId,
                                          String finishReason,
                                          String navigationUrl,
                                          String clientRequestId,
                                          String assistantContent,
                                          List<ProjectEvidenceResponse> projectEvidence,
                                          CompletionStatus completionStatus,
                                          AgentStopReason stopReason,
                                          String outcome) {
        return new WsChatEvent("done", null, sessionId, null, finishReason, navigationUrl, clientRequestId, null,
                assistantContent,
                projectEvidence == null ? List.of() : List.copyOf(projectEvidence),
                completionStatus, stopReason, outcome);
    }

    public static WsChatEvent error(Long sessionId, String error, String clientRequestId) {
        return new WsChatEvent("error", null, sessionId, error, null, null, clientRequestId, null, null, null);
    }

    public static WsChatEvent debug(Long sessionId, AgentDebugPayload debug, String clientRequestId) {
        return new WsChatEvent("debug", null, sessionId, null, null, null, clientRequestId, debug, null, null);
    }
}
