package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
class ReactPlanTaskSchedulerService {
    private static final int CLAIM_SCAN_LIMIT = 512;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final AgentEngineTaskGrantService grants;
    private final ReactPlanRuntimeProperties properties;

    ReactPlanTaskSchedulerService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            ReactPlanTaskCheckpointRepository checkpoints,
            AgentEngineTaskGrantService grants,
            ReactPlanRuntimeProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.checkpoints = checkpoints;
        this.grants = grants;
        this.properties = properties;
    }

    @Transactional
    ClaimedTask claimNext(String owner) {
        validateOwner(owner);
        lockScheduler();
        LocalDateTime now = databaseNow();
        List<ReactPlanTaskCheckpointEntity> candidates = checkpoints.findClaimable(
                now, PageRequest.of(0, CLAIM_SCAN_LIMIT));
        if (candidates.isEmpty()) return null;

        ReactPlanTaskCheckpointEntity selected = candidates.stream()
                .filter(ReactPlanTaskCheckpointEntity::cancellationRequested)
                .findFirst().orElse(null);
        if (selected == null) {
            int active = countActive(null, now);
            if (active >= properties.getMaxConcurrentTasks()) return null;
            Map<Long, Integer> activeByUser = new HashMap<>();
            for (ReactPlanTaskCheckpointEntity candidate : candidates) {
                int userActive = activeByUser.computeIfAbsent(
                        candidate.userId(), user -> countActive(user, now));
                if (userActive < properties.getMaxConcurrentTasksPerUser()) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected == null) return null;

        return claim(selected, owner, now);
    }

    @Transactional
    ClaimedTask claimTask(String taskId, String owner) {
        validateOwner(owner);
        lockScheduler();
        ReactPlanTaskCheckpointEntity selected = locked(taskId);
        LocalDateTime now = databaseNow();
        if (!"waiting_user".equals(selected.state())
                || selected.leaseExpiresAt() != null && selected.leaseExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TASK_NOT_WAITING_FOR_CLAIM");
        }
        if (countActive(null, now) >= properties.getMaxConcurrentTasks()
                || countActive(selected.userId(), now) >= properties.getMaxConcurrentTasksPerUser()) {
            return null;
        }
        return claim(selected, owner, now);
    }

    @Transactional
    LeaseHeartbeat renew(String taskId, Lease lease) {
        ReactPlanTaskCheckpointEntity checkpoint = locked(taskId);
        LocalDateTime now = databaseNow();
        try {
            checkpoint.renew(lease.owner(), lease.token(), lease.fence(),
                    now.plusSeconds(properties.getTaskLeaseSeconds()), now);
        } catch (IllegalStateException stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TASK_LEASE_LOST");
        }
        checkpoints.saveAndFlush(checkpoint);
        return new LeaseHeartbeat(checkpoint.cancellationRequested());
    }

    @Transactional
    void release(String taskId, Lease lease) {
        ReactPlanTaskCheckpointEntity checkpoint = locked(taskId);
        LocalDateTime now = databaseNow();
        try { checkpoint.requireLease(lease.owner(), lease.token(), lease.fence(), now); }
        catch (IllegalStateException stale) { return; }
        checkpoint.releaseLease();
        checkpoints.saveAndFlush(checkpoint);
    }

    @Transactional
    void requestCancellation(String taskId) {
        ReactPlanTaskCheckpointEntity checkpoint = locked(taskId);
        checkpoint.requestCancellation(databaseNow());
        checkpoints.saveAndFlush(checkpoint);
    }

    void requireOwned(ReactPlanTaskCheckpointEntity checkpoint, Lease lease) {
        if (lease == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "TASK_LEASE_REQUIRED");
        try { checkpoint.requireLease(lease.owner(), lease.token(), lease.fence(), databaseNow()); }
        catch (IllegalStateException stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TASK_LEASE_LOST");
        }
    }

    void assertQueueCapacity(long userId) {
        lockScheduler();
        Integer queued = jdbc.queryForObject(
                "select count(*) from reactplan_task_checkpoints where user_id=? and state in ('queued','running')",
                Integer.class, userId);
        int admitted = properties.getMaxConcurrentTasksPerUser()
                + properties.getMaxQueuedTasksPerUser();
        if (queued != null && queued >= admitted) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AGENT_USER_QUEUE_FULL");
        }
    }

    void lockScheduler() {
        jdbc.queryForObject(
                "select lock_id from reactplan_agent_scheduler_lock where lock_id=1 for update",
                Integer.class);
    }

    private int countActive(Long userId, LocalDateTime now) {
        String base = "select count(*) from reactplan_task_checkpoints "
                + "where state in ('queued','running') and lease_expires_at>?";
        Integer result = userId == null
                ? jdbc.queryForObject(base, Integer.class, now)
                : jdbc.queryForObject(base + " and user_id=?", Integer.class, now, userId);
        return result == null ? 0 : result;
    }

    private ClaimedTask claim(ReactPlanTaskCheckpointEntity selected, String owner, LocalDateTime now) {
        String token = UUID.randomUUID().toString();
        selected.claim(owner, token, now.plusSeconds(properties.getTaskLeaseSeconds()), now);
        checkpoints.saveAndFlush(selected);
        JsonNode checkpoint = parse(selected.checkpointJson());
        JsonNode model = checkpoint.path("authority").path("model");
        EngineTaskGrant grant = grants.issue(
                selected.taskId(), selected.requestDigest(), selected.userId(), selected.turnId(),
                model.path("provider").asText(), model.path("model").asText());
        return new ClaimedTask(
                selected.checkpointRevision(), checkpoint,
                new Lease(owner, token, selected.leaseFence()),
                grant.value(), grant.expiresAt().toString(), selected.cancellationRequested());
    }

    private ReactPlanTaskCheckpointEntity locked(String taskId) {
        return checkpoints.findLockedByTaskId(taskId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND"));
    }

    private LocalDateTime databaseNow() {
        java.sql.Timestamp value = jdbc.queryForObject("select current_timestamp", java.sql.Timestamp.class);
        if (value == null) throw new IllegalStateException("database time unavailable");
        // Keep the database's wall-clock representation. Lease values and the
        // comparisons above then use one clock even when the JDBC session is
        // configured for a non-UTC server time zone.
        return value.toLocalDateTime();
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (Exception corrupt) { throw new IllegalStateException("Persisted ReAct state is corrupt", corrupt); }
    }

    private static void validateOwner(String owner) {
        if (owner == null || !owner.matches("engine\\.[A-Za-z0-9_-]{8,110}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TASK_LEASE_OWNER_INVALID");
        }
    }

    record Lease(String owner, String token, long fence) { }
    record LeaseHeartbeat(boolean cancellationRequested) { }
    record ClaimedTask(long checkpointRevision, JsonNode checkpoint, Lease lease,
                       String taskGrant, String expiresAt, boolean cancellationRequested) { }
}
