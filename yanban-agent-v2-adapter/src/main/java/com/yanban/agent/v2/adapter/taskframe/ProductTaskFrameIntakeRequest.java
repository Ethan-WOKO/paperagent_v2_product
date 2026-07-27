package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;

import java.time.Instant;
import java.util.Optional;

/**
 * Product facts and caller-owned V2 authority required to freeze one TaskFrame.
 *
 * <p>The product is responsible for authenticating, authorizing, and resolving
 * these facts before constructing this request. This adapter validates only
 * their internal consistency.
 */
public record ProductTaskFrameIntakeRequest(
        AgentRunIdentity identity,
        Optional<String> projectVersionId,
        RoutingDecision routingDecision,
        TaskFrameDraft draft,
        ExecutionProfile executionProfile,
        Instant createdAt) {
}
