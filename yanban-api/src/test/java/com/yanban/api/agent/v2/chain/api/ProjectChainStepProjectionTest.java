package com.yanban.api.agent.v2.chain.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paperagent.v2.chain.ChainStepStatus;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectChainStepProjectionTest {

    @Test
    void terminalRecoveryCheckpointProjectsCompletedStep() throws Exception {
        PlanStepId stepId = new PlanStepId("step-1");
        PlanStep step = mock(PlanStep.class);
        when(step.id()).thenReturn(stepId);
        Checkpoint checkpoint = new Checkpoint(
                new TaskFrameId("task-frame-1"),
                new PlanId("plan-1"),
                new PlanRevisionId("revision-1"),
                1, 3, PlanExecutionState.SUCCEEDED,
                Map.of(stepId, StepExecutionState.SUCCEEDED),
                List.<ReceiptId>of(), Instant.parse("2026-08-08T00:00:00Z"));
        PersistedStepRecoverySucceeded recovery =
                mock(PersistedStepRecoverySucceeded.class);
        when(recovery.checkpoint()).thenReturn(new VersionedCheckpoint(1, checkpoint));

        Method stepStatus = Arrays.stream(ProjectChainTurnCoordinator.class
                        .getDeclaredMethods())
                .filter(method -> method.getName().equals("stepStatus")
                        && method.getParameterCount() == 8)
                .findFirst().orElseThrow();
        stepStatus.setAccessible(true);
        Object actual = stepStatus.invoke(null, step, null, recovery, null,
                List.of(), List.of(), List.of(), List.of());

        assertEquals(ChainStepStatus.COMPLETED, actual);
    }
}
