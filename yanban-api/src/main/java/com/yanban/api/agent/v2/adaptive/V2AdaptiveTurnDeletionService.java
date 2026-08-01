package com.yanban.api.agent.v2.adaptive;

import org.springframework.stereotype.Service;

/** Deletes adaptive records before their owning V2 intake is removed. */
@Service
public final class V2AdaptiveTurnDeletionService {
    private final V2AdaptiveTurnRepository turns;

    public V2AdaptiveTurnDeletionService(V2AdaptiveTurnRepository turns) {
        this.turns = turns;
    }

    public void deleteOwnedSessionData(Long userId, Long sessionId) {
        turns.deleteByUserIdAndSessionId(userId, sessionId);
        turns.flush();
    }
}
