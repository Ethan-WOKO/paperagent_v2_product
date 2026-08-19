package com.yanban.api.agent.reactplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentSessionTitleGenerator;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ReactPlanTurnIntakeServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentSessionRepository sessions;
    private ReactPlanTurnIntakeTransactions transactions;
    private ReactPlanRuntimeService runtime;
    private AgentSessionTitleGenerator titleGenerator;
    private ReactPlanTurnIntakeService service;

    @BeforeEach
    void setUp() {
        sessions = mock(AgentSessionRepository.class);
        transactions = mock(ReactPlanTurnIntakeTransactions.class);
        runtime = mock(ReactPlanRuntimeService.class);
        titleGenerator = mock(AgentSessionTitleGenerator.class);
        service = new ReactPlanTurnIntakeService(
                json, sessions, transactions, runtime, titleGenerator);
        when(sessions.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(
                new AgentSession(7L, "project", "deepseek", "deepseek-chat",
                        20, true, AgentSessionScope.PROJECT, 88L)));
    }

    @Test
    void createsOneProductTurnAndStartsTheReActTask() {
        when(transactions.find(7L, 11L, requestId())).thenReturn(Optional.empty());
        when(transactions.create(anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(intake("a".repeat(64)));
        when(runtime.submit(anyLong(), anyLong(), any())).thenReturn(accepted(false));
        when(transactions.shouldInitializeTitle(7L, 11L,
                ReactPlanRuntimeService.taskId(7L, 42L))).thenReturn(true);
        when(titleGenerator.generate(any(), anyLong(), any())).thenReturn("检查 Sort 编译");

        JsonNode result = service.start(7L, 11L, request("Compile Sort.java"));

        assertEquals(42L, result.path("turnId").asLong());
        assertEquals(ReactPlanRuntimeService.taskId(7L, 42L),
                result.path("taskId").asText());
        assertFalse(result.path("replayed").asBoolean());
        ArgumentCaptor<ReactPlanTaskRequest> task =
                ArgumentCaptor.forClass(ReactPlanTaskRequest.class);
        verify(runtime).submit(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L), task.capture());
        assertNull(task.getValue().provider());
        assertNull(task.getValue().model());
        verify(transactions).initializeTitleIfStillEligible(
                7L, 11L, ReactPlanRuntimeService.taskId(7L, 42L), "检查 Sort 编译");
    }

    @Test
    void exactReplayReusesTheSameTurnAndTask() {
        ReactPlanSessionTaskRequest request = request("Compile Sort.java");
        ReactPlanTurnIntakeEntity existing = intake(digest(request));
        when(transactions.find(7L, 11L, requestId()))
                .thenReturn(Optional.of(existing));
        when(runtime.submit(anyLong(), anyLong(), any())).thenReturn(accepted(true));

        JsonNode result = service.start(7L, 11L, request);

        assertTrue(result.path("replayed").asBoolean());
        assertEquals(42L, result.path("turnId").asLong());
    }

    @Test
    void sameClientRequestWithDifferentContentConflicts() {
        when(transactions.find(7L, 11L, requestId()))
                .thenReturn(Optional.of(intake("f".repeat(64))));

        assertThrows(ResponseStatusException.class,
                () -> service.start(7L, 11L, request("Different task")));
    }

    private ReactPlanSessionTaskRequest request(String instruction) {
        return new ReactPlanSessionTaskRequest(
                requestId(), instruction, null, null);
    }

    private String digest(ReactPlanSessionTaskRequest request) {
        ObjectNode value = json.createObjectNode();
        value.put("clientRequestId", request.clientRequestId());
        value.put("instruction", request.instruction());
        value.putNull("provider");
        value.putNull("model");
        return ReactPlanCanonicalJson.digest(json, value);
    }

    private ReactPlanTurnIntakeEntity intake(String digest) {
        return new ReactPlanTurnIntakeEntity(
                7L, 11L, requestId(), digest, 42L, 51L,
                ReactPlanRuntimeService.taskId(7L, 42L), LocalDateTime.now());
    }

    private ObjectNode accepted(boolean replayed) {
        ObjectNode response = json.createObjectNode();
        response.put("replayed", replayed);
        response.putObject("task").put("state", "queued");
        return response;
    }

    private static String requestId() {
        return "request.0123456789abcdef";
    }
}
