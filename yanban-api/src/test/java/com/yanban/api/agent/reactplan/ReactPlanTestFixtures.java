package com.yanban.api.agent.reactplan;

import io.paperagent.v2.contracts.BoundedExecutionHints;
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
import java.util.Set;

final class ReactPlanTestFixtures {
    private ReactPlanTestFixtures() {
    }

    static ReactPlanBootstrapCommand command() {
        RoutingDecision decision = new RoutingDecision(
                new RoutingRequestId("route-react-42"),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.TOOL_USE));
        return new ReactPlanBootstrapCommand(
                decision,
                new TaskFrameDraft(
                        "Compile Sort.java and explain the result",
                        List.of("Sort.java"),
                        List.of("compile result", "concise explanation"),
                        List.of("do not modify the Project")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT, Capability.EXECUTE_COMMAND),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(2),
                                256 * 1024 * 1024L,
                                1024 * 1024L,
                                2),
                        Set.of()),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)),
                Instant.parse("2026-08-16T01:00:00Z"),
                Instant.parse("2026-08-16T01:00:01Z"),
                Instant.parse("2026-08-16T01:00:02Z"));
    }
}
