package com.yanban.api.agent.reactplan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReactPlanUsageSettlementRepository
        extends JpaRepository<ReactPlanUsageSettlementEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from ReactPlanUsageSettlementEntity value where value.taskId=:taskId")
    Optional<ReactPlanUsageSettlementEntity> findLocked(String taskId);

    List<ReactPlanUsageSettlementEntity> findByStateOrderByCreatedAtAsc(
            String state, Pageable pageable);
}
