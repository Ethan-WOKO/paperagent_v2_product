package com.yanban.core.agent;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentLongTermMemoryRepository extends JpaRepository<AgentLongTermMemory, Long> {
    Optional<AgentLongTermMemory> findByIdAndUserId(Long id, Long userId);

    Optional<AgentLongTermMemory> findByUserIdAndSourceTypeAndSourceRefId(
            Long userId, String sourceType, String sourceRefId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select memory from AgentLongTermMemory memory where memory.id = :id and memory.userId = :userId")
    Optional<AgentLongTermMemory> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    List<AgentLongTermMemory> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status, Pageable page);

    @Query("""
            select memory from AgentLongTermMemory memory
            where memory.userId = :userId
              and memory.status = 'ACTIVE'
              and memory.invalidatedAt is null
              and memory.deletedAt is null
              and (memory.expiresAt is null or memory.expiresAt > :now)
            order by memory.updatedAt desc
            """)
    List<AgentLongTermMemory> findActiveForGovernance(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            Pageable page);

    List<AgentLongTermMemory> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable page);

    @Query("""
            select memory from AgentLongTermMemory memory
            where memory.userId = :userId
              and memory.scope = 'USER'
              and memory.projectId is null
              and memory.status = 'ACTIVE'
              and memory.confirmationStatus = 'CONFIRMED'
              and memory.confirmedAt is not null
              and memory.invalidatedAt is null
              and memory.deletedAt is null
              and (memory.expiresAt is null or memory.expiresAt > :now)
            order by memory.updatedAt desc, memory.id asc
            """)
    List<AgentLongTermMemory> findGovernedUserCandidates(
            @Param("userId") Long userId,
            @Param("now") Instant now,
            Pageable page);

    @Query("""
            select memory from AgentLongTermMemory memory
            where memory.userId = :userId
              and memory.projectId = :projectId
              and memory.projectVersion = :projectVersion
              and memory.scope = 'PROJECT'
              and memory.status = 'ACTIVE'
              and memory.confirmationStatus = 'CONFIRMED'
              and memory.confirmedAt is not null
              and memory.invalidatedAt is null
              and memory.deletedAt is null
              and (memory.expiresAt is null or memory.expiresAt > :now)
            order by memory.updatedAt desc, memory.id asc
            """)
    List<AgentLongTermMemory> findGovernedProjectCandidates(
            @Param("userId") Long userId,
            @Param("projectId") Long projectId,
            @Param("projectVersion") String projectVersion,
            @Param("now") Instant now,
            Pageable page);
}
