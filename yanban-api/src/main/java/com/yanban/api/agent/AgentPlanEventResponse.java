package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlanEvent;
import java.time.LocalDateTime;

public record AgentPlanEventResponse(
        Long id,
        Long planId,
        Long stepId,
        String eventType,
        String payloadJson,
        LocalDateTime createdAt
) {
    public static AgentPlanEventResponse from(AgentPlanEvent event) {
        return new AgentPlanEventResponse(
                event.getId(),
                event.getPlanId(),
                event.getStepId(),
                event.getEventType(),
                event.getPayloadJson(),
                event.getCreatedAt()
        );
    }
}
