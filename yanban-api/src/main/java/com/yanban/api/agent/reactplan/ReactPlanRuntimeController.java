package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/react-agent/turns/{turnId}/tasks")
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
final class ReactPlanRuntimeController {
    private final ReactPlanRuntimeService runtime;

    ReactPlanRuntimeController(ReactPlanRuntimeService runtime) {
        this.runtime = runtime;
    }

    @PostMapping
    ResponseEntity<JsonNode> submit(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable long turnId,
            @RequestBody ReactPlanTaskRequest request) {
        return ResponseEntity.accepted().body(runtime.submit(userId, turnId, request));
    }

    @GetMapping("/{taskId}")
    JsonNode task(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable long turnId,
            @PathVariable String taskId) {
        return runtime.task(userId, turnId, taskId);
    }

    @PostMapping("/{taskId}/cancel")
    ResponseEntity<JsonNode> cancel(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable long turnId,
            @PathVariable String taskId,
            @RequestBody CancelRequest request) {
        return ResponseEntity.accepted().body(
                runtime.cancel(userId, turnId, taskId, request.clientRequestId()));
    }

    @PostMapping("/{taskId}/answer")
    ResponseEntity<JsonNode> answer(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable long turnId,
            @PathVariable String taskId,
            @RequestBody ReactPlanAnswerRequest request) {
        return ResponseEntity.accepted().body(runtime.answer(userId, turnId, taskId, request));
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> events(
            @AuthenticationPrincipal(expression = "id") Long userId,
            @PathVariable long turnId,
            @PathVariable String taskId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        long after = lastEventId == null ? 0 : lastEventId;
        InputStream source = runtime.events(userId, turnId, taskId, after);
        StreamingResponseBody body = output -> {
            try (source) { ReactPlanEventStreamCopier.copyAndFlush(source, output); }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    record CancelRequest(String clientRequestId) { }
}
