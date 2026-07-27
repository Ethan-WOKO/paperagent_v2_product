package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentTurnRepository extends JpaRepository<AgentTurn, Long> {
    Optional<AgentTurn> findByIdAndUserId(Long id, Long userId);

    List<AgentTurn> findBySessionIdAndUserIdOrderByStartedAtDescIdDesc(Long sessionId, Long userId);

    void deleteBySessionId(Long sessionId);
}
