package com.yanban.api.agent.v2.adaptive;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface V2AdaptiveTurnRepository
        extends JpaRepository<V2AdaptiveTurnEntity, Long> {
    Optional<V2AdaptiveTurnEntity> findByUserIdAndSessionIdAndClientRequestId(
            Long userId, Long sessionId, String clientRequestId);
}
