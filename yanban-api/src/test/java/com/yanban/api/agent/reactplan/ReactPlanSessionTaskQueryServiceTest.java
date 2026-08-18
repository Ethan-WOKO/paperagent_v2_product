package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ReactPlanSessionTaskQueryServiceTest {
    @Mock AgentSessionRepository sessions;
    @Mock AgentMessageRepository messages;
    @Mock ReactPlanTurnIntakeRepository intakes;
    @Mock ReactPlanTaskCheckpointRepository checkpoints;
    @Mock ReactPlanTaskEventRepository events;

    private ReactPlanSessionTaskQueryService service;

    @BeforeEach
    void setUp() {
        service = new ReactPlanSessionTaskQueryService(
                new ObjectMapper(), sessions, messages, intakes, checkpoints, events);
    }

    @Test
    void returnsOwnerQualifiedServerTaskWithPersistedEvents() {
        long userId = 7L;
        long sessionId = 11L;
        long messageId = 23L;
        long turnId = 29L;
        String taskId = "task." + "a".repeat(64);
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 10, 0);
        AgentSession session = new AgentSession(userId, "project", "deepseek", "deepseek-chat",
                24, true, AgentSessionScope.PROJECT, 95L);
        AgentMessage message = new AgentMessage(sessionId, userId, "user", "inspect Sort.java", null, null);
        ReflectionTestUtils.setField(message, "id", messageId);
        ReactPlanTurnIntakeEntity intake = new ReactPlanTurnIntakeEntity(
                userId, sessionId, "request.test", "b".repeat(64), turnId, messageId, taskId, now);
        ReflectionTestUtils.setField(intake, "id", 41L);
        ReactPlanTurnIntakeEntity older = new ReactPlanTurnIntakeEntity(
                userId, sessionId, "request.older", "c".repeat(64), 28L, 22L,
                "task." + "c".repeat(64), now.minusMinutes(1));
        ReflectionTestUtils.setField(older, "id", 40L);
        ReactPlanTaskCheckpointEntity checkpoint = new ReactPlanTaskCheckpointEntity(
                taskId, "b".repeat(64), userId, sessionId, turnId, "succeeded", 2,
                "{\"view\":{\"contractVersion\":\"1.0\",\"taskId\":\"" + taskId
                        + "\",\"state\":\"succeeded\",\"lastSequence\":2}}", now);
        ReactPlanTaskEventEntity event = new ReactPlanTaskEventEntity(
                taskId, 1, "{\"contractVersion\":\"1.0\",\"taskId\":\"" + taskId
                        + "\",\"sequence\":1,\"type\":\"status\",\"state\":\"running\"}", now);

        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));
        when(intakes.findByUserIdAndSessionIdOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of(intake, older));
        when(checkpoints.findByTaskIdIn(List.of(taskId))).thenReturn(List.of(checkpoint));
        when(messages.findAllById(List.of(messageId))).thenReturn(List.of(message));
        when(events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(List.of(taskId)))
                .thenReturn(List.of(event));

        ReactPlanSessionTaskQueryService.SessionTaskPage page =
                service.list(userId, sessionId, true, null, 1);
        List<ReactPlanSessionTaskQueryService.SessionTask> result = page.items();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).instruction()).isEqualTo("inspect Sort.java");
        assertThat(result.get(0).task().path("state").asText()).isEqualTo("succeeded");
        assertThat(result.get(0).events()).hasSize(1);
        assertThat(result.get(0).finishedAt()).isNotNull();
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("intake.41");
    }

    @Test
    void summaryDoesNotReadEventBodies() {
        long userId = 7L;
        long sessionId = 11L;
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(
                new AgentSession(userId, "project", "deepseek", "deepseek-chat",
                        24, true, AgentSessionScope.PROJECT, 95L)));
        when(intakes.findByUserIdAndSessionIdOrderByIdDesc(any(), any(), any()))
                .thenReturn(List.of());

        assertThat(service.list(userId, sessionId, false, null, 12).items()).isEmpty();
        verify(events, never()).findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(any());
    }

    @Test
    void rejectsAConversationThatDoesNotBelongToTheAuthenticatedUser() {
        when(sessions.findByIdAndUserId(11L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(7L, 11L, true, null, 12))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verify(intakes, never()).findByUserIdAndSessionIdOrderByIdDesc(any(), any(), any());
    }

    @Test
    void rejectsAnInvalidPaginationCursor() {
        when(sessions.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(
                new AgentSession(7L, "project", "deepseek", "deepseek-chat",
                        24, true, AgentSessionScope.PROJECT, 95L)));

        assertThatThrownBy(() -> service.list(7L, 11L, true, "not-a-cursor", 12))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        verify(intakes, never()).findByUserIdAndSessionIdOrderByIdDesc(any(), any(), any());
    }
}
