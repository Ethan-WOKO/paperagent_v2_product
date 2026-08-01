package com.yanban.api.agent.v2.intake;

import com.yanban.api.agent.v2.adaptive.V2AdaptiveTurnDeletionService;
import org.springframework.stereotype.Service;

/** Removes V2-owned rows that reference a legacy session, turn, or message. */
@Service
public final class V2SessionDeletionService {
    private final V2AdaptiveTurnDeletionService adaptiveTurns;
    private final V2TurnIntakeJpaRepository intakes;

    public V2SessionDeletionService(
            V2AdaptiveTurnDeletionService adaptiveTurns,
            V2TurnIntakeJpaRepository intakes) {
        this.adaptiveTurns = adaptiveTurns;
        this.intakes = intakes;
    }

    public void deleteOwnedSessionData(Long userId, Long sessionId) {
        adaptiveTurns.deleteOwnedSessionData(userId, sessionId);
        intakes.deleteByUserIdAndSessionId(userId, sessionId);
        intakes.flush();
    }
}
