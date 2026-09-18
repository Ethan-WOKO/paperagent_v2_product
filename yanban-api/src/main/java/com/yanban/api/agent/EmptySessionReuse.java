package com.yanban.api.agent;

import com.yanban.core.agent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
public class EmptySessionReuse {
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entityManager;
    public EmptySessionReuse(JdbcTemplate jdbc, jakarta.persistence.EntityManager entityManager) {
        this.jdbc=jdbc;
        this.entityManager=entityManager;
    }

    // Lock is retained by the enclosing create transaction, including when no draft exists yet.
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<AgentSession> find(Long userId, AgentSessionScope scope, Long projectId) {
        jdbc.queryForObject("SELECT id FROM sys_users WHERE id=? FOR UPDATE", Long.class, userId);
        var ids = jdbc.queryForList("""
                SELECT s.id FROM agent_sessions s WHERE s.user_id=? AND s.scope=?
                AND ((s.project_id IS NULL AND ? IS NULL) OR s.project_id=?)
                AND NOT EXISTS (SELECT 1 FROM agent_messages m WHERE m.session_id=s.id)
                AND NOT EXISTS (SELECT 1 FROM agent_turns t WHERE t.session_id=s.id)
                ORDER BY s.updated_at DESC, s.id DESC FOR UPDATE
                """, Long.class, userId, scope.name(), projectId, projectId);
        // Use a current read here too: MySQL REPEATABLE READ may have an older
        // settings-query snapshot that predates a concurrent creator's commit.
        for (Long id : ids) {
            if (!jdbc.queryForList("SELECT id FROM agent_messages WHERE session_id=? LIMIT 1 FOR UPDATE",Long.class,id).isEmpty()) continue;
            if (!jdbc.queryForList("SELECT id FROM agent_turns WHERE session_id=? LIMIT 1 FOR UPDATE",Long.class,id).isEmpty()) continue;
            return Optional.ofNullable(entityManager.find(AgentSession.class,id,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        }
        return Optional.empty();
    }
}
