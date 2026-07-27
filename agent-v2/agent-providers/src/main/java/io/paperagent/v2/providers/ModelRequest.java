package io.paperagent.v2.providers;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ToolDescriptor;

import java.util.List;
import java.util.Optional;

public record ModelRequest(
        ModelRequestId requestId,
        CorrelationId correlationId,
        List<ModelMessage> messages,
        List<ToolDescriptor> availableTools,
        GenerationOptions generationOptions,
        Optional<TaskFrameId> taskFrameId,
        Optional<PlanId> planId,
        Optional<PlanRevisionId> planRevisionId,
        Optional<PlanStepId> stepId,
        boolean cancellationRequested) {

    public ModelRequest {
        ProviderValues.required(requestId, "modelRequest.requestId");
        ProviderValues.required(correlationId, "modelRequest.correlationId");
        messages = ProviderValues.list(messages, "modelRequest.messages");
        if (messages.isEmpty()) {
            ProviderValues.fail(
                    ProviderValidationCode.REQUIRED_COLLECTION_EMPTY,
                    "modelRequest.messages",
                    "at least one message is required");
        }
        availableTools = ProviderValues.list(
                availableTools,
                "modelRequest.availableTools");
        ProviderValues.unique(
                availableTools,
                ToolDescriptor::id,
                "modelRequest.availableTools");
        ProviderValues.required(generationOptions, "modelRequest.generationOptions");
        taskFrameId = ProviderValues.required(taskFrameId, "modelRequest.taskFrameId");
        planId = ProviderValues.required(planId, "modelRequest.planId");
        planRevisionId = ProviderValues.required(
                planRevisionId,
                "modelRequest.planRevisionId");
        stepId = ProviderValues.required(stepId, "modelRequest.stepId");
        validateReferences(taskFrameId, planId, planRevisionId, stepId);
    }

    private static void validateReferences(
            Optional<TaskFrameId> taskFrameId,
            Optional<PlanId> planId,
            Optional<PlanRevisionId> planRevisionId,
            Optional<PlanStepId> stepId) {
        if (planId.isPresent() && taskFrameId.isEmpty()) {
            inconsistent("modelRequest.planId", "plan reference requires taskFrameId");
        }
        if (planRevisionId.isPresent() && planId.isEmpty()) {
            inconsistent(
                    "modelRequest.planRevisionId",
                    "plan revision reference requires planId");
        }
        if (stepId.isPresent()
                && (taskFrameId.isEmpty() || planId.isEmpty() || planRevisionId.isEmpty())) {
            inconsistent(
                    "modelRequest.stepId",
                    "step reference requires taskFrameId, planId and planRevisionId");
        }
    }

    private static void inconsistent(String path, String message) {
        ProviderValues.fail(
                ProviderValidationCode.INCONSISTENT_REFERENCE,
                path,
                message);
    }
}
