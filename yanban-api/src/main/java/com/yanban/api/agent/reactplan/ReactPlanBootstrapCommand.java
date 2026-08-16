package com.yanban.api.agent.reactplan;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;

import java.time.Instant;
import java.util.Objects;

/** Product-authoritative inputs for the deterministic ReAct Plan shell. */
public record ReactPlanBootstrapCommand(
        RoutingDecision routingDecision,
        TaskFrameDraft taskFrameDraft,
        ExecutionProfile executionProfile,
        BoundedExecutionHints executionHints,
        Instant taskFrameCreatedAt,
        Instant planCreatedAt,
        Instant checkpointCreatedAt) {

    public ReactPlanBootstrapCommand {
        Objects.requireNonNull(routingDecision, "routingDecision");
        Objects.requireNonNull(taskFrameDraft, "taskFrameDraft");
        Objects.requireNonNull(executionProfile, "executionProfile");
        Objects.requireNonNull(executionHints, "executionHints");
        Objects.requireNonNull(taskFrameCreatedAt, "taskFrameCreatedAt");
        Objects.requireNonNull(planCreatedAt, "planCreatedAt");
        Objects.requireNonNull(checkpointCreatedAt, "checkpointCreatedAt");
    }
}
