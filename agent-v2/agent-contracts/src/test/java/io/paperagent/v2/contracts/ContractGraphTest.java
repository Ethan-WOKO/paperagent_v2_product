package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.PLAN_ID;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_2;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ContractGraphTest {
    @Test
    void constructsCompleteTaskPlanCheckpointGraph() {
        TaskFrame taskFrame = ContractFixtures.taskFrame();
        PlanRevision first = ContractFixtures.revision1();
        CompletionFact fact = new CompletionFact(
                STEP_1, "outcome-v1", T0.plusSeconds(10), List.of(new ReceiptId("receipt-1")));
        PlanRevision second = new PlanRevision(
                new PlanRevisionId("revision-2"),
                TASK_ID,
                2,
                Optional.of(first.id()),
                "continue unfinished work",
                T0.plusSeconds(20),
                first.steps(),
                Map.of(STEP_1, fact));
        Plan plan = ContractFixtures.plan(first, second);
        Checkpoint checkpoint = new Checkpoint(
                TASK_ID,
                PLAN_ID,
                second.id(),
                second.number(),
                7,
                PlanExecutionState.ACTIVE,
                Map.of(STEP_1, StepExecutionState.SUCCEEDED, STEP_2, StepExecutionState.ACTIVE),
                List.of(new ReceiptId("receipt-1")),
                T0.plusSeconds(30));

        assertDoesNotThrow(() -> CheckpointValidators.requireValid(checkpoint, taskFrame, plan, null));
        assertEquals(second, plan.latestRevision());
        assertEquals(ContractFixtures.PROJECT_VERSION, taskFrame.sourceProjectVersion().orElseThrow());
    }
}
