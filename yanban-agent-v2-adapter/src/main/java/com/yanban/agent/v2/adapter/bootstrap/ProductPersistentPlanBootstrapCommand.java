package com.yanban.agent.v2.adapter.bootstrap;

import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;

import java.time.Instant;

/**
 * Caller-owned immutable inputs for one persistent Plan bootstrap.
 *
 * <p>Identity and ProjectVersion are deliberately absent: the product
 * resolver supplies those authoritative facts.
 */
public record ProductPersistentPlanBootstrapCommand(
        RoutingDecision routingDecision,
        TaskFrameDraft taskFrameDraft,
        ExecutionProfile executionProfile,
        InitialPlanDraft initialPlanDraft,
        Instant taskFrameCreatedAt,
        Instant planCreatedAt,
        Instant checkpointCreatedAt) {
}
