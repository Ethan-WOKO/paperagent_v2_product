package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.security.JwtUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentChatStreamControllerTest {

    private final AgentService agentService = mock(AgentService.class);
    private final AgentChatStreamController controller =
            new AgentChatStreamController(agentService, new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void streamsAckProgressChunksAndTerminalResponseAsSse() throws Exception {
        SendMessageRequest request = new SendMessageRequest(
                "hello", false, null, "request-1", null);
        SendMessageResponse response = mock(SendMessageResponse.class);
        when(response.success()).thenReturn(true);
        when(response.assistantContent()).thenReturn("Hello world");
        when(response.navigationUrl()).thenReturn("/next");
        when(agentService.sendMessageStreaming(
                eq(7L), eq(9L), eq(request), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Consumer<String> chunks = invocation.getArgument(3);
                    Consumer<String> progress = invocation.getArgument(4);
                    progress.accept("thinking");
                    chunks.accept("Hello ");
                    chunks.accept("world");
                    return response;
                });

        var entity = controller.streamMessage(
                new JwtUser(7L, "owner"), 9L, request);
        CloseDetectingOutputStream output = new CloseDetectingOutputStream();
        entity.getBody().writeTo(output);
        String stream = output.content();

        assertThat(entity.getHeaders().getContentType().toString())
                .isEqualTo("text/event-stream");
        assertThat(entity.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(output.closed()).isFalse();
        assertThat(stream).containsSubsequence(
                "event: ack", "\"clientRequestId\":\"request-1\"",
                "event: process", "\"content\":\"thinking\"",
                "event: chunk", "\"content\":\"Hello \"",
                "event: chunk", "\"content\":\"world\"",
                "event: done", "\"assistantContent\":\"Hello world\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesWhitespaceOnlyChunksInSsePayloads() throws Exception {
        List<String> deltas = List.of("## 标题", "\n\n", "1.", " ", "**步骤**", "\n",
                "```java", "\n", "    ", "run();", "\n", "\t", "finish();", "\n", "```");
        SendMessageRequest request = new SendMessageRequest(
                "explain", false, null, "request-whitespace", null);
        SendMessageResponse response = mock(SendMessageResponse.class);
        when(response.success()).thenReturn(true);
        when(response.assistantContent()).thenReturn(String.join("", deltas));
        when(agentService.sendMessageStreaming(
                eq(7L), eq(9L), eq(request), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Consumer<String> chunks = invocation.getArgument(3);
                    chunks.accept(null);
                    chunks.accept("");
                    deltas.forEach(chunks);
                    return response;
                });
        CloseDetectingOutputStream output = new CloseDetectingOutputStream();

        controller.streamMessage(new JwtUser(7L, "owner"), 9L, request).getBody().writeTo(output);

        List<String> received = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        String finalContent = null;
        for (String line : output.content().lines().filter(value -> value.startsWith("data:")).toList()) {
            var event = mapper.readTree(line.substring(5).stripLeading());
            if (event.path("type").asText().equals("chunk")) {
                received.add(event.path("content").asText());
            } else if (event.path("type").asText().equals("done")) {
                finalContent = event.path("assistantContent").asText();
            }
        }
        assertThat(received).containsExactlyElementsOf(deltas);
        assertThat(String.join("", received)).isEqualTo(finalContent);
        assertThat(output.closed()).isFalse();
    }

    @Test
    void requiresIdempotencyKeyForStreamingSubmission() {
        SendMessageRequest request = new SendMessageRequest(
                "hello", false, null, null, null);

        assertThatThrownBy(() -> controller.streamMessage(
                new JwtUser(7L, "owner"), 9L, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).contains("clientRequestId");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void disconnectedClientDoesNotAbortAcceptedAgentExecution() throws Exception {
        SendMessageRequest request = new SendMessageRequest(
                "hello", false, null, "request-2", null);
        SendMessageResponse response = mock(SendMessageResponse.class);
        when(response.success()).thenReturn(true);
        when(agentService.sendMessageStreaming(
                eq(7L), eq(9L), eq(request), any(Consumer.class), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    invocation.<Consumer<String>>getArgument(3).accept("still running");
                    return response;
                });
        OutputStream disconnected = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("client disconnected");
            }
        };

        controller.streamMessage(new JwtUser(7L, "owner"), 9L, request)
                .getBody().writeTo(disconnected);

        verify(agentService).sendMessageStreaming(
                eq(7L), eq(9L), eq(request), any(Consumer.class), any(Consumer.class));
    }

    private static final class CloseDetectingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public void write(int value) throws IOException {
            ensureOpen();
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            delegate.write(bytes, offset, length);
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("write after close");
            }
        }

        private boolean closed() {
            return closed;
        }

        private String content() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }
}
