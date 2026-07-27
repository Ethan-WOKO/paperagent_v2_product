package io.paperagent.v2.contracts;

import java.time.Instant;

public record ToolCall(
        ToolCallId id,
        ToolId toolId,
        TaskFrameId taskFrameId,
        PlanId planId,
        PlanStepId stepId,
        ObjectValue arguments,
        Instant requestedAt) {

    public ToolCall {
        Contracts.required(id, "toolCall.id");
        Contracts.required(toolId, "toolCall.toolId");
        Contracts.required(taskFrameId, "toolCall.taskFrameId");
        Contracts.required(planId, "toolCall.planId");
        Contracts.required(stepId, "toolCall.stepId");
        Contracts.required(arguments, "toolCall.arguments");
        Contracts.required(requestedAt, "toolCall.requestedAt");
    }
}
