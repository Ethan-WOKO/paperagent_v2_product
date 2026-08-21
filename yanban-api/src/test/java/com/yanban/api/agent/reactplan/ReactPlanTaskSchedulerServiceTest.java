package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import com.yanban.api.agent.reactplan.gateway.EngineModelRouteCandidate;
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
        when(grants.issue(any(), any(), any(Long.class), any(Long.class), any(), any(), anyList()))
                .thenReturn(new EngineTaskGrant("g".repeat(40), Instant.parse("2026-08-18T00:05:00Z")));

        ReactPlanTaskSchedulerService.ClaimedTask claimed = scheduler.claimNext("engine.worker_one");

        assertThat(claimed.checkpoint().path("view").path("taskId").asText())
                .isEqualTo(eligible.taskId());
        assertThat(claimed.lease().fence()).isEqualTo(1L);
        assertThat(eligible.leaseOwner()).isEqualTo("engine.worker_one");
        assertThat(saturated.leaseOwner()).isNull();
        verify(grants).issue(eq(eligible.taskId()), eq(eligible.requestDigest()),
                eq(8L), eq(80L), eq("test"), eq("model"),
                eq(List.of(new EngineModelRouteCandidate("backup", "backup-model"))));
    }

    @Test
    void staleWorkerCannotRenewAfterTheFenceChanges() {
        ReactPlanTaskCheckpointEntity task = task("c", 9L, 90L);
        when(checkpoints.findClaimable(any(), any())).thenReturn(List.of(task));
        when(checkpoints.existsById(task.taskId())).thenReturn(true);
        when(checkpoints.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.issue(any(), any(), any(Long.class), any(Long.class), any(), any(), anyList()))
                .thenReturn(new EngineTaskGrant("g".repeat(40), Instant.parse("2026-08-18T00:05:00Z")));
        ReactPlanTaskSchedulerService.ClaimedTask claimed = scheduler.claimNext("engine.worker_one");

        ReactPlanTaskSchedulerService.Lease stale = new ReactPlanTaskSchedulerService.Lease(
                claimed.lease().owner(), claimed.lease().token(), claimed.lease().fence() - 1);
        jdbc.renewUpdateCount = 0;
        assertThatThrownBy(() -> scheduler.renew(task.taskId(), stale))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TASK_LEASE_LOST");
    }

    @Test
    void renewsOnlyLeaseColumnsWithoutSavingTheCheckpointEntity() {
        String taskId = "task." + "d".repeat(64);
        ReactPlanTaskSchedulerService.Lease lease = new ReactPlanTaskSchedulerService.Lease(
                "engine.worker_one", "lease-token", 3L);
        jdbc.cancellationRequested = true;

        ReactPlanTaskSchedulerService.LeaseHeartbeat heartbeat = scheduler.renew(taskId, lease);

        assertThat(heartbeat.cancellationRequested()).isTrue();
        assertThat(jdbc.lastUpdateSql).contains("set lease_expires_at=?, updated_at=?")
                .doesNotContain("checkpoint_json");
        assertThat(jdbc.lastUpdateArgs).contains(taskId, lease.owner(), lease.token(), lease.fence());
        verify(checkpoints, org.mockito.Mockito.never()).saveAndFlush(any());
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
        String checkpoint = "{\"authority\":{\"model\":{\"provider\":\"test\",\"model\":\"model\","
                + "\"fallbacks\":[{\"provider\":\"backup\",\"model\":\"backup-model\"}]}},"
                + "\"view\":{\"taskId\":\"" + taskId + "\",\"requestDigest\":\"" + digest
                + "\",\"state\":\"queued\",\"lastSequence\":0}}";
        return new ReactPlanTaskCheckpointEntity(taskId, digest, userId, userId, turnId,
                "queued", 0, checkpoint, LocalDateTime.parse("2026-08-18T00:00:00"));
    }

    private static final class FakeJdbc extends JdbcTemplate {
        int globalActive;
        int renewUpdateCount = 1;
        boolean cancellationRequested;
        String lastUpdateSql;
        List<Object> lastUpdateArgs = List.of();
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
            if (type == Boolean.class) return type.cast(cancellationRequested);
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

        @Override
        public int update(String sql, Object... args) {
            lastUpdateSql = sql;
            lastUpdateArgs = List.of(args);
            return renewUpdateCount;
        }
    }
}
