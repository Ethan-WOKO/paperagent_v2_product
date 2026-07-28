package com.yanban.agent.v2.adapter.provider;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.kernel.StepTurnInput;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ProductProviderAdapterTestFixtures {
    private static final Instant T0 = Instant.parse("2026-07-28T00:00:00Z");

    private ProductProviderAdapterTestFixtures() {
    }

    static StepTurnInput input(String suffix) {
        TaskFrameId taskId = new TaskFrameId("task-" + suffix);
        PlanId planId = new PlanId("plan-" + suffix);
        PlanStepId stepId = new PlanStepId("step-" + suffix);
        TaskFrame task = new TaskFrame(
                taskId,
                "Find sources " + suffix,
                List.of("paper"),
                List.of("references"),
                List.of("do not mutate project"),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(2),
                                1024,
                                1024,
                                1),
                        Set.of()),
                T0);
        PlanStep step = new PlanStep(
                stepId,
                "search literature",
                "candidate papers",
                Set.of(),
                List.of("results are relevant"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-" + suffix),
                taskId,
                1,
                Optional.empty(),
                "initial",
                T0,
                List.of(step),
                Map.of());
        Plan plan = new Plan(planId, taskId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                taskId,
                planId,
                revision.id(),
                1,
                2,
                PlanExecutionState.ACTIVE,
                Map.of(stepId, StepExecutionState.ACTIVE),
                List.of(),
                T0.plusSeconds(2));
        return new StepTurnInput(
                task, plan, new VersionedCheckpoint(3, checkpoint), step);
    }
}
