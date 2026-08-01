package com.yanban.api.agent;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.intake.V2SessionDeletionService;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentTurnRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentServiceSessionDeletionTest {

    @Test
    void deletesV2DependentsBeforeTurnsMessagesAndSession() {
        var sessions = mock(AgentSessionRepository.class);
        var messages = mock(AgentMessageRepository.class);
        var turns = mock(AgentTurnRepository.class);
        var cache = mock(AgentMessageCacheService.class);
        var v2Data = mock(V2SessionDeletionService.class);
        var session = mock(AgentSession.class);
        when(session.getId()).thenReturn(45L);
        when(sessions.findByIdAndUserId(45L, 7L))
                .thenReturn(Optional.of(session));
        var service = new AgentService(
                sessions, messages, turns, cache,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, v2Data);

        service.deleteSession(7L, 45L);

        var order = inOrder(v2Data, turns, messages, cache, sessions);
        order.verify(v2Data).deleteOwnedSessionData(7L, 45L);
        order.verify(turns).deleteBySessionId(45L);
        order.verify(messages).deleteBySessionId(45L);
        order.verify(cache).evictSession(7L, 45L);
        order.verify(sessions).delete(session);
        order.verify(sessions).flush();
    }
}
