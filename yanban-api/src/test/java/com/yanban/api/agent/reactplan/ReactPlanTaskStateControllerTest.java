package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class ReactPlanTaskStateControllerTest {
    private static final String TOKEN = "t".repeat(32);

    @Test
    void retriesADeadlockInAFreshTransactionalServiceInvocation() {
        ReactPlanTaskStateService state = mock(ReactPlanTaskStateService.class);
        ReactPlanTaskSchedulerService scheduler = mock(ReactPlanTaskSchedulerService.class);
        ReactPlanRuntimeProperties properties = new ReactPlanRuntimeProperties();
        properties.setEngineServiceToken(TOKEN);
        ReactPlanTaskStateController controller = new ReactPlanTaskStateController(
                state, properties, scheduler);
        JsonNode checkpoint = new ObjectMapper().createObjectNode();
        String taskId = "task." + "a".repeat(64);
        String requestDigest = "b".repeat(64);
        when(state.save(eq(taskId), eq(requestDigest), eq(1L), eq(checkpoint), isNull()))
                .thenThrow(new CannotAcquireLockException("deadlock victim"))
                .thenReturn(7L);

        ReactPlanTaskStateController.CheckpointSaved result = controller.save(
                "Bearer " + TOKEN,
                new ReactPlanTaskStateController.CheckpointSave(
                        "1.0", taskId, requestDigest, 1L, checkpoint, null));

        assertThat(result.checkpointRevision()).isEqualTo(7L);
        verify(state, times(2)).save(eq(taskId), eq(requestDigest), eq(1L), eq(checkpoint), isNull());
    }
}
