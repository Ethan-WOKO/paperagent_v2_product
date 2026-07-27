package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitialCheckpointCanonicalValidationTest {
    @Test
    void latestCompletionFactPropagatesCanonicalStateFailureUnwrapped() {
        TaskFrame taskFrame =
                InitialCheckpointTestFixtures.taskFrame("task-checkpoint-fact");
        Plan plan =
                InitialCheckpointTestFixtures.planWithLatestCompletedFact(
                        taskFrame);

        ContractViolationException exception = assertThrows(
                ContractViolationException.class,
                () -> new DeterministicInitialCheckpointFreezer().freeze(
                        InitialCheckpointTestFixtures.request(taskFrame, plan)));

        assertEquals(
                ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                exception.primaryCode());
        assertEquals(
                "checkpoint.stepStates."
                        + InitialCheckpointTestFixtures.FIRST_STEP_ID.value(),
                exception.violations().get(0).path());
        assertNull(exception.getCause());
    }

    @Test
    void taskFrameAndPlanMismatchPropagatesCanonicalFailureUnwrapped() {
        TaskFrame planTaskFrame =
                InitialCheckpointTestFixtures.taskFrame(
                        "task-checkpoint-plan-authority");
        TaskFrame requestTaskFrame =
                InitialCheckpointTestFixtures.taskFrame(
                        "task-checkpoint-request-authority");
        Plan plan =
                InitialCheckpointTestFixtures.standardPlan(planTaskFrame);

        ContractViolationException exception = assertThrows(
                ContractViolationException.class,
                () -> new DeterministicInitialCheckpointFreezer().freeze(
                        InitialCheckpointTestFixtures.request(
                                requestTaskFrame,
                                plan)));

        assertEquals(ViolationCode.TASK_FRAME_MISMATCH, exception.primaryCode());
        assertEquals(
                "checkpoint.taskFrameId",
                exception.violations().get(0).path());
        assertNull(exception.getCause());
    }
}
