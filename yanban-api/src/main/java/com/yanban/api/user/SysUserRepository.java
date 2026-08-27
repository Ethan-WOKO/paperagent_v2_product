package com.yanban.api.user;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsername(String username);

    Optional<SysUser> findByUsernameAndDeletedAtIsNull(String username);

    Optional<SysUser> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByUsername(String username);

    List<SysUser> findByAccountTypeIgnoreCase(String accountType);

    List<SysUser> findByDeletedAtIsNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from SysUser user where user.id = :userId")
    Optional<SysUser> findLockedById(Long userId);
}
