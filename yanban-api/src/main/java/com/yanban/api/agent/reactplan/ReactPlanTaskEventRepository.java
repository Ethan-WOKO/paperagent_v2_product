package com.yanban.api.agent.reactplan;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReactPlanTaskEventRepository
        extends JpaRepository<ReactPlanTaskEventEntity, ReactPlanTaskEventEntity.Key> {
    List<ReactPlanTaskEventEntity> findByTaskIdOrderBySequenceNumberAsc(String taskId);
    Optional<ReactPlanTaskEventEntity> findTopByTaskIdOrderBySequenceNumberDesc(String taskId);
    Optional<ReactPlanTaskEventEntity> findByTaskIdAndSequenceNumber(String taskId, long sequenceNumber);
    List<ReactPlanTaskEventEntity> findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(
            Collection<String> taskIds);
}
