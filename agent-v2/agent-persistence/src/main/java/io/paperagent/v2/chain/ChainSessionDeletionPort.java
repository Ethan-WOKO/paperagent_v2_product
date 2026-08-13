package io.paperagent.v2.chain;

/** Product-owned deletion adapter must verify ownership and delete task rows before global instructions/commands. */
public interface ChainSessionDeletionPort {
    long deleteOwnedSessionData(long userId, long sessionId);
}
