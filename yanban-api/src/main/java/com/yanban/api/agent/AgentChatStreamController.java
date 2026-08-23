package com.yanban.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.security.JwtUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Authenticated chat transport: the HTTP request carries the command and the
 * response is a one-way SSE stream. A repeated clientRequestId joins the
 * existing in-process execution through AgentRequestDedupService.
 */
@RestController
@RequestMapping("/api/v1/agent/sessions")
public class AgentChatStreamController {

    private static final String DEFAULT_ERROR = "对话处理失败";

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    public AgentChatStreamController(AgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamMessage(
            @AuthenticationPrincipal JwtUser currentUser,
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {
        if (!StringUtils.hasText(request.clientRequestId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "clientRequestId is required for streaming chat");
        }
        StreamingResponseBody body = output -> execute(
                output, currentUser.id(), sessionId, request);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    private void execute(OutputStream output,
                         Long userId,
                         Long sessionId,
                         SendMessageRequest request) {
        EventWriter writer = new EventWriter(output, objectMapper);
        writer.send(ChatStreamEvent.ack(sessionId, request.clientRequestId()));
        try {
            SendMessageResponse response = agentService.sendMessageStreaming(
                    userId,
                    sessionId,
                    request,
                    token -> {
                        if (StringUtils.hasText(token)) {
                            writer.send(ChatStreamEvent.chunk(
                                    sessionId, token, request.clientRequestId()));
                        }
                    },
                    process -> {
                        if (StringUtils.hasText(process)) {
                            writer.send(ChatStreamEvent.process(
                                    sessionId, process, request.clientRequestId()));
                        }
                    });
            if (response.debug() != null) {
                writer.send(ChatStreamEvent.debug(
                        sessionId, response.debug(), request.clientRequestId()));
            }
            if (response.success()) {
                writer.send(ChatStreamEvent.done(
                        sessionId, response, request.clientRequestId(),
                        StringUtils.hasText(request.skillId())
                                ? "skill_langchain4j" : "langchain4j"));
            } else {
                writer.send(ChatStreamEvent.error(sessionId,
                        StringUtils.hasText(response.errorMessage())
                                ? response.errorMessage() : DEFAULT_ERROR,
                        request.clientRequestId()));
            }
        } catch (RuntimeException ex) {
            writer.send(ChatStreamEvent.error(
                    sessionId, errorMessage(ex), request.clientRequestId()));
        }
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return DEFAULT_ERROR;
    }

    private static final class EventWriter {
        private final OutputStream output;
        private final ObjectMapper objectMapper;
        private final AtomicBoolean connected = new AtomicBoolean(true);

        private EventWriter(OutputStream output, ObjectMapper objectMapper) {
            this.output = output;
            this.objectMapper = objectMapper;
        }

        private synchronized void send(ChatStreamEvent event) {
            if (!connected.get()) {
                return;
            }
            try {
                output.write(("event: " + event.type() + "\n").getBytes(StandardCharsets.UTF_8));
                output.write("data: ".getBytes(StandardCharsets.UTF_8));
                // ObjectMapper#writeValue(OutputStream, ...) closes its target by
                // default. A servlet response stream is owned by Spring and must
                // remain open for all subsequent SSE events.
                output.write(objectMapper.writeValueAsBytes(event));
                output.write("\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException | RuntimeException disconnectedClient) {
                // Transport loss must not abort an accepted model execution. A
                // retry with the same clientRequestId waits for/replays its result.
                connected.set(false);
            }
        }
    }
}
