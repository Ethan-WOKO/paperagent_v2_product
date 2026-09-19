package com.yanban.api.agent.reactplan;

public record ReactPlanSessionTaskRequest(
        String clientRequestId,
        String instruction,
        String provider,
        String model,
        String skillId,
        String engine) {

    public ReactPlanSessionTaskRequest {
        if (clientRequestId == null
                || !clientRequestId.matches("request\\.[A-Za-z0-9_-]{16,120}")) {
            throw new IllegalArgumentException("clientRequestId is invalid");
        }
        clientRequestId = clientRequestId.trim();
        engine = ReactPlanEngineSelection.normalize(engine);
    }

    ReactPlanTaskRequest taskRequest() {
        return new ReactPlanTaskRequest(instruction, provider, model, skillId, engine);
    }

    public ReactPlanSessionTaskRequest(String clientRequestId, String instruction,
                                       String provider, String model) {
        this(clientRequestId, instruction, provider, model, null);
    }

    public ReactPlanSessionTaskRequest(String clientRequestId, String instruction,
                                       String provider, String model, String skillId) {
        this(clientRequestId, instruction, provider, model, skillId, "TS");
    }
}
