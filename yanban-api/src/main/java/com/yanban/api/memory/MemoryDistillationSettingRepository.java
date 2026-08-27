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

interface MemoryDistillationSettingRepository extends JpaRepository<MemoryDistillationSettingEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select setting from MemoryDistillationSettingEntity setting where setting.userId = :userId")
    Optional<MemoryDistillationSettingEntity> findLocked(@Param("userId") Long userId);

    @Query("""
            select setting from MemoryDistillationSettingEntity setting, SysUser user
            where user.id = setting.userId
              and user.deletedAt is null
              and setting.autoEnabled = true
              and setting.nextRunAt is not null
              and setting.nextRunAt <= :now
            order by setting.nextRunAt asc, setting.userId asc
            """)
    List<MemoryDistillationSettingEntity> findDue(@Param("now") Instant now, Pageable page);
}
