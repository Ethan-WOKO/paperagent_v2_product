package com.yanban.api.agent;

/** Event envelope used by the authenticated HTTP/SSE chat transport. */
public record ChatStreamEvent(
        String type,
        String content,
        Long sessionId,
        String error,
        String finishReason,
        String navigationUrl,
        String clientRequestId,
        AgentDebugPayload debug,
        String assistantContent
) {
    static ChatStreamEvent ack(Long sessionId, String clientRequestId) {
        return new ChatStreamEvent("ack", null, sessionId, null, null, null,
                clientRequestId, null, null);
    }

    static ChatStreamEvent chunk(Long sessionId, String content, String clientRequestId) {
        return new ChatStreamEvent("chunk", content, sessionId, null, null, null,
                clientRequestId, null, null);
    }

    static ChatStreamEvent process(Long sessionId, String content, String clientRequestId) {
        return new ChatStreamEvent("process", content, sessionId, null, null, null,
                clientRequestId, null, null);
    }

    static ChatStreamEvent debug(Long sessionId, AgentDebugPayload debug, String clientRequestId) {
        return new ChatStreamEvent("debug", null, sessionId, null, null, null,
                clientRequestId, debug, null);
    }

    static ChatStreamEvent done(Long sessionId, SendMessageResponse response,
                                String clientRequestId, String finishReason) {
        return new ChatStreamEvent("done", null, sessionId, null, finishReason,
                response.navigationUrl(), clientRequestId, null, response.assistantContent());
    }

    static ChatStreamEvent error(Long sessionId, String error, String clientRequestId) {
        return new ChatStreamEvent("error", null, sessionId, error, null, null,
                clientRequestId, null, null);
    }
}
