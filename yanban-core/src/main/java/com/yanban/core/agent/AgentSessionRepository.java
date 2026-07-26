package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    List<AgentSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<AgentSession> findByUserIdAndScopeOrderByUpdatedAtDesc(Long userId, AgentSessionScope scope);
    List<AgentSession> findByUserIdAndScopeAndProjectIdOrderByUpdatedAtDesc(Long userId, AgentSessionScope scope, Long projectId);

    Optional<AgentSession> findByIdAndUserId(Long id, Long userId);
    Optional<AgentSession> findByIdAndUserIdAndScopeAndProjectId(Long id, Long userId, AgentSessionScope scope, Long projectId);

    long countByUserId(Long userId);
}
