package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ReactPlanConversationHistoryServiceTest {
    private static final long USER = 11L;
    private static final long PROJECT = 14L;
    private static final long CURRENT_SESSION = 13L;
    private static final String CURRENT_TASK = "task." + "1".repeat(64);
    private static final String TARGET_TASK = "task." + "2".repeat(64);

    private final ObjectMapper json = new ObjectMapper();
    private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final ReactPlanTurnIntakeRepository intakes = mock(ReactPlanTurnIntakeRepository.class);
    private final ReactPlanTaskCheckpointRepository checkpoints =
            mock(ReactPlanTaskCheckpointRepository.class);
    private final ReactPlanTaskEventRepository events = mock(ReactPlanTaskEventRepository.class);
    private final ReactPlanConversationHistoryService service =
            new ReactPlanConversationHistoryService(
                    json, sessions, messages, intakes, checkpoints, events);
    private final ReactPlanConversationHistoryService.Authority authority =
            new ReactPlanConversationHistoryService.Authority(
                    USER, PROJECT, CURRENT_SESSION, CURRENT_TASK);

    @BeforeEach
    void requesterAuthority() {
        ReactPlanTurnIntakeEntity requester = intake(
                100L, USER, CURRENT_SESSION, CURRENT_TASK, 1000L,
                "current instruction", LocalDateTime.parse("2026-08-19T00:00:00"));
        AgentSession current = session(CURRENT_SESSION, USER, PROJECT);
        when(intakes.findByTaskId(CURRENT_TASK)).thenReturn(Optional.of(requester));
        when(sessions.findByIdAndUserIdAndScopeAndProjectId(
                CURRENT_SESSION, USER, AgentSessionScope.PROJECT, PROJECT))
                .thenReturn(Optional.of(current));
        when(sessions.findByUserIdAndScopeAndProjectIdOrderByUpdatedAtDesc(
                USER, AgentSessionScope.PROJECT, PROJECT)).thenReturn(List.of(current));
    }

    @Test
    void searchesOnlyBoundTerminalInstructionsAndReturnsBoundedOutcome() {
        ReactPlanTurnIntakeEntity target = intake(
                90L, USER, CURRENT_SESSION, TARGET_TASK, 900L,
                "检查 Sort.java 的历史编译结果", LocalDateTime.parse("2026-08-18T10:00:00"));
        AgentMessage instruction = message(
                900L, CURRENT_SESSION, USER, "user", "检查 Sort.java 的历史编译结果");
        ReactPlanTaskCheckpointEntity checkpoint = checkpoint(
                TARGET_TASK, USER, CURRENT_SESSION, target.turnId(), "succeeded",
                LocalDateTime.parse("2026-08-18T10:00:05"));
        when(intakes.findTerminalHistoryCandidates(
                any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(target));
        when(checkpoints.findByTaskIdIn(anyList())).thenReturn(List.of(checkpoint));
        when(messages.findAllById(anyList())).thenReturn(List.of(instruction));
        when(events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(anyList()))
                .thenReturn(List.of(event(TARGET_TASK, 8, delivery(
                        TARGET_TASK, 8, "Sort.java 编译失败，原因是无效 import。"))));

        ObjectNode output = service.search(authority,
                new ReactPlanConversationHistoryService.SearchRequest(
                        "current_session", "sort.java", "succeeded", null, 5));

        assertThat(output.path("resultCount").asInt()).isEqualTo(1);
        assertThat(output.path("items").get(0).path("taskId").asText())
                .isEqualTo(TARGET_TASK);
        assertThat(output.path("items").get(0).path("instruction").path("text").asText())
                .contains("Sort.java");
        assertThat(output.path("items").get(0).path("finalOutcome").path("text").asText())
                .contains("无效 import");
        assertThat(output.toString()).doesNotContain("projectId", "projectVersion", "clientRequestId");
    }

    @Test
    void directLookupDoesNotRevealCrossProjectTaskExistence() {
        long otherSession = 99L;
        ReactPlanTurnIntakeEntity target = intake(
                90L, USER, otherSession, TARGET_TASK, 900L,
                "other project", LocalDateTime.parse("2026-08-18T10:00:00"));
        when(intakes.findByTaskId(TARGET_TASK)).thenReturn(Optional.of(target));
        when(checkpoints.findById(TARGET_TASK)).thenReturn(Optional.of(checkpoint(
                TARGET_TASK, USER, otherSession, target.turnId(), "succeeded",
                LocalDateTime.parse("2026-08-18T10:00:05"))));
        when(messages.findById(900L)).thenReturn(Optional.of(message(
                900L, otherSession, USER, "user", "other project")));

        assertThatThrownBy(() -> service.task(authority, TARGET_TASK))
                .isExactlyInstanceOf(
                        ReactPlanConversationHistoryService.HistoryUnavailableException.class)
                .hasMessage("history unavailable");
    }

    @Test
    void traceProjectsOnlyAllowlistedFieldsAndNeverRawEventPayloads() {
        ReactPlanTurnIntakeEntity target = intake(
                90L, USER, CURRENT_SESSION, TARGET_TASK, 900L,
                "inspect prior trace", LocalDateTime.parse("2026-08-18T10:00:00"));
        AgentMessage instruction = message(
                900L, CURRENT_SESSION, USER, "user", "inspect prior trace");
        ReactPlanTaskCheckpointEntity checkpoint = checkpoint(
                TARGET_TASK, USER, CURRENT_SESSION, target.turnId(), "succeeded",
                LocalDateTime.parse("2026-08-18T10:00:05"));
        when(intakes.findByTaskId(TARGET_TASK)).thenReturn(Optional.of(target));
        when(checkpoints.findById(TARGET_TASK)).thenReturn(Optional.of(checkpoint));
        when(messages.findById(900L)).thenReturn(Optional.of(instruction));
        when(events.findByTaskIdOrderBySequenceNumberAsc(TARGET_TASK)).thenReturn(List.of(
                event(TARGET_TASK, 2, tool(TARGET_TASK, 2)),
                event(TARGET_TASK, 3, messageEvent(TARGET_TASK, 3)),
                event(TARGET_TASK, 4, delivery(TARGET_TASK, 4, "公开最终结论"))));

        ObjectNode output = service.trace(authority, TARGET_TASK, null, 20);
        String serialized = output.toString();

        assertThat(output.path("events").size()).isEqualTo(3);
        assertThat(serialized).contains("search_web", "resultCount", "公开最终结论");
        assertThat(serialized).doesNotContain(
                "SECRET_VALUE", "apiKey", "inputSummary", "outputSummary",
                "hidden reasoning", "raw", "authorization");
    }

    private ReactPlanTurnIntakeEntity intake(
            long id, long userId, long sessionId, String taskId,
            long messageId, String instruction, LocalDateTime createdAt) {
        ReactPlanTurnIntakeEntity value = new ReactPlanTurnIntakeEntity(
                userId, sessionId, "request-" + id, "a".repeat(64), id + 1000,
                messageId, taskId, createdAt);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private AgentMessage message(long id, long sessionId, long userId,
                                 String role, String content) {
        AgentMessage value = new AgentMessage(sessionId, userId, role, content, null, null);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private AgentSession session(long id, long userId, long projectId) {
        AgentSession value = new AgentSession(userId, "session", "deepseek", "deepseek-chat",
                8, true, AgentSessionScope.PROJECT, projectId);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private ReactPlanTaskCheckpointEntity checkpoint(
            String taskId, long userId, long sessionId, long turnId,
            String state, LocalDateTime finishedAt) {
        ObjectNode checkpoint = json.createObjectNode();
        checkpoint.putObject("view").put("state", state).putNull("error");
        ReactPlanTaskCheckpointEntity value = new ReactPlanTaskCheckpointEntity(
                taskId, "b".repeat(64), userId, sessionId, turnId, state, 4,
                checkpoint.toString(), finishedAt.minusSeconds(5));
        ReflectionTestUtils.setField(value, "updatedAt", finishedAt);
        return value;
    }

    private ReactPlanTaskEventEntity event(String taskId, long sequence, ObjectNode event) {
        return new ReactPlanTaskEventEntity(taskId, sequence, event.toString(),
                LocalDateTime.parse("2026-08-18T10:00:04"));
    }

    private ObjectNode delivery(String taskId, long sequence, String conclusion) {
        ObjectNode event = base(taskId, sequence, "delivery");
        event.put("conclusion", conclusion);
        return event;
    }

    private ObjectNode tool(String taskId, long sequence) {
        ObjectNode event = base(taskId, sequence, "tool");
        event.put("name", "registered.invoke");
        event.put("registeredToolName", "search_web");
        event.put("state", "succeeded");
        event.put("inputSummary", "apiKey=SECRET_VALUE; raw=file body");
        event.put("outputSummary", "registeredTool=search_web; success=true; "
                + "resultCount=3; evidenceCount=2; retryable=false; raw=SECRET_VALUE");
        event.put("authorization", "SECRET_VALUE");
        return event;
    }

    private ObjectNode messageEvent(String taskId, long sequence) {
        ObjectNode event = base(taskId, sequence, "message");
        event.put("content", "hidden reasoning SECRET_VALUE");
        return event;
    }

    private ObjectNode base(String taskId, long sequence, String type) {
        ObjectNode event = json.createObjectNode();
        event.put("contractVersion", "1.0");
        event.put("taskId", taskId);
        event.put("sequence", sequence);
        event.put("occurredAt", "2026-08-18T10:00:04Z");
        event.put("type", type);
        return event;
    }
}
