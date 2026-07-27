package io.paperagent.v2.runtime.planning;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrame;
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

final class PlanningTestFixtures {
    static final Instant CREATED_AT = Instant.parse("2026-07-24T05:00:00Z");
    static final PlanId PLAN_ID = new PlanId("plan-initial-freeze-1");
    static final PlanRevisionId REVISION_ID =
            new PlanRevisionId("revision-initial-freeze-1");
    static final PlanStepId FIRST_STEP_ID = new PlanStepId("step-initial-1");
    static final PlanStepId SECOND_STEP_ID = new PlanStepId("step-initial-2");

    private PlanningTestFixtures() {
    }

    static InitialPlanDraft twoStepDraft() {
        return new InitialPlanDraft(
                "Initial execution plan",
                List.of(
                        step(FIRST_STEP_ID, Set.of()),
                        step(SECOND_STEP_ID, Set.of(FIRST_STEP_ID))));
    }

    static PlanStep step(PlanStepId id, Set<PlanStepId> dependencies) {
        return new PlanStep(
                id,
                "Perform " + id.value(),
                "Verify " + id.value(),
                dependencies,
                List.of("result for " + id.value() + " is verified"),
                new BoundedExecutionHints(3, Duration.ofMinutes(2)));
    }

    static TaskFrame taskFrame(String id) {
        return new TaskFrame(
                new TaskFrameId(id),
                "Prepare a verified paper update",
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve citations"),
                Optional.of(new ProjectVersionRef("project-plan-1", "version-plan-1")),
                executionProfile(),
                Instant.parse("2026-07-24T04:30:00Z"));
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

    static InitialPlanFreezeRequest request(
            RoutingDecision decision,
            TaskFrame taskFrame,
            PlanId planId,
            PlanRevisionId revisionId,
            InitialPlanDraft draft,
            Instant createdAt) {
        return new InitialPlanFreezeRequest(
                decision,
                taskFrame,
                planId,
                revisionId,
                draft,
                createdAt);
    }

    static InitialPlanFreezeRequest request(InitialPlanDraft draft) {
        return request(
                declaredRequirementDecision("routing-initial-plan-default"),
                taskFrame("task-initial-plan-default"),
                PLAN_ID,
                REVISION_ID,
                draft,
                CREATED_AT);
    }
}
