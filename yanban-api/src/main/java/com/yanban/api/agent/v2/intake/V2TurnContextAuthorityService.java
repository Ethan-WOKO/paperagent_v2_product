package com.yanban.api.agent.v2.intake;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner/session-qualified bridge from a public request id to its durable turn. */
@Service
public class V2TurnContextAuthorityService {
    private final V2TurnIntakeJpaRepository intakes;

    public V2TurnContextAuthorityService(V2TurnIntakeJpaRepository intakes) {
        this.intakes = intakes;
    }

    @Transactional(readOnly = true)
    public Optional<TurnAuthority> find(
            Long userId, Long sessionId, String clientRequestId) {
        if (userId == null || sessionId == null || clientRequestId == null
                || clientRequestId.isBlank()) {
            return Optional.empty();
        }
        return intakes.findByUserIdAndSessionIdAndClientRequestId(
                        userId, sessionId, clientRequestId)
                .map(value -> new TurnAuthority(value.turnId()));
    }

    public record TurnAuthority(Long turnId) {
        public TurnAuthority {
            if (turnId == null || turnId <= 0) {
                throw new IllegalArgumentException("turnId must be positive");
            }
        }
    }
}
