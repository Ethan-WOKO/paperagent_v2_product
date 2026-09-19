package com.yanban.api.agent.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.*;
import com.yanban.core.agent.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class ConversationReadCacheTest {
    final Map<String, String> data = new HashMap<>();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    ConversationSnapshotCache snapshots;
    AgentMessageCacheService cache;
    AgentService service;
    final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    final TypeReference<List<String>> strings = new TypeReference<>() {};

    @BeforeEach
    void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call ->
                data.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var beans = new StaticListableBeanFactory();
        beans.addBean("redis", redis);
        var provider = beans.getBeanProvider(StringRedisTemplate.class);
        var json = new ObjectMapper().findAndRegisterModules();
        snapshots = new ConversationSnapshotCache(json, provider);
        cache = new AgentMessageCacheService(json, provider, snapshots);
        service = mock(AgentService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "sessions", sessions);
        ReflectionTestUtils.setField(service, "messages", messages);
        ReflectionTestUtils.setField(service, "messageCache", cache);
        AgentSession session = new AgentSession(7L, "test", "mock", "model", 24, false);
        ReflectionTestUtils.setField(session, "id", 11L);
        when(sessions.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(session));
    }

    @Test void repeatedAllViewReadsSkipMessageQueryButAlwaysCheckOwnership() {
        var tool = message(1L, "tool", "tool evidence");
        when(messages.findBySessionIdOrderByIdDesc(eq(11L), any()))
                .thenAnswer(call -> new ArrayList<>(List.of(tool)));
        assertThat(service.listMessages(7L, 11L, 50, null, "all")).extracting(AgentMessageResponse::role).containsExactly("tool");
        assertThat(service.listMessages(7L, 11L, 50, null, "all")).hasSize(1);
        verify(messages, times(1)).findBySessionIdOrderByIdDesc(eq(11L), any());
        verify(sessions, times(2)).findByIdAndUserId(11L, 7L);
        when(sessions.findByIdAndUserId(11L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listMessages(7L, 11L, 50, null, "all"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void exactQueryShapeSeparatesLimitViewAndOwner() {
        String small = cache.recentQueryKey(7L, 11L, "chat", 1);
        cache.putRecentMessages(small, List.of(AgentMessageResponse.from(message(1L,"user","one"))));
        assertThat(cache.getRecentMessages(cache.recentQueryKey(7L,11L,"chat",50))).isEmpty();
        assertThat(cache.getRecentMessages(cache.recentQueryKey(7L,11L,"all",1))).isEmpty();
        assertThat(cache.getRecentMessages(cache.recentQueryKey(8L,11L,"chat",1))).isEmpty();
        assertThat(cache.getRecentMessages(small)).isPresent();
    }

    @Test void lateReadCannotRepopulateCurrentGenerationAfterMutation() {
        String old = cache.recentQueryKey(7L,11L,"all",50);
        cache.messageChanged(new AgentMessageChangeListener.Changed(7L,11L));
        cache.putRecentMessages(old, List.of(AgentMessageResponse.from(message(1L,"assistant","old"))));
        String current = cache.recentQueryKey(7L,11L,"all",50);
        assertThat(current).isNotEqualTo(old);
        assertThat(cache.getRecentMessages(current)).isEmpty();
    }

    @Test void mutationOnColdCacheNeverCreatesPartialHistory() {
        cache.messageChanged(new AgentMessageChangeListener.Changed(7L,11L));
        assertThat(cache.getRecentMessages(cache.recentQueryKey(7L,11L,"all",50))).isEmpty();
    }

    @Test void historicalPaginationBypassesRecentCache() {
        when(messages.findBySessionIdAndIdLessThanOrderByIdDesc(eq(11L),eq(10L),any()))
                .thenReturn(new ArrayList<>());
        service.listMessages(7L,11L,50,10L,"all");
        verifyNoInteractions(redis);
        verify(messages).findBySessionIdAndIdLessThanOrderByIdDesc(eq(11L),eq(10L),any());
    }

    @Test void redisFailureFallsBackAndDoesNotRepeatReadTimeouts() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("unavailable"));
        when(messages.findBySessionIdOrderByIdDesc(eq(11L),any())).thenAnswer(call -> new ArrayList<>());
        assertThat(service.listMessages(7L,11L,50,null,"all")).isEmpty();
        assertThat(service.listMessages(7L,11L,50,null,"all")).isEmpty();
        verify(values,times(1)).get(anyString());
        verify(messages,times(2)).findBySessionIdOrderByIdDesc(eq(11L),any());
    }

    @Test void boundedPayloadsAndMalformedJsonBecomeMisses() {
        snapshots.put("large", List.of("x".repeat(1_048_577)));
        assertThat(data).doesNotContainKey("paperagent:conversation:v1:large");
        data.put("paperagent:conversation:v1:broken", "not json");
        assertThat(snapshots.get("broken", strings)).isEmpty();
        snapshots.put("small",List.of("ok"));
        assertThat(snapshots.get("small",strings)).contains(List.of("ok"));
        verify(values).set(eq("paperagent:conversation:v1:small"),anyString(),eq(Duration.ofMinutes(2)));
    }

    @Test void redisWriteFailureDoesNotFailDatabaseResult() {
        doThrow(new IllegalStateException("write failed")).when(values).set(anyString(),anyString(),any(Duration.class));
        when(messages.findBySessionIdOrderByIdDesc(eq(11L),any()))
                .thenAnswer(call -> new ArrayList<>(List.of(message(1L,"user","saved"))));
        assertThat(service.listMessages(7L,11L,50,null,"all")).hasSize(1);
    }

    @Test void featureSwitchBypassesRedisReadsAndWrites() {
        ReflectionTestUtils.setField(snapshots,"enabled",false);
        when(messages.findBySessionIdOrderByIdDesc(eq(11L),any())).thenAnswer(call -> new ArrayList<>());
        service.listMessages(7L,11L,50,null,"all");
        snapshots.invalidate(7L,11L);
        verifyNoInteractions(redis);
        verify(messages).findBySessionIdOrderByIdDesc(eq(11L),any());
    }

    @Test void aPageOfTaskSnapshotsUsesOneRedisReadAndSkipsCorruptEntries() {
        when(values.multiGet(anyCollection())).thenReturn(Arrays.asList("[\"one\"]",null,"broken","[\"four\"]"));
        assertThat(snapshots.getMany(List.of("a","b","c","d"),strings))
                .containsOnlyKeys("a","d").containsEntry("a",List.of("one")).containsEntry("d",List.of("four"));
        verify(values,times(1)).multiGet(anyCollection());
        verify(values,never()).get(anyString());
    }

    private AgentMessage message(long id, String role, String body) {
        AgentMessage message = new AgentMessage(11L,7L,role,body,null,null);
        ReflectionTestUtils.setField(message,"id",id);
        return message;
    }
}
