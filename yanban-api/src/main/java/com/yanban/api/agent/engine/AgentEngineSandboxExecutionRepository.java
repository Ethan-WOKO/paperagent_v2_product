package com.yanban.api.agent.engine;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AgentEngineSandboxExecutionRepository
        extends JpaRepository<AgentEngineSandboxExecutionEntity, Long> {
    Optional<AgentEngineSandboxExecutionEntity> findByTaskIdAndClientRequestId(
            String taskId, String clientRequestId);
    Optional<AgentEngineSandboxExecutionEntity> findByTaskIdAndReceiptRef(
            String taskId, String receiptRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select value from AgentEngineSandboxExecutionEntity value "
            + "where value.taskId=:taskId and value.clientRequestId=:clientRequestId")
    Optional<AgentEngineSandboxExecutionEntity> lock(
            @Param("taskId") String taskId,
            @Param("clientRequestId") String clientRequestId);
}
