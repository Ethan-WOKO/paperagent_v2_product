package io.paperagent.v2.contracts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ContractFixtures {
    static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");
    static final TaskFrameId TASK_ID = new TaskFrameId("task-1");
    static final PlanId PLAN_ID = new PlanId("plan-1");
    static final ProjectVersionRef PROJECT_VERSION = new ProjectVersionRef("project-1", "version-1");
    static final PlanStepId STEP_1 = new PlanStepId("step-1");
    static final PlanStepId STEP_2 = new PlanStepId("step-2");

    private ContractFixtures() {
    }

    static ExecutionProfile profile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(Duration.ofMinutes(10), Duration.ofMinutes(5),
                        512 * 1024 * 1024L, 1024 * 1024L, 8),
                Set.of());
    }

    static TaskFrame taskFrame() {
        return new TaskFrame(
                TASK_ID,
                "Prepare a verified paper update",
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve citations"),
                Optional.of(PROJECT_VERSION),
                profile(),
                T0);
    }

    static PlanStep step(PlanStepId id, Set<PlanStepId> dependencies) {
        return new PlanStep(
                id,
                "Perform " + id.value(),
                "Verified " + id.value(),
                dependencies,
                List.of("result is verified"),
                new BoundedExecutionHints(3, Duration.ofMinutes(2)));
    }

    static PlanRevision revision1() {
        return new PlanRevision(
                new PlanRevisionId("revision-1"),
                TASK_ID,
                1,
                Optional.empty(),
                "initial plan",
                T0,
                List.of(step(STEP_1, Set.of()), step(STEP_2, Set.of(STEP_1))),
                Map.of());
    }

    static Plan plan(PlanRevision... revisions) {
        return new Plan(PLAN_ID, TASK_ID, List.of(revisions));
    }

    static Checkpoint checkpoint(PlanRevision revision, long sequence) {
        return new Checkpoint(
                TASK_ID,
                PLAN_ID,
                revision.id(),
                revision.number(),
                sequence,
                PlanExecutionState.ACTIVE,
                Map.of(STEP_1, StepExecutionState.ACTIVE, STEP_2, StepExecutionState.NOT_STARTED),
                List.of(),
                T0.plusSeconds(sequence));
    }

    static String hash(char character) {
        return String.valueOf(character).repeat(64);
    }

    static ContractViolationException violation(Runnable action) {
        try {
            action.run();
        } catch (ContractViolationException exception) {
            return exception;
        }
        throw new AssertionError("expected ContractViolationException");
    }
}
