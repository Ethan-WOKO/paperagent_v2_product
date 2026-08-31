package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemoryDistillationConversationServiceTest {
    @Mock
    private AgentMessageRepository messages;

    @Mock
    private AgentSessionRepository sessions;

    private MemoryDistillationConversationService service;

    @BeforeEach
    void setUp() {
        service = new MemoryDistillationConversationService(
                messages, sessions, new MemoryDistillationProperties());
    }

    @Test
    void freezesAnImmutableBoundedWindow() {
        AgentMessage first = message(11L, 1L, 42L, "user", "first");
        AgentMessage second = message(12L, 1L, 42L, "assistant", "second");
        when(messages.findDistillationWindow(
                eq(42L), eq(10L), org.mockito.ArgumentMatchers.<String>anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        MemoryDistillationConversationService.FrozenWindow window = service.freeze(42L, 10L);

        assertThat(window.fromMessageId()).isEqualTo(10L);
        assertThat(window.throughMessageId()).isEqualTo(12L);
        assertThat(window.messageCount()).isEqualTo(2);
        assertThat(window.hasWork()).isTrue();
    }

    @Test
    void limitsEachWindowToTheConfiguredNumberOfUserAssessments() {
        MemoryDistillationProperties properties = new MemoryDistillationProperties();
        properties.setMaxCandidates(2);
        service = new MemoryDistillationConversationService(messages, sessions, properties);
        AgentMessage firstUser = message(11L, 1L, 42L, "user", "first");
        AgentMessage assistant = message(12L, 1L, 42L, "assistant", "reply");
        AgentMessage secondUser = message(13L, 1L, 42L, "user", "second");
        AgentMessage nextUser = message(14L, 1L, 42L, "user", "next window");
        when(messages.findDistillationWindow(
                eq(42L), eq(10L), org.mockito.ArgumentMatchers.<String>anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(firstUser, assistant, secondUser, nextUser));

        MemoryDistillationConversationService.FrozenWindow window = service.freeze(42L, 10L);

        assertThat(window.throughMessageId()).isEqualTo(13L);
        assertThat(window.messageCount()).isEqualTo(3);
    }

    @Test
    void loadsOnlyOwnerQualifiedWorkspaceAndExactProjectSessions() {
        AgentMessage workspace = message(21L, 1L, 42L, "user", "workspace preference");
        AgentMessage project = message(22L, 2L, 42L, "user", "project decision");
        AgentMessage forged = message(23L, 3L, 42L, "assistant", "foreign session data");
        when(messages.findDistillationWindow(
                eq(42L), eq(20L), eq(23L), org.mockito.ArgumentMatchers.<String>anyCollection()))
                .thenReturn(List.of(workspace, project, forged));

        AgentSession workspaceSession = session(1L, 42L, AgentSessionScope.WORKSPACE, null);
        AgentSession projectSession = session(2L, 42L, AgentSessionScope.PROJECT, 7L);
        AgentSession foreignSession = session(3L, 99L, AgentSessionScope.WORKSPACE, null);
        when(sessions.findAllById(any())).thenReturn(List.of(workspaceSession, projectSession, foreignSession));

        List<MemoryDistillationConversationService.ConversationLine> lines = service.load(42L, 20L, 23L);

        assertThat(lines).extracting(MemoryDistillationConversationService.ConversationLine::messageId)
                .containsExactly(21L, 22L);
        assertThat(lines).extracting(MemoryDistillationConversationService.ConversationLine::scope)
                .containsExactly("USER", "PROJECT");
        assertThat(lines.get(1).projectId()).isEqualTo(7L);
        assertThat(lines).extracting(MemoryDistillationConversationService.ConversationLine::content)
                .doesNotContain("foreign session data");
    }

    private AgentMessage message(long id, long sessionId, long userId, String role, String content) {
        AgentMessage message = new AgentMessage(sessionId, userId, role, content, null, null);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }

    private AgentSession session(long id, long userId, AgentSessionScope scope, Long projectId) {
        AgentSession session = new AgentSession(userId, "test", "openai", "gpt-test", 4, false, scope, projectId);
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }
}
