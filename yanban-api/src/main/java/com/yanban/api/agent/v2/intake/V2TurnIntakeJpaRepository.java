package com.yanban.api.agent.v2.intake;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface V2TurnIntakeJpaRepository
        extends JpaRepository<V2TurnIntakeEntity, Long> {
    Optional<V2TurnIntakeEntity> findByUserIdAndSessionIdAndClientRequestId(
            Long userId, Long sessionId, String clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intake from V2TurnIntakeEntity intake "
            + "where intake.userId = :userId "
            + "and intake.sessionId = :sessionId "
            + "and intake.clientRequestId = :clientRequestId")
    Optional<V2TurnIntakeEntity> findLocked(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("clientRequestId") String clientRequestId);
}
