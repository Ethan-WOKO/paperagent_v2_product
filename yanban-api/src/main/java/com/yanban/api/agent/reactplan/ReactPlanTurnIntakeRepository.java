package com.yanban.api.agent.reactplan;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReactPlanTurnIntakeRepository
        extends JpaRepository<ReactPlanTurnIntakeEntity, Long> {
    Optional<ReactPlanTurnIntakeEntity> findByUserIdAndSessionIdAndClientRequestId(
            Long userId, Long sessionId, String clientRequestId);
}
