package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.runtime.routing.RoutingDecision;

import java.time.Instant;
import java.util.Optional;

public record TaskFrameFreezeRequest(
        RoutingDecision routingDecision,
        TaskFrameId taskFrameId,
        TaskFrameDraft draft,
        Optional<ProjectVersionRef> sourceProjectVersion,
        ExecutionProfile executionProfile,
        Instant createdAt) {

    public TaskFrameFreezeRequest {
        TaskFrameFreezeValues.required(
                routingDecision,
                "taskFrameFreezeRequest.routingDecision");
        TaskFrameFreezeValues.required(
                taskFrameId,
                "taskFrameFreezeRequest.taskFrameId");
        TaskFrameFreezeValues.required(draft, "taskFrameFreezeRequest.draft");
        TaskFrameFreezeValues.required(
                sourceProjectVersion,
                "taskFrameFreezeRequest.sourceProjectVersion");
        TaskFrameFreezeValues.required(
                executionProfile,
                "taskFrameFreezeRequest.executionProfile");
        TaskFrameFreezeValues.required(
                createdAt,
                "taskFrameFreezeRequest.createdAt");
    }
}
