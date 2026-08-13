package com.yanban.api.agent.v2.chain.persistence;

import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaim;
import com.yanban.api.agent.v2.chain.progression.ProductChainProgressionClaimStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** JDBC authority for short-lived task progression claims. */
@Repository
public class ProductChainProgressionClaimRepositoryAdapter
        implements ProductChainProgressionClaimStore {
    private static final Logger log = LoggerFactory.getLogger(
            ProductChainProgressionClaimRepositoryAdapter.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainTimeSource time;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    ProductChainProgressionClaimRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ProductChainTimeSource time) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.time = Objects.requireNonNull(time, "time");
        this.write = new TransactionTemplate(Objects.requireNonNull(
                transactions, "transactions"));
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    @Override
    public AcquireResult acquire(
            String taskId, String ownerId, String claimToken,
            Instant expiresAt) {
        requireText(taskId, "taskId", 128);
        requireText(ownerId, "ownerId", 255);
        requireText(claimToken, "claimToken", 128);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Instant canonicalExpiry = canonical(expiresAt);
        try {
            return write.execute(status -> acquireLocked(
                    taskId, ownerId, claimToken, canonicalExpiry));
        } catch (DataIntegrityViolationException failure) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROGRESSION_CLAIM_TOKEN_CONFLICT", failure);
        }
    }

    @Override
    public ReleaseResult release(
            String taskId, String ownerId, String claimToken, long fence) {
        requireClaimIdentity(taskId, ownerId, claimToken, fence);
        return write.execute(status -> releaseLocked(
                taskId, ownerId, claimToken, fence));
    }

    @Override
    public RenewResult renew(
            String taskId, String ownerId, String claimToken, long fence,
            Instant expiresAt) {
        requireClaimIdentity(taskId, ownerId, claimToken, fence);
        Instant canonicalExpiry = canonical(expiresAt);
        return write.execute(status -> renewLocked(
                taskId, ownerId, claimToken, fence, canonicalExpiry));
    }

    @Override
    public CurrentResult assertCurrent(
            String taskId, String ownerId, String claimToken, long fence) {
        requireClaimIdentity(taskId, ownerId, claimToken, fence);
        return write.execute(status -> assertCurrentLocked(
                taskId, ownerId, claimToken, fence));
    }

    @Override
    public CommittedTaskPage scanCommittedRootTasks(
            CommittedTaskCursor afterExclusive, int limit) {
        if (limit <= 0 || limit > 1000) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 1000");
        }
        Instant observedAt = canonical(time.now());
        List<CommittedTaskRow> rows = read.execute(status ->
                scanCommittedRows(afterExclusive, limit, observedAt));
        log.debug("Scanned committed root tasks observedAt={} after={} "
                        + "limit={} count={}", observedAt, afterExclusive,
                limit, rows.size());
        if (rows.isEmpty()) {
            return new CommittedTaskPage(List.of(), null);
        }
        CommittedTaskRow last = rows.get(rows.size() - 1);
        return new CommittedTaskPage(
                rows.stream().map(CommittedTaskRow::taskId).toList(),
                new CommittedTaskCursor(
                        last.committedAt(), last.taskId()));
    }

    @Override
    public FailureDisposition recordFailure(
            String taskId, long authorityEventCut, String failureSha256,
            String reason, boolean deterministic) {
        requireText(taskId, "taskId", 128);
        requireText(failureSha256, "failureSha256", 64);
        if (!failureSha256.matches("[0-9a-f]{64}")
                || authorityEventCut < 0) {
            throw new IllegalArgumentException("failure identity is invalid");
        }
        String boundedReason = Objects.requireNonNullElse(reason, "failure");
        if (boundedReason.length() > 512) {
            boundedReason = boundedReason.substring(0, 512);
        }
        final String storedReason = boundedReason;
        return write.execute(status -> {
            if (!lockTask(taskId)) return FailureDisposition.BLOCKED;
            GuardRow current = guard(taskId);
            int count = current != null
                    && "RUNNABLE".equals(current.state())
                    && failureSha256.equals(current.failureSha256())
                    && authorityEventCut == current.authorityEventCut()
                    ? current.count() + 1 : 1;
            boolean blocked = deterministic || count >= 3;
            saveGuard(taskId, blocked ? "BLOCKED" : "RUNNABLE",
                    failureSha256, authorityEventCut, count, storedReason);
            return blocked ? FailureDisposition.BLOCKED
                    : FailureDisposition.RETRY;
        });
    }

    @Override
    public ProgressDisposition recordProgress(
            String taskId, long previousAuthorityEventCut) {
        requireText(taskId, "taskId", 128);
        return write.execute(status -> {
            if (!lockTask(taskId)) return ProgressDisposition.BLOCKED;
            GuardRow current = guard(taskId);
            if (current != null && !"RUNNABLE".equals(current.state())) {
                return ProgressDisposition.BLOCKED;
            }
            Long repeated = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM (
                        SELECT action.action_signature_sha256,
                               block.failure_code
                          FROM agent_v2_chain_action_receipt_step_blocks block
                          JOIN agent_v2_chain_action_bindings action
                            ON action.action_id = block.action_id
                           AND action.task_id = block.task_id
                         WHERE block.task_id = :taskId
                         GROUP BY action.action_signature_sha256,
                                  block.failure_code
                        HAVING COUNT(DISTINCT block.plan_revision_id) >= 2
                      ) repeated_failure
                    """, Map.of("taskId", taskId), Long.class);
            if (repeated != null && repeated > 0) {
                saveGuard(taskId, "BLOCKED", null, null, 0,
                        "REPLAN_NO_PROGRESS");
                return ProgressDisposition.BLOCKED;
            }
            if (current != null && current.count() > 0) {
                saveGuard(taskId, "RUNNABLE", null, null, 0, null);
            }
            return ProgressDisposition.RUNNABLE;
        });
    }

    private List<CommittedTaskRow> scanCommittedRows(
            CommittedTaskCursor afterExclusive, int limit,
            Instant observedAt) {
        if (afterExclusive == null) {
            return jdbc.query("""
                SELECT task.task_id
                      ,root_command.committed_at
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_commands root_command
                    ON root_command.command_id = task.created_by_command_id
                   AND root_command.user_id = task.user_id
                   AND root_command.session_id = task.session_id
                   AND root_command.client_request_id =
                       task.root_client_request_id
                   AND root_command.request_sha256 = task.root_request_sha256
                   AND root_command.result_task_id = task.task_id
                 WHERE root_command.status = 'COMMITTED'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_progression_guards guard
                        WHERE guard.task_id = task.task_id
                          AND guard.state IN ('BLOCKED','CANCELLED'))
                   AND (
                       NOT EXISTS (
                           SELECT 1
                             FROM agent_v2_chain_progression_claims claim_history
                            WHERE claim_history.task_id = task.task_id
                       )
                       OR EXISTS (
                           SELECT 1
                             FROM agent_v2_chain_progression_claims latest_claim
                            WHERE latest_claim.task_id = task.task_id
                              AND latest_claim.fence = (
                                  SELECT MAX(claim_candidate.fence)
                                    FROM agent_v2_chain_progression_claims claim_candidate
                                   WHERE claim_candidate.task_id = latest_claim.task_id
                              )
                              AND (
                                  (latest_claim.released_at IS NOT NULL
                                   AND latest_claim.authority_event_cut < (
                                       SELECT COALESCE(MAX(authority_event.event_sequence), 0)
                                         FROM agent_v2_chain_authority_events authority_event
                                        WHERE authority_event.task_id = latest_claim.task_id
                                   ))
                                  OR (latest_claim.released_at IS NULL
                                      AND latest_claim.expires_at <= :observedAt)
                              )
                       )
                   )
                 ORDER BY root_command.committed_at, task.task_id
                 LIMIT :limit
                """, Map.of(
                        "limit", limit,
                        "observedAt", Timestamp.from(observedAt)),
                    ProductChainProgressionClaimRepositoryAdapter
                            ::committedTaskRow);
        }
        return jdbc.query("""
                SELECT task.task_id
                      ,root_command.committed_at
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_commands root_command
                    ON root_command.command_id = task.created_by_command_id
                   AND root_command.user_id = task.user_id
                   AND root_command.session_id = task.session_id
                   AND root_command.client_request_id =
                       task.root_client_request_id
                   AND root_command.request_sha256 = task.root_request_sha256
                   AND root_command.result_task_id = task.task_id
                 WHERE root_command.status = 'COMMITTED'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_progression_guards guard
                        WHERE guard.task_id = task.task_id
                          AND guard.state IN ('BLOCKED','CANCELLED'))
                   AND (
                       NOT EXISTS (
                           SELECT 1
                             FROM agent_v2_chain_progression_claims claim_history
                            WHERE claim_history.task_id = task.task_id
                       )
                       OR EXISTS (
                           SELECT 1
                             FROM agent_v2_chain_progression_claims latest_claim
                            WHERE latest_claim.task_id = task.task_id
                              AND latest_claim.fence = (
                                  SELECT MAX(claim_candidate.fence)
                                    FROM agent_v2_chain_progression_claims claim_candidate
                                   WHERE claim_candidate.task_id = latest_claim.task_id
                              )
                              AND (
                                  (latest_claim.released_at IS NOT NULL
                                   AND latest_claim.authority_event_cut < (
                                       SELECT COALESCE(MAX(authority_event.event_sequence), 0)
                                         FROM agent_v2_chain_authority_events authority_event
                                        WHERE authority_event.task_id = latest_claim.task_id
                                   ))
                                  OR (latest_claim.released_at IS NULL
                                      AND latest_claim.expires_at <= :observedAt)
                              )
                       )
                   )
                   AND (root_command.committed_at > :afterCommittedAt
                     OR (root_command.committed_at = :afterCommittedAt
                     AND task.task_id > :afterTaskId))
                 ORDER BY root_command.committed_at, task.task_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                        .addValue("afterCommittedAt", Timestamp.from(
                                canonical(afterExclusive.committedAt())))
                        .addValue("afterTaskId", afterExclusive.taskId())
                        .addValue("observedAt",
                                Timestamp.from(observedAt))
                        .addValue("limit", limit),
                ProductChainProgressionClaimRepositoryAdapter
                        ::committedTaskRow);
    }

    private AcquireResult acquireLocked(
            String taskId, String ownerId, String claimToken,
            Instant expiresAt) {
        if (!lockTask(taskId)) {
            return AcquireResult.taskNotFound();
        }
        GuardRow guard = guard(taskId);
        if (guard != null && !"RUNNABLE".equals(guard.state())) {
            return new AcquireResult(AcquireStatus.STOPPED, null);
        }
        Instant now = canonical(time.now());
        ProductChainProgressionClaim current = current(taskId);
        if (current != null && now.isBefore(current.expiresAt())
                && !released(taskId, current.fence())) {
            return AcquireResult.active();
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after the authoritative time");
        }
        long nextFence = current == null ? 1 : increment(current.fence());
        long authorityCut = jdbc.queryForObject("""
                SELECT COALESCE(MAX(event_sequence), 0)
                  FROM agent_v2_chain_authority_events
                 WHERE task_id = :taskId
                """, Map.of("taskId", taskId), Long.class);
        jdbc.update("""
                INSERT INTO agent_v2_chain_progression_claims(
                  task_id,fence,owner_id,claim_token,authority_event_cut,
                  acquired_at,expires_at,released_at)
                VALUES (
                  :taskId,:fence,:ownerId,:claimToken,:authorityEventCut,
                  :acquiredAt,:expiresAt,NULL)
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("fence", nextFence)
                .addValue("ownerId", ownerId)
                .addValue("claimToken", claimToken)
                .addValue("authorityEventCut", authorityCut)
                .addValue("acquiredAt", Timestamp.from(now))
                .addValue("expiresAt", Timestamp.from(expiresAt)));
        return AcquireResult.acquired(requirePersistedClaim(
                taskId, ownerId, claimToken, nextFence));
    }

    private ReleaseResult releaseLocked(
            String taskId, String ownerId, String claimToken, long fence) {
        if (!lockTask(taskId)) {
            return ReleaseResult.TASK_NOT_FOUND;
        }
        ProductChainProgressionClaim current = current(taskId);
        if (current == null || current.fence() != fence
                || !current.ownerId().equals(ownerId)
                || !current.claimToken().equals(claimToken)
                || released(taskId, current.fence())) {
            return ReleaseResult.STALE_CLAIM;
        }
        int changed = jdbc.update("""
                UPDATE agent_v2_chain_progression_claims
                   SET released_at = :releasedAt
                 WHERE task_id = :taskId
                   AND fence = :fence
                   AND owner_id = :ownerId
                   AND claim_token = :claimToken
                   AND released_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("releasedAt", Timestamp.from(canonical(time.now())))
                .addValue("taskId", taskId)
                .addValue("fence", fence)
                .addValue("ownerId", ownerId)
                .addValue("claimToken", claimToken));
        return changed == 1
                ? ReleaseResult.RELEASED : ReleaseResult.STALE_CLAIM;
    }

    private RenewResult renewLocked(
            String taskId, String ownerId, String claimToken, long fence,
            Instant expiresAt) {
        if (!lockTask(taskId)) {
            return RenewResult.taskNotFound();
        }
        Instant now = canonical(time.now());
        ProductChainProgressionClaim current = current(taskId);
        CurrentResult validation = currentStatus(
                taskId, ownerId, claimToken, fence, current, now);
        if (validation == CurrentResult.EXPIRED_CLAIM) {
            return RenewResult.expired();
        }
        if (validation != CurrentResult.CURRENT) {
            return RenewResult.stale();
        }
        if (expiresAt.isBefore(current.expiresAt())) {
            throw new IllegalArgumentException(
                    "expiresAt must not shorten the current claim");
        }
        if (expiresAt.equals(current.expiresAt())) {
            return RenewResult.replayed(current);
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after the authoritative time");
        }
        int changed = jdbc.update("""
                UPDATE agent_v2_chain_progression_claims
                   SET expires_at = :expiresAt
                 WHERE task_id = :taskId
                   AND fence = :fence
                   AND owner_id = :ownerId
                   AND claim_token = :claimToken
                   AND expires_at = :currentExpiresAt
                   AND released_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("taskId", taskId)
                .addValue("fence", fence)
                .addValue("ownerId", ownerId)
                .addValue("claimToken", claimToken)
                .addValue("currentExpiresAt",
                        Timestamp.from(current.expiresAt())));
        if (changed != 1) {
            return RenewResult.stale();
        }
        return RenewResult.renewed(requirePersistedClaim(
                taskId, ownerId, claimToken, fence));
    }

    /**
     * Returns the database-authoritative temporal values after a write. JDBC
     * drivers and database column precision may normalize an Instant at the
     * storage boundary, so callers must not continue from the pre-write value.
     */
    private ProductChainProgressionClaim requirePersistedClaim(
            String taskId, String ownerId, String claimToken, long fence) {
        ProductChainProgressionClaim persisted = current(taskId);
        if (persisted == null || persisted.fence() != fence
                || !persisted.ownerId().equals(ownerId)
                || !persisted.claimToken().equals(claimToken)) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROGRESSION_CLAIM_WRITE_NOT_OBSERVED");
        }
        return persisted;
    }

    private CurrentResult assertCurrentLocked(
            String taskId, String ownerId, String claimToken, long fence) {
        if (!lockTask(taskId)) {
            return CurrentResult.TASK_NOT_FOUND;
        }
        return currentStatus(taskId, ownerId, claimToken, fence,
                current(taskId), canonical(time.now()));
    }

    private CurrentResult currentStatus(
            String taskId, String ownerId, String claimToken, long fence,
            ProductChainProgressionClaim current, Instant now) {
        if (current == null || current.fence() != fence
                || !current.ownerId().equals(ownerId)
                || !current.claimToken().equals(claimToken)
                || released(taskId, current.fence())) {
            return CurrentResult.STALE_CLAIM;
        }
        return now.isBefore(current.expiresAt())
                ? CurrentResult.CURRENT : CurrentResult.EXPIRED_CLAIM;
    }

    private boolean lockTask(String taskId) {
        return !jdbc.queryForList("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, Map.of("taskId", taskId), String.class).isEmpty();
    }

    private GuardRow guard(String taskId) {
        List<GuardRow> rows = jdbc.query("""
                SELECT state,last_failure_sha256,last_failure_authority_cut,
                       consecutive_failure_count
                  FROM agent_v2_chain_progression_guards
                 WHERE task_id = :taskId
                """, Map.of("taskId", taskId), (row, ignored) ->
                new GuardRow(row.getString("state"),
                        row.getString("last_failure_sha256"),
                        nullableLong(row, "last_failure_authority_cut"),
                        row.getInt("consecutive_failure_count")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void saveGuard(
            String taskId, String state, String failureSha256,
            Long authorityEventCut, int count, String reason) {
        int updated = jdbc.update("""
                UPDATE agent_v2_chain_progression_guards
                   SET state = :state,
                       last_failure_sha256 = :failureSha256,
                       last_failure_authority_cut = :authorityEventCut,
                       consecutive_failure_count = :count,
                       reason_code = :reason,
                       updated_at = :updatedAt
                 WHERE task_id = :taskId
                """, new MapSqlParameterSource()
                .addValue("taskId", taskId)
                .addValue("state", state)
                .addValue("failureSha256", failureSha256)
                .addValue("authorityEventCut", authorityEventCut)
                .addValue("count", count)
                .addValue("reason", reason)
                .addValue("updatedAt", Timestamp.from(canonical(time.now()))));
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO agent_v2_chain_progression_guards(
                      task_id,state,last_failure_sha256,
                      last_failure_authority_cut,consecutive_failure_count,
                      reason_code,updated_at)
                    VALUES (
                      :taskId,:state,:failureSha256,:authorityEventCut,
                      :count,:reason,:updatedAt)
                    """, new MapSqlParameterSource()
                    .addValue("taskId", taskId)
                    .addValue("state", state)
                    .addValue("failureSha256", failureSha256)
                    .addValue("authorityEventCut", authorityEventCut)
                    .addValue("count", count)
                    .addValue("reason", reason)
                    .addValue("updatedAt", Timestamp.from(
                            canonical(time.now()))));
        }
    }

    private ProductChainProgressionClaim current(String taskId) {
        List<ProductChainProgressionClaim> rows = jdbc.query("""
                SELECT task_id,owner_id,claim_token,fence,
                       authority_event_cut,acquired_at,expires_at
                  FROM agent_v2_chain_progression_claims
                 WHERE task_id = :taskId
                 ORDER BY fence DESC
                 LIMIT 1
                """, Map.of("taskId", taskId),
                ProductChainProgressionClaimRepositoryAdapter::claimRow);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private static ProductChainProgressionClaim claimRow(
            ResultSet row, int rowNumber) throws SQLException {
        return new ProductChainProgressionClaim(
                row.getString("task_id"), row.getString("owner_id"),
                row.getString("claim_token"), row.getLong("fence"),
                row.getLong("authority_event_cut"),
                instant(row, "acquired_at"), instant(row, "expires_at"));
    }

    private boolean released(String taskId, long fence) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_progression_claims
                 WHERE task_id = :taskId
                   AND fence = :fence
                   AND released_at IS NOT NULL
                """, Map.of("taskId", taskId, "fence", fence), Long.class);
        return count != null && count == 1;
    }

    private static CommittedTaskRow committedTaskRow(
            ResultSet row, int rowNumber) throws SQLException {
        return new CommittedTaskRow(row.getString("task_id"),
                instant(row, "committed_at"));
    }

    private static Instant instant(ResultSet row, String column)
            throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROGRESSION_CLAIM_TIME_INVALID");
        }
        return value.toInstant();
    }

    private static Long nullableLong(ResultSet row, String column)
            throws SQLException {
        Number value = (Number) row.getObject(column);
        return value == null ? null : value.longValue();
    }

    private record CommittedTaskRow(String taskId, Instant committedAt) {}
    private record GuardRow(
            String state, String failureSha256, Long authorityEventCut,
            int count) {}

    private static long increment(long fence) {
        if (fence == Long.MAX_VALUE) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROGRESSION_CLAIM_FENCE_EXHAUSTED");
        }
        return fence + 1;
    }

    private static Instant canonical(Instant value) {
        return Objects.requireNonNull(value, "time")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireText(
            String value, String path, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    path + " must be nonblank and at most "
                            + maxLength + " characters");
        }
    }

    private static void requireClaimIdentity(
            String taskId, String ownerId, String claimToken, long fence) {
        requireText(taskId, "taskId", 128);
        requireText(ownerId, "ownerId", 255);
        requireText(claimToken, "claimToken", 128);
        if (fence <= 0) {
            throw new IllegalArgumentException("fence must be positive");
        }
    }
}
