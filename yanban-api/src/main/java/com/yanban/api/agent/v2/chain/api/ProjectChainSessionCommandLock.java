package com.yanban.api.agent.v2.chain.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serializes the Project session command registry before any command read or write. */
@Component
public final class ProjectChainSessionCommandLock {
    private static final Logger log = LoggerFactory.getLogger(ProjectChainSessionCommandLock.class);
    private final NamedParameterJdbcTemplate jdbc;

    public ProjectChainSessionCommandLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void lock(long userId, long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id
                  FROM agent_sessions
                 WHERE id = :sessionId
                   AND user_id = :userId
                   AND scope = 'PROJECT'
                   AND project_id IS NOT NULL
                   FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId));
        if (rows.size() != 1) {
            log.warn("chain session lock miss userId={} sessionId={} rows={}", userId, sessionId, rows.size());
            throw new ProjectChainApiException(
                    HttpStatus.NOT_FOUND, "CHAIN_TARGET_NOT_FOUND");
        }
    }
}
