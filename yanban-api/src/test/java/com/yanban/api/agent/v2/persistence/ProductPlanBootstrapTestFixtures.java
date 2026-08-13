package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
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
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.SecretRef;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ProductPlanBootstrapTestFixtures {
    private static final Instant CREATED = Instant.parse("2026-07-27T08:00:00Z");

    private ProductPlanBootstrapTestFixtures() {
    }

    static PersistedPlanBootstrap workspace(String planId, String taskFrameId) {
        return tuple(planId, taskFrameId, Optional.empty(), false);
    }

    static PersistedPlanBootstrap project(String planId, String taskFrameId) {
        return tuple(
                planId,
                taskFrameId,
                Optional.of(new ProjectVersionRef("project-42", "version-7")),
                true);
    }

    static PersistedPlanBootstrap workspaceWithStepConstraints() {
        PersistedPlanBootstrap base = workspace("plan-constraints", "task-constraints");
        PlanRevision revision = base.plan().revisions().get(0);
        PlanStep original = revision.steps().get(1);
        PlanStep constrained = new PlanStep(
                original.id(), original.intent(), original.expectedOutcome(),
                original.dependencies(), original.completionCriteria(),
                original.executionHints(), List.of("preserve unrelated content"));
        PlanRevision revised = new PlanRevision(
                revision.id(), revision.taskFrameId(), revision.number(),
                revision.parentRevisionId(), revision.reason(), revision.createdAt(),
                List.of(revision.steps().get(0), constrained), revision.completedFacts());
        Plan plan = new Plan(base.plan().id(), base.plan().taskFrameId(), List.of(revised));
        return new PersistedPlanBootstrap(
                base.taskFrame(), plan, base.initialCheckpoint());
    }

    static PersistedPlanBootstrap workspaceWithCandidateMetadata() {
        PersistedPlanBootstrap base = workspace("plan-candidate", "task-candidate");
        PlanRevision revision = base.plan().latestRevision();
        PlanStep original = revision.steps().get(1);
        PlanStep candidate = new PlanStep(
                original.id(), original.intent(), original.expectedOutcome(),
                original.dependencies(), original.completionCriteria(),
                original.executionHints(), List.of("preserve unrelated behavior"),
                true, "finished");
        PlanRevision revised = new PlanRevision(
                revision.id(), revision.taskFrameId(), revision.number(),
                revision.parentRevisionId(), revision.reason(), revision.createdAt(),
                List.of(revision.steps().get(0), candidate), revision.completedFacts());
        Plan plan = new Plan(base.plan().id(), base.plan().taskFrameId(), List.of(revised));
        return new PersistedPlanBootstrap(
                base.taskFrame(), plan, base.initialCheckpoint());
    }

    static PersistedPlanBootstrap tuple(
            String planId,
            String taskFrameId,
            Optional<ProjectVersionRef> project,
            boolean reverseInsertion) {
        TaskFrameId taskId = new TaskFrameId(taskFrameId);
        PlanStepId firstId = new PlanStepId("step-a");
        PlanStepId secondId = new PlanStepId("step-b");
        Set<Capability> capabilities = new LinkedHashSet<>();
        Set<SecretRef> secrets = new LinkedHashSet<>();
        if (reverseInsertion) {
            capabilities.add(Capability.USE_SECRET_REFERENCE);
            capabilities.add(Capability.ACCESS_NETWORK);
            capabilities.add(Capability.READ_PROJECT);
            secrets.add(new SecretRef("synthetic/second"));
            secrets.add(new SecretRef("synthetic/first"));
        } else {
            capabilities.add(Capability.READ_PROJECT);
            capabilities.add(Capability.ACCESS_NETWORK);
            capabilities.add(Capability.USE_SECRET_REFERENCE);
            secrets.add(new SecretRef("synthetic/first"));
            secrets.add(new SecretRef("synthetic/second"));
        }
        ExecutionProfile profile = new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                capabilities,
                NetworkPolicy.ALLOWLIST_ONLY,
                List.of("api.example.test", "mirror.example.test"),
                new ResourceLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        536_870_912,
                        1_048_576,
                        4),
                secrets);
        TaskFrame taskFrame = new TaskFrame(
                taskId,
                "Produce a synthetic result",
                List.of("target-a"),
                List.of("artifact-a"),
                List.of("No external facts"),
                project,
                profile,
                CREATED);
        PlanStep first = new PlanStep(
                firstId,
                "Prepare",
                "Preparation exists",
                Set.of(),
                List.of("prepared"),
                new BoundedExecutionHints(2, Duration.ofMinutes(1)));
        PlanStep second = new PlanStep(
                secondId,
                "Finish",
                "Result exists",
                Set.of(firstId),
                List.of("finished"),
                new BoundedExecutionHints(3, Duration.ofMinutes(2)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-1"),
                taskId,
                1,
                Optional.empty(),
                "Initial plan",
                CREATED.plusSeconds(1),
                List.of(first, second),
                Map.of());
        Plan plan = new Plan(new PlanId(planId), taskId, List.of(revision));
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        if (reverseInsertion) {
            states.put(secondId, StepExecutionState.NOT_STARTED);
            states.put(firstId, StepExecutionState.NOT_STARTED);
        } else {
            states.put(firstId, StepExecutionState.NOT_STARTED);
            states.put(secondId, StepExecutionState.NOT_STARTED);
        }
        Checkpoint checkpoint = new Checkpoint(
                taskId,
                plan.id(),
                revision.id(),
                1,
                0,
                PlanExecutionState.NOT_STARTED,
                states,
                List.of(),
                CREATED.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame, plan, new VersionedCheckpoint(1, checkpoint));
    }
}
