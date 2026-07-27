package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialCheckpointFreezerTest {
    @Test
    void freezesRevisionOneIntoTheExactNoExecutionState() {
        TaskFrame taskFrame =
                InitialCheckpointTestFixtures.taskFrame("task-checkpoint-standard");
        Plan plan = InitialCheckpointTestFixtures.standardPlan(taskFrame);
        InitialCheckpointFreezeRequest request =
                InitialCheckpointTestFixtures.request(taskFrame, plan);

        Checkpoint result =
                new DeterministicInitialCheckpointFreezer().freeze(request);

        assertEquals(taskFrame.id(), result.taskFrameId());
        assertEquals(plan.id(), result.planId());
        assertEquals(plan.latestRevision().id(), result.revisionId());
        assertEquals(plan.latestRevision().number(), result.revisionNumber());
        assertEquals(0, result.lastEventSequence());
        assertEquals(PlanExecutionState.NOT_STARTED, result.planState());
        assertEquals(
                Map.of(
                        InitialCheckpointTestFixtures.FIRST_STEP_ID,
                        StepExecutionState.NOT_STARTED,
                        InitialCheckpointTestFixtures.SECOND_STEP_ID,
                        StepExecutionState.NOT_STARTED),
                result.stepStates());
        assertEquals(List.of(), result.receiptReferences());
        assertEquals(request.createdAt(), result.createdAt());
        assertTrue(
                CheckpointValidators.validate(result, taskFrame, plan, null)
                        .isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.stepStates().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.receiptReferences().add(
                        new io.paperagent.v2.contracts.ReceiptId("receipt-late")));
    }

    @Test
    void multiRevisionPlanFreezesTheLatestRevision() {
        TaskFrame taskFrame =
                InitialCheckpointTestFixtures.taskFrame("task-checkpoint-latest");
        Plan plan = InitialCheckpointTestFixtures.multiRevisionPlan(taskFrame);

        Checkpoint result = new DeterministicInitialCheckpointFreezer().freeze(
                InitialCheckpointTestFixtures.request(taskFrame, plan));

        assertEquals(2, plan.revisions().size());
        assertEquals(
                InitialCheckpointTestFixtures.SECOND_REVISION_ID,
                result.revisionId());
        assertEquals(2, result.revisionNumber());
        assertEquals(2, result.stepStates().size());
        assertTrue(result.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.NOT_STARTED));
    }

    @Test
    void emptyLatestStepSetRemainsNotStartedWithNoStepStates() {
        TaskFrame taskFrame =
                InitialCheckpointTestFixtures.taskFrame("task-checkpoint-empty");
        Plan plan = InitialCheckpointTestFixtures.emptyStepPlan(taskFrame);

        Checkpoint result = new DeterministicInitialCheckpointFreezer().freeze(
                InitialCheckpointTestFixtures.request(taskFrame, plan));

        assertEquals(Map.of(), result.stepStates());
        assertEquals(PlanExecutionState.NOT_STARTED, result.planState());
        assertEquals(List.of(), result.receiptReferences());
    }

    @Test
    void bothPersistentReasonsAndInterleavedReplayRemainDeterministic() {
        InitialCheckpointFreezer firstFreezer =
                new DeterministicInitialCheckpointFreezer();
        InitialCheckpointFreezer secondFreezer =
                new DeterministicInitialCheckpointFreezer();
        TaskFrame taskFrame =
                InitialCheckpointTestFixtures.taskFrame("task-checkpoint-replay");
        TaskFrame taskFrameSnapshot =
                InitialCheckpointTestFixtures.taskFrame("task-checkpoint-replay");
        Plan plan = InitialCheckpointTestFixtures.multiRevisionPlan(taskFrame);
        Plan planSnapshot =
                InitialCheckpointTestFixtures.multiRevisionPlan(taskFrameSnapshot);
        InitialCheckpointFreezeRequest firstRequest =
                InitialCheckpointTestFixtures.request(
                        InitialCheckpointTestFixtures.declaredRequirementDecision(
                                "routing-checkpoint-replay"),
                        taskFrame,
                        plan,
                        InitialCheckpointTestFixtures.CREATED_AT);
        InitialCheckpointFreezeRequest equalFirstRequest =
                InitialCheckpointTestFixtures.request(
                        InitialCheckpointTestFixtures.declaredRequirementDecision(
                                "routing-checkpoint-replay"),
                        taskFrame,
                        plan,
                        InitialCheckpointTestFixtures.CREATED_AT);
        InitialCheckpointFreezeRequest secondRequest =
                InitialCheckpointTestFixtures.request(
                        InitialCheckpointTestFixtures.incompleteAssessmentDecision(
                                "routing-checkpoint-interleaved"),
                        taskFrame,
                        plan,
                        InitialCheckpointTestFixtures.CREATED_AT.plusSeconds(1));

        Checkpoint firstResult = firstFreezer.freeze(firstRequest);
        Checkpoint secondResult = secondFreezer.freeze(secondRequest);
        Checkpoint firstCrossResult = secondFreezer.freeze(equalFirstRequest);
        Checkpoint secondCrossResult = firstFreezer.freeze(secondRequest);
        Checkpoint firstReplay = firstFreezer.freeze(equalFirstRequest);
        Checkpoint secondReplay = secondFreezer.freeze(secondRequest);

        assertEquals(firstRequest, equalFirstRequest);
        assertEquals(firstResult, firstCrossResult);
        assertEquals(firstResult, firstReplay);
        assertEquals(secondResult, secondCrossResult);
        assertEquals(secondResult, secondReplay);
        assertNotEquals(firstResult, secondResult);
        assertEquals(taskFrameSnapshot, taskFrame);
        assertEquals(planSnapshot, plan);
    }
}
