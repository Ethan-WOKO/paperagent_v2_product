package com.yanban.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.cache.ConversationSnapshotCache;
import com.yanban.core.agent.AgentMessageChangeListener;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentMessageCacheService {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageCacheService.class);
    private static final Duration RECENT_TTL = Duration.ofHours(6);
    private static final TypeReference<List<AgentMessageResponse>> MESSAGE_LIST_TYPE = new TypeReference<>() {};

    private final ConversationSnapshotCache snapshots;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public AgentMessageCacheService(ObjectMapper objectMapper,
                                    ObjectProvider<StringRedisTemplate> redisProvider,
                                    ConversationSnapshotCache snapshots) {
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.redis = redisProvider.getIfAvailable();
    }

    public String recentQueryKey(Long userId, Long sessionId, String view, int limit) {
        String generation = snapshots.generation(userId, sessionId);
        return generation == null ? null : ConversationSnapshotCache.scope(userId, sessionId)
                + ":messages:" + generation + ":" + view + ":" + limit;
    }

    public Optional<List<AgentMessageResponse>> getRecentMessages(String queryKey) {
        return snapshots.get(queryKey, MESSAGE_LIST_TYPE);
    }

    public void putRecentMessages(String queryKey, List<AgentMessageResponse> messages) {
        snapshots.put(queryKey, messages);
    }

    @EventListener
    public void messageChanged(AgentMessageChangeListener.Changed event) {
        evictSession(event.userId(), event.sessionId());
    }

    public void putTurnStatus(Long turnId, String status, String errorMessage) {
        if (redis == null || turnId == null) {
            return;
        }
        try {
            String value = objectMapper.writeValueAsString(new TurnStatusCacheValue(status, errorMessage));
            redis.opsForValue().set(turnStatusKey(turnId), value, RECENT_TTL);
        } catch (Exception ex) {
            log.debug("Ignoring Redis turn status write failure turnId={} error={}", turnId, ex.getMessage());
        }
    }

    public void evictSession(Long userId, Long sessionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // Deleting a long conversation should invalidate once, not once per message.
            boolean scheduled = TransactionSynchronizationManager.getSynchronizations().stream()
                    .anyMatch(sync -> sync instanceof SessionInvalidation invalidation
                            && java.util.Objects.equals(invalidation.userId(), userId)
                            && java.util.Objects.equals(invalidation.sessionId(), sessionId));
            if (!scheduled) TransactionSynchronizationManager.registerSynchronization(
                    new SessionInvalidation(userId, sessionId, snapshots));
        } else {
            snapshots.invalidate(userId, sessionId);
        }
    }

    private record SessionInvalidation(Long userId, Long sessionId, ConversationSnapshotCache snapshots)
            implements TransactionSynchronization {
        @Override public void afterCommit() { snapshots.invalidate(userId, sessionId); }
    }

    private String turnStatusKey(Long turnId) {
        return "chat:turn:" + turnId + ":status";
    }

    private record TurnStatusCacheValue(String status, String errorMessage) {}
}
