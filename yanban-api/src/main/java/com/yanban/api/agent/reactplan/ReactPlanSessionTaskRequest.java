package com.yanban.api.agent.reactplan;

public record ReactPlanSessionTaskRequest(
        String clientRequestId,
        String instruction,
        String provider,
        String model,
        String skillId) {

    public ReactPlanSessionTaskRequest {
        if (clientRequestId == null
                || !clientRequestId.matches("request\\.[A-Za-z0-9_-]{16,120}")) {
            throw new IllegalArgumentException("clientRequestId is invalid");
        }
        clientRequestId = clientRequestId.trim();
    }

    ReactPlanTaskRequest taskRequest() {
        return new ReactPlanTaskRequest(instruction, provider, model, skillId);
    }

    public ReactPlanSessionTaskRequest(String clientRequestId, String instruction,
                                       String provider, String model) {
        this(clientRequestId, instruction, provider, model, null);
    }
}
