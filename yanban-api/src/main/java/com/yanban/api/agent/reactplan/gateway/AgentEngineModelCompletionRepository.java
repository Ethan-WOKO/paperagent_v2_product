package com.yanban.api.agent.reactplan.gateway;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AgentEngineModelCompletionRepository extends JpaRepository<AgentEngineModelCompletionEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from AgentEngineModelCompletionEntity value where value.taskId=:taskId and value.clientRequestId=:clientRequestId")
    Optional<AgentEngineModelCompletionEntity> lock(@Param("taskId") String taskId,
                                                    @Param("clientRequestId") String clientRequestId);
}
