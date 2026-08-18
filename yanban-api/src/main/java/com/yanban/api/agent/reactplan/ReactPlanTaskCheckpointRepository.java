package com.yanban.api.agent.reactplan;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReactPlanTaskCheckpointRepository
        extends JpaRepository<ReactPlanTaskCheckpointEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkpoint from ReactPlanTaskCheckpointEntity checkpoint where checkpoint.taskId = :taskId")
    Optional<ReactPlanTaskCheckpointEntity> findLockedByTaskId(String taskId);
    List<ReactPlanTaskCheckpointEntity> findAllByOrderByUpdatedAtAsc();
    List<ReactPlanTaskCheckpointEntity> findByTaskIdIn(Collection<String> taskIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkpoint from ReactPlanTaskCheckpointEntity checkpoint "
            + "where checkpoint.state in ('queued','running') "
            + "and (checkpoint.leaseExpiresAt is null or checkpoint.leaseExpiresAt <= :now) "
            + "order by checkpoint.createdAt asc")
    List<ReactPlanTaskCheckpointEntity> findClaimable(LocalDateTime now, Pageable page);
}
