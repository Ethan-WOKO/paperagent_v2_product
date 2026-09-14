package com.yanban.api.skills;

import com.yanban.api.user.SysUser;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSkillRepository extends JpaRepository<UserSkillEntity, String> {
    List<UserSkillEntity> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);
    Optional<UserSkillEntity> findByIdAndUserId(String id, Long userId);
    boolean existsByUserIdAndNameKey(Long userId, String nameKey);
    long countByUserId(Long userId);

    // Serializes per-owner quota and name checks across requests and API instances.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from SysUser u where u.id = :userId and u.deletedAt is null")
    Optional<SysUser> lockOwner(@Param("userId") Long userId);
}
