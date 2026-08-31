package com.yanban.core.agent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {
    List<AgentMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<AgentMessage> findBySessionIdOrderByIdDesc(Long sessionId, Pageable pageable);

    List<AgentMessage> findBySessionIdAndIdLessThanOrderByIdDesc(Long sessionId, Long beforeId, Pageable pageable);

    List<AgentMessage> findBySessionIdAndRoleInOrderByIdDesc(Long sessionId, Collection<String> roles, Pageable pageable);

    List<AgentMessage> findBySessionIdAndRoleInAndIdLessThanOrderByIdDesc(Long sessionId, Collection<String> roles, Long beforeId, Pageable pageable);

    Optional<AgentMessage> findFirstBySessionIdAndToolCallIdOrderByIdDesc(Long sessionId, String toolCallId);

    long countByUserIdAndRoleAndCreatedAtAfter(Long userId, String role, Instant createdAt);

    @Query("""
            select message from AgentMessage message
            where message.userId = :userId
              and message.id > :afterId
              and message.role in :roles
            order by message.id asc
            """)
    List<AgentMessage> findDistillationWindow(
            @Param("userId") Long userId,
            @Param("afterId") Long afterId,
            @Param("roles") Collection<String> roles,
            Pageable page);

    @Query("""
            select message from AgentMessage message
            where message.userId = :userId
              and message.id > :afterId
              and message.id <= :throughId
              and message.role in :roles
            order by message.id asc
            """)
    List<AgentMessage> findDistillationWindow(
            @Param("userId") Long userId,
            @Param("afterId") Long afterId,
            @Param("throughId") Long throughId,
            @Param("roles") Collection<String> roles);

    void deleteBySessionId(Long sessionId);
}
