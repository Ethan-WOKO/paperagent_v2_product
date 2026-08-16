package com.yanban.api.agent.reactplan;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReactPlanModelProjectionTest {

    @Test
    void publicModelPayloadHasNoAuthorityIdentityFields() {
        Set<String> fields = Arrays.stream(ReactPlanModelProjection.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .collect(Collectors.toSet());

        assertEquals(Set.of("goals", "targets", "deliverables", "constraints", "capabilities"), fields);
        assertFalse(fields.stream().anyMatch(name ->
                name.contains("planid") || name.contains("stepid")
                        || name.contains("receiptid") || name.contains("eventid")
                        || name.contains("secret") || name.contains("token")));
    }

    @Test
    void projectorReplacesAuthorityStepIdentityWithALocalGoalKey() {
        TaskFrameId taskFrameId = new TaskFrameId("authority-task-frame-id");
        ExecutionProfile profile = new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT, Capability.EXECUTE_COMMAND),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(5), Duration.ofMinutes(2),
                        256 * 1024 * 1024L, 1024 * 1024L, 2),
                Set.of());
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "Compile the project",
                List.of("Sort.java"),
                List.of("compile result"),
                List.of("read only"),
                TaskRequirements.legacyUnspecified(),
                Optional.empty(),
                profile,
                Instant.parse("2026-08-16T01:00:00Z"));
        PlanStep step = new PlanStep(
                new PlanStepId("authority-step-id"),
                "Compile the project",
                "Receipt-backed result",
                Set.of(),
                List.of("terminal receipt"),
                new BoundedExecutionHints(2, Duration.ofMinutes(2)));
        Plan plan = new Plan(
                new PlanId("authority-plan-id"),
                taskFrameId,
                List.of(new PlanRevision(
                        new PlanRevisionId("authority-revision-id"),
                        taskFrameId,
                        1,
                        Optional.empty(),
                        "deterministic",
                        Instant.parse("2026-08-16T01:00:01Z"),
                        List.of(step),
                        Map.of())));

        ReactPlanModelProjection projection =
                new ReactPlanModelProjector().project(taskFrame, plan);

        assertEquals(DeterministicReactPlanDraftFactory.GOAL_KEY,
                projection.goals().get(0).key());
        assertFalse(projection.toString().contains("authority-step-id"));
        assertFalse(projection.toString().contains("authority-plan-id"));
        assertFalse(projection.toString().contains("authority-revision-id"));
        assertFalse(projection.toString().contains("authority-task-frame-id"));
    }
}
