package com.yanban.api.agent.cache;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Explicit opt-in: use a disposable Redis, never flush a developer/shared Redis. */
@EnabledIfSystemProperty(named = "conversation.test.redis.port", matches = "[0-9]+")
class ConversationRedisIntegrationTest {
    @Test void realRedisStoresBoundedSnapshotsAndRotatesGenerations() {
        int port = Integer.parseInt(System.getProperty("conversation.test.redis.port"));
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",port),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        factory.afterPropertiesSet();
        factory.start();
        var redis = new StringRedisTemplate(factory);
        var beans = new StaticListableBeanFactory();
        beans.addBean("redis",redis);
        var cache = new ConversationSnapshotCache(new ObjectMapper(),beans.getBeanProvider(StringRedisTemplate.class));
        String key = "test:" + UUID.randomUUID();
        long userId = System.nanoTime();
        String generationKey = "paperagent:conversation:v1:" + ConversationSnapshotCache.scope(userId,1L) + ":generation";
        try {
            String initial = cache.generation(userId,1L);
            assertThat(initial).isNotBlank();
            assertThat(cache.generation(userId,1L)).isEqualTo(initial);
            cache.put(key,List.of("history"));
            assertThat(cache.get(key,new TypeReference<List<String>>() {})).contains(List.of("history"));
            assertThat(cache.getMany(List.of(key,key + ":missing"),new TypeReference<List<String>>() {}))
                    .containsOnlyKeys(key).containsEntry(key,List.of("history"));
            assertThat(redis.getExpire("paperagent:conversation:v1:" + key)).isBetween(1L,120L);
            cache.invalidate(userId,1L);
            assertThat(cache.generation(userId,1L)).isNotEqualTo(initial);
        } finally {
            redis.delete(List.of("paperagent:conversation:v1:" + key,generationKey));
            factory.destroy();
        }
    }
}
