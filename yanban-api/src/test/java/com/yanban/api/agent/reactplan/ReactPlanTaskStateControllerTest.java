package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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

    @Test
    void retriesADeadlockWhileRenewingTheLease() {
        ReactPlanTaskStateService state = mock(ReactPlanTaskStateService.class);
        ReactPlanTaskSchedulerService scheduler = mock(ReactPlanTaskSchedulerService.class);
        ReactPlanRuntimeProperties properties = new ReactPlanRuntimeProperties();
        properties.setEngineServiceToken(TOKEN);
        ReactPlanTaskStateController controller = new ReactPlanTaskStateController(
                state, properties, scheduler);
        String taskId = "task." + "c".repeat(64);
        ReactPlanTaskSchedulerService.Lease lease = new ReactPlanTaskSchedulerService.Lease(
                "engine.worker_one", "lease-token", 2L);
        when(scheduler.renew(taskId, lease))
                .thenThrow(new CannotAcquireLockException("deadlock victim"))
                .thenReturn(new ReactPlanTaskSchedulerService.LeaseHeartbeat(false));

        ReactPlanTaskSchedulerService.LeaseHeartbeat result = controller.renew(
                "Bearer " + TOKEN, taskId,
                new ReactPlanTaskStateController.LeaseRequest("1.0", lease));

        assertThat(result.cancellationRequested()).isFalse();
        verify(scheduler, times(2)).renew(taskId, lease);
    }

    @Test
    void retriesADeadlockWhileAppendingAnEvent() {
        ReactPlanTaskStateService state = mock(ReactPlanTaskStateService.class);
        ReactPlanTaskSchedulerService scheduler = mock(ReactPlanTaskSchedulerService.class);
        ReactPlanRuntimeProperties properties = new ReactPlanRuntimeProperties();
        properties.setEngineServiceToken(TOKEN);
        ReactPlanTaskStateController controller = new ReactPlanTaskStateController(
                state, properties, scheduler);
        String taskId = "task." + "d".repeat(64);
        JsonNode event = new ObjectMapper().createObjectNode().put("taskId", taskId);
        doThrow(new CannotAcquireLockException("deadlock victim"))
                .doNothing().when(state).appendEvent(event, null);

        ReactPlanTaskStateController.Accepted result = controller.append(
                "Bearer " + TOKEN,
                new ReactPlanTaskStateController.EventAppend("1.0", event, null));

        assertThat(result.accepted()).isTrue();
        verify(state, times(2)).appendEvent(event, null);
    }
}
