package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReactPlanAdminConversationReaderTest {

    @Mock AgentMessageRepository messages;
    @Mock ReactPlanTurnIntakeRepository intakes;
    @Mock ReactPlanTaskCheckpointRepository checkpoints;
    @Mock ReactPlanTaskEventRepository events;

    private ReactPlanAdminConversationReader reader;

    @BeforeEach
    void setUp() {
        reader = new ReactPlanAdminConversationReader(
                new ObjectMapper(), messages, intakes, checkpoints, events);
    }

    @Test
    void projectsOnlyUserInstructionAndPublicFinalDelivery() {
        long userId = 7L;
        long sessionId = 11L;
        String taskId = "task." + "a".repeat(64);
        LocalDateTime started = LocalDateTime.parse("2026-08-19T10:00:00");
        ReactPlanTurnIntakeEntity intake = intake(
                31L, userId, sessionId, 41L, 51L, taskId, started);
        AgentMessage instruction = message(
                51L, userId, sessionId, "检查 Sort.java", started);
        ReactPlanTaskCheckpointEntity checkpoint = checkpoint(
                taskId, userId, sessionId, 41L, "succeeded", started.plusSeconds(8));
        ReactPlanTaskEventEntity internal = event(taskId, 1,
                "{\"type\":\"tool\",\"raw\":\"SECRET_VALUE\"}", started.plusSeconds(2));
        ReactPlanTaskEventEntity delivery = event(taskId, 2,
                "{\"type\":\"delivery\",\"conclusion\":\"Sort.java 编译成功。\","
                        + "\"raw\":\"SECRET_VALUE\"}", started.plusSeconds(7));

        when(intakes.findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId))
                .thenReturn(List.of(intake));
        when(messages.findAllById(List.of(51L))).thenReturn(List.of(instruction));
        when(checkpoints.findByTaskIdIn(List.of(taskId))).thenReturn(List.of(checkpoint));
        when(events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(List.of(taskId)))
                .thenReturn(List.of(internal, delivery));

        List<ReactPlanAdminConversationReader.ConversationMessage> result =
                reader.read(userId, sessionId);

        assertThat(result).extracting(ReactPlanAdminConversationReader.ConversationMessage::role)
                .containsExactly("user", "assistant");
        assertThat(result).extracting(ReactPlanAdminConversationReader.ConversationMessage::content)
                .containsExactly("检查 Sort.java", "Sort.java 编译成功。");
        assertThat(result.toString()).doesNotContain("SECRET_VALUE", "tool");
    }

    @Test
    void explainsTerminalTaskWhenNoDeliveryWasStored() {
        long userId = 7L;
        long sessionId = 11L;
        String taskId = "task." + "b".repeat(64);
        LocalDateTime started = LocalDateTime.parse("2026-08-19T10:00:00");
        ReactPlanTurnIntakeEntity intake = intake(
                32L, userId, sessionId, 42L, 52L, taskId, started);
        AgentMessage instruction = message(
                52L, userId, sessionId, "运行测试", started);
        ReactPlanTaskCheckpointEntity checkpoint = checkpoint(
                taskId, userId, sessionId, 42L, "cancelled", started.plusSeconds(3));

        when(intakes.findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId))
                .thenReturn(List.of(intake));
        when(messages.findAllById(List.of(52L))).thenReturn(List.of(instruction));
        when(checkpoints.findByTaskIdIn(List.of(taskId))).thenReturn(List.of(checkpoint));
        when(events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(List.of(taskId)))
                .thenReturn(List.of());

        assertThat(reader.read(userId, sessionId)).last().satisfies(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("已取消", "没有生成最终回答");
        });
    }

    private ReactPlanTurnIntakeEntity intake(
            long id, long userId, long sessionId, long turnId,
            long messageId, String taskId, LocalDateTime createdAt) {
        ReactPlanTurnIntakeEntity value = new ReactPlanTurnIntakeEntity(
                userId, sessionId, "request-" + id, "a".repeat(64),
                turnId, messageId, taskId, createdAt);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private AgentMessage message(
            long id, long userId, long sessionId, String content, LocalDateTime createdAt) {
        AgentMessage value = new AgentMessage(
                sessionId, userId, "user", content, null, null);
        ReflectionTestUtils.setField(value, "id", id);
        ReflectionTestUtils.setField(value, "createdAt", createdAt.toInstant(java.time.ZoneOffset.UTC));
        return value;
    }

    private ReactPlanTaskCheckpointEntity checkpoint(
            String taskId, long userId, long sessionId, long turnId,
            String state, LocalDateTime updatedAt) {
        ReactPlanTaskCheckpointEntity value = new ReactPlanTaskCheckpointEntity(
                taskId, "b".repeat(64), userId, sessionId, turnId, state, 2,
                "{\"view\":{\"state\":\"" + state + "\"}}", updatedAt.minusSeconds(2));
        ReflectionTestUtils.setField(value, "updatedAt", updatedAt);
        return value;
    }

    private ReactPlanTaskEventEntity event(
            String taskId, long sequence, String content, LocalDateTime occurredAt) {
        return new ReactPlanTaskEventEntity(taskId, sequence, content, occurredAt);
    }
}
