package com.yanban.api.agent.engine;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface ProductEngineTurnRepository extends JpaRepository<ProductEngineTurnEntity, Long> {
    Optional<ProductEngineTurnEntity> findByUserIdAndSessionIdAndRootClientRequestId(
            Long userId, Long sessionId, String rootClientRequestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from ProductEngineTurnEntity value where value.userId = :userId and value.sessionId = :sessionId and value.rootClientRequestId = :requestId")
    Optional<ProductEngineTurnEntity> findLockedByUserIdAndSessionIdAndRootClientRequestId(
            @Param("userId") Long userId, @Param("sessionId") Long sessionId,
            @Param("requestId") String rootClientRequestId);
    List<ProductEngineTurnEntity> findByUserIdAndSessionIdOrderByCreatedAtDescIdDesc(
            Long userId, Long sessionId, Pageable pageable);
}
