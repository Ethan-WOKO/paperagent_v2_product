package com.yanban.api.agent.reactplan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReactPlanTurnIntakeRepository
        extends JpaRepository<ReactPlanTurnIntakeEntity, Long> {
    Optional<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdAndClientRequestId(
            Long userId, Long sessionId, String clientRequestId);
    Optional<ReactPlanTurnIntakeEntity> findByTaskId(String taskId);
    List<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdOrderByIdDesc(
            Long userId, Long sessionId, Pageable pageable);
    List<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdAndIdLessThanOrderByIdDesc(
            Long userId, Long sessionId, Long id, Pageable pageable);
    @org.springframework.data.jpa.repository.Query("select intake from ReactPlanTurnIntakeEntity intake, "
            + "ReactPlanTaskCheckpointEntity checkpoint where intake.taskId=checkpoint.taskId "
            + "and intake.userId=:userId and intake.sessionId=:sessionId "
            + "and checkpoint.state in ('succeeded','failed','cancelled') order by intake.id asc")
    List<ReactPlanTurnIntakeEntity> findTerminalByOwnerAndSession(
            Long userId, Long sessionId);
}
