package com.yanban.api.agent.reactplan;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReactPlanConversationSummaryRepository
        extends JpaRepository<ReactPlanConversationSummaryEntity, Long> {
    Optional<ReactPlanConversationSummaryEntity> findBySessionIdAndUserId(Long sessionId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from ReactPlanConversationSummaryEntity value where value.sessionId=:sessionId")
    Optional<ReactPlanConversationSummaryEntity> findLocked(long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from ReactPlanConversationSummaryEntity value "
            + "where (value.state='PENDING' and (value.leaseExpiresAt is null "
            + "or value.leaseExpiresAt <= :now)) or (value.state='PROCESSING' "
            + "and value.leaseExpiresAt <= :now) order by value.updatedAt asc")
    List<ReactPlanConversationSummaryEntity> findClaimable(LocalDateTime now, Pageable pageable);
}
