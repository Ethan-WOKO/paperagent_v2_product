package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class TaskFrameTestFixtures {
    static final Instant CREATED_AT = Instant.parse("2026-07-24T04:00:00Z");
    static final TaskFrameId TASK_FRAME_ID = new TaskFrameId("task-frame-freeze-1");
    static final ProjectVersionRef PROJECT_VERSION =
            new ProjectVersionRef("project-freeze-1", "version-freeze-1");

    private TaskFrameTestFixtures() {
    }

    static TaskFrameDraft draft() {
        return new TaskFrameDraft(
                "Prepare a verified manuscript update",
                List.of("manuscript", "references"),
                List.of("workspace diff", "verification summary"),
                List.of("preserve citation meaning"));
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

    static RoutingDecision declaredRequirementDecision(String requestId) {
        return new RoutingDecision(
                new RoutingRequestId(requestId),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.PROJECT_FILE_ACCESS));
    }

    static RoutingDecision incompleteAssessmentDecision(String requestId) {
        return new RoutingDecision(
                new RoutingRequestId(requestId),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.INCOMPLETE_ASSESSMENT,
                Set.of());
    }

    static RoutingDecision directDecision(String requestId) {
        return new RoutingDecision(
                new RoutingRequestId(requestId),
                Route.DIRECT,
                RoutingDecisionReason.DIRECT_ELIGIBLE,
                Set.of());
    }

    static TaskFrameFreezeRequest request(
            RoutingDecision routingDecision,
            TaskFrameId taskFrameId,
            TaskFrameDraft draft) {
        return new TaskFrameFreezeRequest(
                routingDecision,
                taskFrameId,
                draft,
                Optional.of(PROJECT_VERSION),
                executionProfile(),
                CREATED_AT);
    }

    static TaskFrameFreezeRequest request(TaskFrameDraft draft) {
        return request(
                declaredRequirementDecision("routing-freeze-default"),
                TASK_FRAME_ID,
                draft);
    }
}
