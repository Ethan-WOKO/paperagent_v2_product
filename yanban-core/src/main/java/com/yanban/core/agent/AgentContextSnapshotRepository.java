package com.yanban.core.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AgentContextSnapshotRepository extends JpaRepository<AgentContextSnapshot, Long> {
    Optional<AgentContextSnapshot> findFirstByTurnIdAndSessionIdAndUserIdOrderByRevisionNumberDescIdDesc(
            Long turnId, Long sessionId, Long userId);

    default Optional<AgentContextSnapshot> findByTurnIdAndSessionIdAndUserId(
            Long turnId, Long sessionId, Long userId) {
        return findFirstByTurnIdAndSessionIdAndUserIdOrderByRevisionNumberDescIdDesc(
                turnId, sessionId, userId);
    }

    Optional<AgentContextSnapshot> findByUserIdAndSessionIdAndTurnIdAndStableStageKey(
            Long userId, Long sessionId, Long turnId, String stableStageKey);

    Optional<AgentContextSnapshot> findByTurnIdAndRevisionNumber(
            Long turnId, Integer revisionNumber);

    Optional<AgentContextSnapshot> findByIdAndUserIdAndSessionIdAndTurnId(
            Long id, Long userId, Long sessionId, Long turnId);

    List<AgentContextSnapshot> findBySessionIdAndUserIdOrderByCreatedAtDescIdDesc(
            Long sessionId, Long userId, Pageable page);

    @Query("""
            select snapshot from AgentContextSnapshot snapshot
            where snapshot.sessionId = :sessionId
              and snapshot.userId = :userId
              and snapshot.revisionNumber = (
                select max(candidate.revisionNumber)
                from AgentContextSnapshot candidate
                where candidate.turnId = snapshot.turnId
                  and candidate.sessionId = snapshot.sessionId
                  and candidate.userId = snapshot.userId)
            order by snapshot.createdAt desc, snapshot.id desc
            """)
    List<AgentContextSnapshot> findLatestRevisionPerTurn(
            Long sessionId, Long userId, Pageable page);
}
