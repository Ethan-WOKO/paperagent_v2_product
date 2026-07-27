package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class InitialCheckpointTestFixtures {
    static final Instant CREATED_AT = Instant.parse("2026-07-24T07:00:00Z");
    static final PlanId PLAN_ID = new PlanId("plan-initial-checkpoint");
    static final PlanRevisionId FIRST_REVISION_ID =
            new PlanRevisionId("revision-checkpoint-1");
    static final PlanRevisionId SECOND_REVISION_ID =
            new PlanRevisionId("revision-checkpoint-2");
    static final PlanStepId FIRST_STEP_ID =
            new PlanStepId("step-checkpoint-1");
    static final PlanStepId SECOND_STEP_ID =
            new PlanStepId("step-checkpoint-2");

    private InitialCheckpointTestFixtures() {
    }

    static TaskFrame taskFrame(String id) {
        return new TaskFrame(
                new TaskFrameId(id),
                "Prepare a verified paper update",
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve citations"),
                Optional.of(new ProjectVersionRef(
                        "project-checkpoint",
                        "version-checkpoint")),
                executionProfile(),
                Instant.parse("2026-07-24T06:30:00Z"));
    }

    static Plan standardPlan(TaskFrame taskFrame) {
        return new Plan(
                PLAN_ID,
                taskFrame.id(),
                List.of(revision(
                        FIRST_REVISION_ID,
                        taskFrame.id(),
                        1,
                        Optional.empty(),
                        steps(),
                        Map.of())));
    }

    static Plan multiRevisionPlan(TaskFrame taskFrame) {
        PlanRevision first = revision(
                FIRST_REVISION_ID,
                taskFrame.id(),
                1,
                Optional.empty(),
                steps(),
                Map.of());
        PlanRevision second = revision(
                SECOND_REVISION_ID,
                taskFrame.id(),
                2,
                Optional.of(first.id()),
                steps(),
                Map.of());
        return new Plan(PLAN_ID, taskFrame.id(), List.of(first, second));
    }

    static Plan emptyStepPlan(TaskFrame taskFrame) {
        return new Plan(
                PLAN_ID,
                taskFrame.id(),
                List.of(revision(
                        FIRST_REVISION_ID,
                        taskFrame.id(),
                        1,
                        Optional.empty(),
                        List.of(),
                        Map.of())));
    }

    static Plan planWithLatestCompletedFact(TaskFrame taskFrame) {
        PlanRevision first = revision(
                FIRST_REVISION_ID,
                taskFrame.id(),
                1,
                Optional.empty(),
                steps(),
                Map.of());
        CompletionFact completedFact = new CompletionFact(
                FIRST_STEP_ID,
                "completed before checkpoint initialization",
                Instant.parse("2026-07-24T06:45:00Z"),
                List.of(new ReceiptId("receipt-checkpoint-1")));
        PlanRevision second = revision(
                SECOND_REVISION_ID,
                taskFrame.id(),
                2,
                Optional.of(first.id()),
                steps(),
                Map.of(FIRST_STEP_ID, completedFact));
        return new Plan(PLAN_ID, taskFrame.id(), List.of(first, second));
    }

    static List<PlanStep> steps() {
        return List.of(
                step(FIRST_STEP_ID, Set.of()),
                step(SECOND_STEP_ID, Set.of(FIRST_STEP_ID)));
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

    static InitialCheckpointFreezeRequest request(
            RoutingDecision routingDecision,
            TaskFrame taskFrame,
            Plan plan,
            Instant createdAt) {
        return new InitialCheckpointFreezeRequest(
                routingDecision,
                taskFrame,
                plan,
                createdAt);
    }

    static InitialCheckpointFreezeRequest request(
            TaskFrame taskFrame,
            Plan plan) {
        return request(
                declaredRequirementDecision("routing-initial-checkpoint"),
                taskFrame,
                plan,
                CREATED_AT);
    }

    private static ExecutionProfile executionProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(
                        Capability.READ_PROJECT,
                        Capability.WRITE_WORKSPACE),
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

    private static PlanStep step(
            PlanStepId id,
            Set<PlanStepId> dependencies) {
        return new PlanStep(
                id,
                "Perform " + id.value(),
                "Verify " + id.value(),
                dependencies,
                List.of("result for " + id.value() + " is verified"),
                new BoundedExecutionHints(3, Duration.ofMinutes(2)));
    }

    private static PlanRevision revision(
            PlanRevisionId id,
            TaskFrameId taskFrameId,
            long number,
            Optional<PlanRevisionId> parentRevisionId,
            List<PlanStep> steps,
            Map<PlanStepId, CompletionFact> completedFacts) {
        return new PlanRevision(
                id,
                taskFrameId,
                number,
                parentRevisionId,
                "checkpoint revision " + number,
                Instant.parse("2026-07-24T06:35:00Z").plusSeconds(number),
                steps,
                completedFacts);
    }
}
