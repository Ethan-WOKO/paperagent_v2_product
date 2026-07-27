package com.yanban.agent.v2.adapter.taskframe;

import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ProductTaskFrameTestFixtures {
    static final Instant CREATED_AT = Instant.parse("2026-07-27T08:00:00Z");

    private ProductTaskFrameTestFixtures() {
    }

    static AgentRunIdentity identity(
            Long userId,
            Long sessionId,
            Long projectId) {
        return new AgentRunIdentity(
                "TURN",
                "run-42",
                userId,
                sessionId,
                projectId);
    }

    static RoutingDecision persistentDecision() {
        return new RoutingDecision(
                new RoutingRequestId("route-product-run-42"),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.TOOL_USE));
    }

    static RoutingDecision directDecision() {
        return new RoutingDecision(
                new RoutingRequestId("route-product-run-42-direct"),
                Route.DIRECT,
                RoutingDecisionReason.DIRECT_ELIGIBLE,
                Set.of());
    }

    static TaskFrameDraft draft() {
        return new TaskFrameDraft(
                "Prepare a verified literature summary",
                List.of("project manuscript"),
                List.of("workspace diff"),
                List.of("preserve citations"));
    }

    static ExecutionProfile executionProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(5),
                        512 * 1024 * 1024L,
                        1024 * 1024L,
                        8),
                Set.of());
    }

    static ProductTaskFrameIntakeRequest request(
            AgentRunIdentity identity,
            Optional<String> projectVersionId) {
        return new ProductTaskFrameIntakeRequest(
                identity,
                projectVersionId,
                persistentDecision(),
                draft(),
                executionProfile(),
                CREATED_AT);
    }
}
