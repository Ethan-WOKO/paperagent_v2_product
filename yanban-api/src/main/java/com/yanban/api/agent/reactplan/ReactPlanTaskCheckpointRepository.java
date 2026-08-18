package com.yanban.api.agent.reactplan;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ReactPlanTaskCheckpointRepository
        extends JpaRepository<ReactPlanTaskCheckpointEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select checkpoint from ReactPlanTaskCheckpointEntity checkpoint where checkpoint.taskId = :taskId")
    Optional<ReactPlanTaskCheckpointEntity> findLockedByTaskId(String taskId);
    List<ReactPlanTaskCheckpointEntity> findAllByOrderByUpdatedAtAsc();
}
