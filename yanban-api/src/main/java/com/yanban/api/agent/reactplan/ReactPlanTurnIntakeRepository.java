package com.yanban.api.agent.reactplan;

import com.yanban.core.agent.AgentSessionScope;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ReactPlanTurnIntakeRepository
        extends JpaRepository<ReactPlanTurnIntakeEntity, Long> {
    Optional<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdAndClientRequestId(
            Long userId, Long sessionId, String clientRequestId);
    Optional<ReactPlanTurnIntakeEntity> findByTaskId(String taskId);
    Optional<ReactPlanTurnIntakeEntity> findFirstByUserIdAndSessionIdOrderByIdAsc(
            Long userId, Long sessionId);
    List<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdOrderByIdAsc(
            Long userId, Long sessionId);
    List<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdOrderByIdDesc(
            Long userId, Long sessionId, Pageable pageable);
    List<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdAndIdLessThanOrderByIdDesc(
            Long userId, Long sessionId, Long id, Pageable pageable);
    @Query("select intake from ReactPlanTurnIntakeEntity intake, "
            + "ReactPlanTaskCheckpointEntity checkpoint where intake.taskId=checkpoint.taskId "
            + "and intake.userId=:userId and intake.sessionId=:sessionId "
            + "and checkpoint.state in ('succeeded','failed','cancelled') order by intake.id asc")
    List<ReactPlanTurnIntakeEntity> findTerminalByOwnerAndSession(
            Long userId, Long sessionId);

    @Query("select intake from ReactPlanTurnIntakeEntity intake, AgentSession session, "
            + "ReactPlanTaskCheckpointEntity checkpoint "
            + "where session.id=intake.sessionId and checkpoint.taskId=intake.taskId "
            + "and intake.userId=:userId and session.userId=:userId "
            + "and session.scope=:scope and session.projectId=:projectId "
            + "and checkpoint.userId=:userId and checkpoint.sessionId=intake.sessionId "
            + "and checkpoint.turnId=intake.turnId "
            + "and checkpoint.state in ('succeeded','failed','cancelled') "
            + "and intake.taskId<>:currentTaskId "
            + "and (:sessionId is null or intake.sessionId=:sessionId) "
            + "and (:beforeId is null or intake.id<:beforeId) "
            + "and (:state is null or checkpoint.state=:state) "
            + "order by intake.id desc")
    List<ReactPlanTurnIntakeEntity> findTerminalHistoryCandidates(
            Long userId, Long projectId, AgentSessionScope scope,
            Long sessionId, Long beforeId, String state, String currentTaskId,
            Pageable pageable);
}
