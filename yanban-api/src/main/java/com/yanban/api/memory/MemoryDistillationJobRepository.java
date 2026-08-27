package com.yanban.api.memory;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MemoryDistillationJobRepository extends JpaRepository<MemoryDistillationJobEntity, Long> {
    Optional<MemoryDistillationJobEntity> findByIdAndUserId(Long id, Long userId);

    Optional<MemoryDistillationJobEntity> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<MemoryDistillationJobEntity> findFirstByUserIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long userId, List<String> statuses);

    boolean existsByUserIdAndStatusIn(Long userId, List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from MemoryDistillationJobEntity job where job.id = :id")
    Optional<MemoryDistillationJobEntity> findLocked(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from MemoryDistillationJobEntity job, SysUser user
            where user.id = job.userId
              and user.deletedAt is null
              and (job.status = 'PENDING'
               or (job.status = 'RUNNING' and job.claimedUntil is not null and job.claimedUntil <= :now))
            order by job.id asc
            """)
    List<MemoryDistillationJobEntity> findClaimable(@Param("now") Instant now, Pageable page);
}
