package com.yanban.core.agent;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionSummaryRepository extends JpaRepository<AgentSessionSummary, Long> {
    Optional<AgentSessionSummary> findBySessionIdAndUserId(Long sessionId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select summary from AgentSessionSummary summary "
            + "where summary.sessionId = :sessionId and summary.userId = :userId")
    Optional<AgentSessionSummary> findLockedBySessionIdAndUserId(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId);

    void deleteBySessionId(Long sessionId);
}
