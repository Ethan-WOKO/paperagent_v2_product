package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.runtime.routing.RoutingDecision;

import java.time.Instant;

public record InitialCheckpointFreezeRequest(
        RoutingDecision routingDecision,
        TaskFrame taskFrame,
        Plan plan,
        Instant createdAt) {

    public InitialCheckpointFreezeRequest {
        InitialCheckpointFreezeValues.required(
                routingDecision,
                "initialCheckpointFreezeRequest.routingDecision");
        InitialCheckpointFreezeValues.required(
                taskFrame,
                "initialCheckpointFreezeRequest.taskFrame");
        InitialCheckpointFreezeValues.required(
                plan,
                "initialCheckpointFreezeRequest.plan");
        InitialCheckpointFreezeValues.required(
                createdAt,
                "initialCheckpointFreezeRequest.createdAt");
    }
}
