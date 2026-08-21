package com.yanban.api.agent.reactplan.gateway;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface AgentEngineRegisteredToolCallRepository
        extends JpaRepository<AgentEngineRegisteredToolCallEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from AgentEngineRegisteredToolCallEntity value "
            + "where value.taskId=:taskId and value.callId=:callId")
    Optional<AgentEngineRegisteredToolCallEntity> lock(String taskId, String callId);
}
