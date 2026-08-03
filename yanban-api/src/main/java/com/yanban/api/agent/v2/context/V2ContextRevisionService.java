package com.yanban.api.agent.v2.context;

import java.util.Optional;
import org.springframework.stereotype.Service;

/** Non-transactional facade: a failed insert is classified in fresh transactions. */
@Service
public class V2ContextRevisionService {
    private final V2ContextRevisionTransactions transactions;
    private final V2ContextRevisionCodec codec;

    public V2ContextRevisionService(
            V2ContextRevisionTransactions transactions,
            V2ContextRevisionCodec codec) {
        this.transactions = transactions;
        this.codec = codec;
    }

    public V2ContextRevisionSnapshot append(V2ContextRevisionDraft draft) {
        V2ContextRevisionCodec.EncodedRevision encoded = codec.encode(draft);
        Optional<V2ContextRevisionSnapshot> existing =
                transactions.readByStableKey(
                        draft.userId(), draft.sessionId(), draft.turnId(),
                        draft.stableStageKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.orElseThrow(), encoded.digest());
        }
        try {
            return transactions.insert(draft, encoded);
        } catch (RuntimeException race) {
            Optional<V2ContextRevisionSnapshot> stageWinner =
                    transactions.readByStableKey(
                            draft.userId(), draft.sessionId(), draft.turnId(),
                            draft.stableStageKey());
            if (stageWinner.isPresent()) {
                return replayOrConflict(
                        stageWinner.orElseThrow(), encoded.digest());
            }
            Optional<V2ContextRevisionSnapshot> revisionWinner =
                    transactions.readByRevision(
                            draft.userId(), draft.sessionId(), draft.turnId(),
                            draft.revisionNumber());
            if (revisionWinner.isPresent()) {
                throw new V2ContextRevisionConflictException(
                        "context revision number is already occupied");
            }
            throw race;
        }
    }

    public Optional<V2ContextRevisionSnapshot> find(
            Long userId, Long sessionId, Long turnId, String stableStageKey) {
        return transactions.readByStableKey(
                userId, sessionId, turnId, stableStageKey);
    }

    private static V2ContextRevisionSnapshot replayOrConflict(
            V2ContextRevisionSnapshot existing, String requestedDigest) {
        if (!existing.contextDigest().equals(requestedDigest)) {
            throw new V2ContextRevisionConflictException(
                    "context stable stage key has conflicting content");
        }
        return existing;
    }
}
