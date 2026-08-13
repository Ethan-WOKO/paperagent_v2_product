package com.yanban.api.agent.v2.chain.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Serializes chain mutations for one owned product Session.
 *
 * <p>Deletion and the stage-6 initial-command creation transaction must call
 * this lock before reading or writing any chain rows for the Session.</p>
 */
@Repository
public class ProductChainSessionMutationLock {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainSessionMutationLock(
            NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockOwnedSession(long userId, long sessionId) {
        MapSqlParameterSource owned = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sessionId", sessionId);
        List<Long> sessions = jdbc.queryForList("""
                SELECT id
                  FROM agent_sessions
                 WHERE id = :sessionId
                   AND user_id = :userId
                 FOR UPDATE
                """, owned, Long.class);
        if (sessions.size() != 1) {
            throw new ProductChainPersistenceException(
                    "CHAIN_SESSION_NOT_FOUND");
        }
    }

    /**
     * Locks the Session's existing chain Tasks in the same order used by
     * task-bound Plan mutations. The owned Session lock must be held first so
     * the task set cannot grow while this snapshot is being frozen.
     */
    public List<String> lockOwnedTasks(long userId, long sessionId) {
        MapSqlParameterSource owned = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sessionId", sessionId);
        List<String> taskIds = jdbc.queryForList("""
                SELECT task_id
                  FROM agent_v2_chain_tasks
                 WHERE user_id = :userId
                   AND session_id = :sessionId
                 ORDER BY task_id
                """, owned, String.class);
        for (String taskId : taskIds) {
            MapSqlParameterSource task = new MapSqlParameterSource()
                    .addValue("taskId", taskId)
                    .addValue("userId", userId)
                    .addValue("sessionId", sessionId);
            List<String> locked = jdbc.queryForList("""
                    SELECT task_id
                      FROM agent_v2_chain_tasks
                     WHERE task_id = :taskId
                       AND user_id = :userId
                       AND session_id = :sessionId
                     FOR UPDATE
                    """, task, String.class);
            if (!locked.equals(List.of(taskId))) {
                throw new ProductChainPersistenceException(
                        "CHAIN_TASK_DELETION_SNAPSHOT_MISMATCH");
            }
        }
        return List.copyOf(taskIds);
    }
}
