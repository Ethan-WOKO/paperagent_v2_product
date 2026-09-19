package com.yanban.api.agent.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Disposable read snapshots. Callers must authorize before returning any cached value. */
@Service
public class ConversationSnapshotCache {
    private static final Logger log = LoggerFactory.getLogger(ConversationSnapshotCache.class);
    private static final String PREFIX = "paperagent:conversation:v1:";
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final Duration GENERATION_TTL = Duration.ofHours(1);
    private static final int MAX_BYTES = 1_048_576;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private volatile long bypassUntil;
    @Value("${yanban.conversation-cache.enabled:true}")
    private boolean enabled = true;

    public ConversationSnapshotCache(ObjectMapper json, ObjectProvider<StringRedisTemplate> provider) {
        this.json = json;
        this.redis = provider.getIfAvailable();
    }

    /** Capture before the first database read, so an old transaction cannot populate a new generation. */
    public String generation(Long userId, Long sessionId) {
        return attempt(() -> {
            String key = PREFIX + scope(userId, sessionId) + ":generation";
            String current = redis.opsForValue().get(key);
            if (current != null) return current;
            String fresh = UUID.randomUUID().toString();
            redis.opsForValue().setIfAbsent(key, fresh, GENERATION_TTL);
            return redis.opsForValue().get(key);
        }, null);
    }

    public void invalidate(Long userId, Long sessionId) {
        // Try writes even during a read bypass: a recovered Redis must not keep the old generation.
        if (!enabled || redis == null || userId == null || sessionId == null) return;
        try {
            redis.opsForValue().set(PREFIX + scope(userId, sessionId) + ":generation",
                    UUID.randomUUID().toString(), GENERATION_TTL);
        } catch (RuntimeException failure) {
            failed(failure);
        }
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        if (key == null) return Optional.empty();
        return attempt(() -> decode(redis.opsForValue().get(PREFIX + key), type), Optional.empty());
    }

    /** One Redis round trip for a page containing several completed tasks. */
    public <T> Map<String, T> getMany(List<String> keys, TypeReference<T> type) {
        if (keys.isEmpty()) return Map.of();
        return attempt(() -> {
            List<String> values = redis.opsForValue().multiGet(keys.stream().map(key -> PREFIX + key).toList());
            Map<String, T> result = new HashMap<>();
            if (values != null) {
                for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
                    String key = keys.get(i);
                    decode(values.get(i), type).ifPresent(value -> result.put(key, value));
                }
            }
            return result;
        }, Map.of());
    }

    private <T> Optional<T> decode(String value, TypeReference<T> type) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return Optional.empty();
        try {
            return Optional.ofNullable(json.readValue(value, type));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    public void put(String key, Object value) {
        if (key == null) return;
        attempt(() -> {
            try {
                String encoded = json.writeValueAsString(value);
                if (encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
                    redis.opsForValue().set(PREFIX + key, encoded, TTL);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                log.debug("Conversation snapshot serialization skipped");
            }
            return true;
        }, false);
    }

    public static String scope(Long userId, Long sessionId) {
        return "user:" + userId + ":session:" + sessionId;
    }

    private <T> T attempt(Supplier<T> work, T fallback) {
        if (!enabled || redis == null || System.nanoTime() < bypassUntil) return fallback;
        try {
            return work.get();
        } catch (RuntimeException failure) {
            failed(failure);
            return fallback;
        }
    }

    private void failed(RuntimeException failure) {
        // Outlive every payload if an invalidation fails; failures never fail database work.
        bypassUntil = System.nanoTime() + TTL.plusSeconds(5).toNanos();
        log.debug("Conversation cache unavailable; using database ({})", failure.getClass().getSimpleName());
    }
}
