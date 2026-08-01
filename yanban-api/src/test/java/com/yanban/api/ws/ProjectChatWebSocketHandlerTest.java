package com.yanban.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentStopReason;
import com.yanban.api.agent.CompletionStatus;
import com.yanban.api.agent.ProjectAgentRuntimeService;
import com.yanban.api.agent.ProjectEvidenceResponse;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.security.JwtUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class ProjectChatWebSocketHandlerTest {

    @Test
    void emitsAckProcessCanonicalChunkAndDoneEvidenceForRouteBoundProject() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ProjectAgentRuntimeService runtime = mock(ProjectAgentRuntimeService.class);
        ProjectChatWebSocketHandler handler = new ProjectChatWebSocketHandler(objectMapper, runtime);
        when(runtime.sendStreaming(
                eq(7L),
                eq(18L),
                eq(34L),
                any(),
                Mockito.<Consumer<String>>any(),
                Mockito.<Consumer<String>>any()
        )).thenAnswer(invocation -> {
            Consumer<String> canonical = invocation.getArgument(4);
            Consumer<String> process = invocation.getArgument(5);
            process.accept("reading Project files");
            canonical.accept("verified answer");
            return new SendMessageResponse(true, "verified answer", 2, null, null, List.of(), null,
                    List.of(new ProjectEvidenceResponse(
                            "e1", "src/Main.java", "h1", "h1", "tool:call-1", true, true)));
        });
        List<String> outbound = new ArrayList<>();
        WebSocketSession session = session(7L, 18L, outbound);

        handler.handleTextMessage(session, new TextMessage(
                "{\"projectId\":999,\"sessionId\":34,\"content\":\"inspect\",\"clientRequestId\":\"request-1\"}"));

        assertThat(outbound).hasSize(4);
        assertThat(outbound.get(0)).contains("\"type\":\"ack\"")
                .contains("\"clientRequestId\":\"request-1\"");
        assertThat(outbound.get(1)).contains("\"type\":\"process\"")
                .contains("reading Project files");
        assertThat(outbound.get(2)).contains("\"type\":\"chunk\"")
                .contains("verified answer");
        assertThat(outbound.get(3)).contains("\"type\":\"done\"")
                .contains("\"assistantContent\":\"verified answer\"")
                .contains("\"projectEvidence\":[{")
                .contains("\"relativePath\":\"src/Main.java\"")
                .doesNotContain("C:\\\\");
        verify(runtime).sendStreaming(eq(7L), eq(18L), eq(34L), any(),
                Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any());
    }

    @Test
    void rejectsMessageWithoutHandshakeProjectBinding() throws Exception {
        ProjectAgentRuntimeService runtime = mock(ProjectAgentRuntimeService.class);
        ProjectChatWebSocketHandler handler = new ProjectChatWebSocketHandler(new ObjectMapper(), runtime);
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER, new JwtUser(7L, "alice"));
        when(session.getAttributes()).thenReturn(attributes);

        handler.handleTextMessage(session, new TextMessage("{\"sessionId\":34,\"content\":\"inspect\"}"));

        verify(session).close(any(CloseStatus.class));
        verify(runtime, never()).sendStreaming(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotProjectAbsolutePathFromRuntimeError() throws Exception {
        ProjectAgentRuntimeService runtime = mock(ProjectAgentRuntimeService.class);
        ProjectChatWebSocketHandler handler = new ProjectChatWebSocketHandler(new ObjectMapper(), runtime);
        when(runtime.sendStreaming(
                eq(7L), eq(18L), eq(34L), any(),
                Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()
        )).thenReturn(new SendMessageResponse(false, null, 0,
                "Failed to read C:\\private\\secret.txt", null, List.of(), null));
        List<String> outbound = new ArrayList<>();
        WebSocketSession session = session(7L, 18L, outbound);

        handler.handleTextMessage(session, new TextMessage(
                "{\"sessionId\":34,\"content\":\"inspect\",\"clientRequestId\":\"request-2\"}"));

        assertThat(outbound).hasSize(2);
        assertThat(outbound.get(0)).contains("\"type\":\"ack\"");
        assertThat(outbound.get(1)).contains("\"type\":\"error\"")
                .contains("Project conversation failed")
                .doesNotContain("C:\\\\private");
    }

    @Test
    void controlledBudgetPartialUsesDoneTransportWithTypedStatusInsteadOfError() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectAgentRuntimeService runtime = mock(ProjectAgentRuntimeService.class);
        ProjectChatWebSocketHandler handler = new ProjectChatWebSocketHandler(objectMapper, runtime);
        when(runtime.sendStreaming(
                eq(7L), eq(18L), eq(34L), any(),
                Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenAnswer(invocation -> {
                    Consumer<String> canonical = invocation.getArgument(4);
                    canonical.accept("Useful bounded partial answer.");
                    return new SendMessageResponse(false, "Useful bounded partial answer.", 3,
                            "Tool-call budget exceeded: maxToolCalls=6", null,
                            List.of(), null, List.of(new ProjectEvidenceResponse(
                            "partial-e1", "paper.tex", "h1", "h1", "tool:call-1", true, true)), CompletionStatus.PARTIAL,
                            AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED, "PARTIAL");
                });
        List<String> outbound = new ArrayList<>();
        WebSocketSession session = session(7L, 18L, outbound);

        handler.handleTextMessage(session, new TextMessage(
                "{\"sessionId\":34,\"content\":\"inspect broadly\",\"clientRequestId\":\"request-partial\"}"));

        assertThat(outbound).hasSize(3);
        assertThat(outbound.get(2)).contains("\"type\":\"done\"")
                .contains("\"completionStatus\":\"PARTIAL\"")
                .contains("\"stopReason\":\"TOOL_CALL_BUDGET_EXHAUSTED\"")
                .contains("\"outcome\":\"PARTIAL\"")
                .contains("\"relativePath\":\"paper.tex\"")
                .doesNotContain("\"type\":\"error\"");
    }

    @Test
    void partialWithoutCanonicalAnswerRemainsFailClosedAsError() throws Exception {
        ProjectAgentRuntimeService runtime = mock(ProjectAgentRuntimeService.class);
        ProjectChatWebSocketHandler handler = new ProjectChatWebSocketHandler(new ObjectMapper(), runtime);
        when(runtime.sendStreaming(
                eq(7L), eq(18L), eq(34L), any(),
                Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenReturn(new SendMessageResponse(false, null, 3, "Budget exhausted before synthesis", null,
                        List.of(), null, List.of(), CompletionStatus.PARTIAL,
                        AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED, "PARTIAL"));
        List<String> outbound = new ArrayList<>();

        handler.handleTextMessage(session(7L, 18L, outbound), new TextMessage(
                "{\"sessionId\":34,\"content\":\"inspect broadly\",\"clientRequestId\":\"request-empty-partial\"}"));

        assertThat(outbound).hasSize(2);
        assertThat(outbound.get(1)).contains("\"type\":\"error\"")
                .doesNotContain("\"type\":\"done\"");
    }

    @Test
    void runtimeExceptionPartialWithCanonicalTextRemainsFailClosedAsError() throws Exception {
        ProjectAgentRuntimeService runtime = mock(ProjectAgentRuntimeService.class);
        ProjectChatWebSocketHandler handler = new ProjectChatWebSocketHandler(new ObjectMapper(), runtime);
        when(runtime.sendStreaming(
                eq(7L), eq(18L), eq(34L), any(),
                Mockito.<Consumer<String>>any(), Mockito.<Consumer<String>>any()))
                .thenReturn(new SendMessageResponse(false, "Stale text emitted before the exception.", 3,
                        "Runtime execution failed", null, List.of(), null, List.of(), CompletionStatus.PARTIAL,
                        AgentStopReason.RUNTIME_EXCEPTION, "PARTIAL"));
        List<String> outbound = new ArrayList<>();

        handler.handleTextMessage(session(7L, 18L, outbound), new TextMessage(
                "{\"sessionId\":34,\"content\":\"inspect\",\"clientRequestId\":\"request-runtime-partial\"}"));

        assertThat(outbound).hasSize(2);
        assertThat(outbound.get(1)).contains("\"type\":\"error\"")
                .doesNotContain("\"type\":\"done\"");
    }

    private WebSocketSession session(Long userId, Long projectId, List<String> outbound) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        HashMap<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketAuthHandshakeInterceptor.ATTR_JWT_USER, new JwtUser(userId, "alice"));
        attributes.put(ProjectWebSocketHandshakeInterceptor.ATTR_PROJECT_ID, projectId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        Mockito.doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            outbound.add(message.getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }
}
