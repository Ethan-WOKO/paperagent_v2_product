package com.yanban.api.ws;

public record WsChatRequest(
        Long sessionId,
        String content,
        Boolean ragDisabled,
        String skillId,
        String clientRequestId
) {
}
