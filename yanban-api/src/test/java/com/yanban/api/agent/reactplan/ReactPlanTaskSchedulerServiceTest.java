package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ReactPlanTaskSchedulerServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReactPlanTaskCheckpointRepository checkpoints = mock(ReactPlanTaskCheckpointRepository.class);
    private final AgentEngineTaskGrantService grants = mock(AgentEngineTaskGrantService.class);
    private final ReactPlanRuntimeProperties properties = new ReactPlanRuntimeProperties();
    private final FakeJdbc jdbc = new FakeJdbc();
    private final ReactPlanTaskSchedulerService scheduler = new ReactPlanTaskSchedulerService(
            jdbc, json, checkpoints, grants, properties);

    @Test
    void defaultsMatchTheFrozenProductionLimits() {
        assertThat(properties.getMaxConcurrentTasks()).isEqualTo(20);
        assertThat(properties.getMaxConcurrentTasksPerUser()).isEqualTo(3);
        assertThat(properties.getMaxQueuedTasksPerUser()).isEqualTo(10);
    }

    @Test
    void skipsASaturatedUserAndClaimsTheOldestEligibleUsersTask() {
        ReactPlanTaskCheckpointEntity saturated = task("a", 7L, 70L);
        ReactPlanTaskCheckpointEntity eligible = task("b", 8L, 80L);
        jdbc.globalActive = 19;
        jdbc.activeByUser.put(7L, 3);
        jdbc.activeByUser.put(8L, 0);
        when(checkpoints.findClaimable(any(), any())).thenReturn(List.of(saturated, eligible));
        when(checkpoints.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.issue(any(), any(), any(Long.class), any(Long.class), any(), any()))
                .thenReturn(new EngineTaskGrant("g".repeat(40), Instant.parse("2026-08-18T00:05:00Z")));

        ReactPlanTaskSchedulerService.ClaimedTask claimed = scheduler.claimNext("engine.worker_one");

        assertThat(claimed.checkpoint().path("view").path("taskId").asText())
                .isEqualTo(eligible.taskId());
        assertThat(claimed.lease().fence()).isEqualTo(1L);
        assertThat(eligible.leaseOwner()).isEqualTo("engine.worker_one");
        assertThat(saturated.leaseOwner()).isNull();
    }

    @Test
    void staleWorkerCannotRenewAfterTheFenceChanges() {
        ReactPlanTaskCheckpointEntity task = task("c", 9L, 90L);
        when(checkpoints.findClaimable(any(), any())).thenReturn(List.of(task));
        when(checkpoints.findLockedByTaskId(task.taskId())).thenReturn(java.util.Optional.of(task));
        when(checkpoints.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.issue(any(), any(), any(Long.class), any(Long.class), any(), any()))
                .thenReturn(new EngineTaskGrant("g".repeat(40), Instant.parse("2026-08-18T00:05:00Z")));
        ReactPlanTaskSchedulerService.ClaimedTask claimed = scheduler.claimNext("engine.worker_one");

        ReactPlanTaskSchedulerService.Lease stale = new ReactPlanTaskSchedulerService.Lease(
                claimed.lease().owner(), claimed.lease().token(), claimed.lease().fence() - 1);
        assertThatThrownBy(() -> scheduler.renew(task.taskId(), stale))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TASK_LEASE_LOST");
    }

    @Test
    void permitsThreeActivePlusTenQueuedButRejectsTheFourteenthTask() {
        jdbc.admittedByUser.put(7L, 12);
        scheduler.assertQueueCapacity(7L);
        jdbc.admittedByUser.put(7L, 13);
        assertThatThrownBy(() -> scheduler.assertQueueCapacity(7L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("AGENT_USER_QUEUE_FULL");
    }

    private ReactPlanTaskCheckpointEntity task(String suffix, long userId, long turnId) {
        String taskId = "task." + suffix.repeat(64);
        String digest = suffix.repeat(64);
        String checkpoint = "{\"authority\":{\"model\":{\"provider\":\"test\",\"model\":\"model\"}},"
                + "\"view\":{\"taskId\":\"" + taskId + "\",\"requestDigest\":\"" + digest
                + "\",\"state\":\"queued\",\"lastSequence\":0}}";
        return new ReactPlanTaskCheckpointEntity(taskId, digest, userId, userId, turnId,
                "queued", 0, checkpoint, LocalDateTime.parse("2026-08-18T00:00:00"));
    }

    private static final class FakeJdbc extends JdbcTemplate {
        int globalActive;
        final Map<Long, Integer> activeByUser = new HashMap<>();
        final Map<Long, Integer> admittedByUser = new HashMap<>();

        @Override
        public <T> T queryForObject(String sql, Class<T> type) {
            Object value;
            if (sql.startsWith("select lock_id")) value = 1;
            else if (type == Timestamp.class) value = Timestamp.from(Instant.parse("2026-08-18T00:00:00Z"));
            else value = globalActive;
            return type.cast(value);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            int value;
            if (sql.contains("lease_expires_at") && args.length == 1) {
                value = globalActive;
            } else {
                long userId = ((Number) args[args.length - 1]).longValue();
                value = sql.contains("state in ('queued','running')")
                        && !sql.contains("lease_expires_at")
                        ? admittedByUser.getOrDefault(userId, 0)
                        : activeByUser.getOrDefault(userId, 0);
            }
            return type.cast(value);
        }
    }
}
