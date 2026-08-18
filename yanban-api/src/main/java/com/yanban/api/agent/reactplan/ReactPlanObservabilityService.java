package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineObservationReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.engine.gateway", name = "enabled", havingValue = "true")
class ReactPlanObservabilityService {
    private static final Logger log = LoggerFactory.getLogger(ReactPlanObservabilityService.class);
    private final ObjectMapper json;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ReactPlanTaskEventRepository events;
    private final AgentEngineObservationReader engineFacts;

    ReactPlanObservabilityService(ObjectMapper json,
                                  ReactPlanTaskCheckpointRepository checkpoints,
                                  ReactPlanTaskEventRepository events,
                                  AgentEngineObservationReader engineFacts) {
        this.json = json;
        this.checkpoints = checkpoints;
        this.events = events;
        this.engineFacts = engineFacts;
    }

    @Transactional(readOnly = true)
    Map<String, Object> trace(String taskId) {
        ReactPlanTaskCheckpointEntity checkpoint = checkpoints.findById(taskId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "ReAct task not found"));
        return build(checkpoint);
    }

    @Transactional(readOnly = true)
    Map<String, Object> adminSummary() {
        Map<Long, MutableAggregate> aggregates = new LinkedHashMap<>();
        for (ReactPlanTaskCheckpointEntity checkpoint : checkpoints.findAllByOrderByUpdatedAtAsc()) {
            Map<String, Object> trace = build(checkpoint);
            @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) trace.get("summary");
            aggregates.computeIfAbsent(checkpoint.userId(), ignored -> new MutableAggregate())
                    .add(summary, checkpoint.state());
        }
        List<Map<String, Object>> users = aggregates.entrySet().stream().map(entry ->
                entry.getValue().view(entry.getKey())).toList();
        return Map.of("contractVersion", "1.0", "users", users,
                "cost", Map.of("available", false, "reason", "MODEL_PRICING_NOT_CONFIGURED"));
    }

    private Map<String, Object> build(ReactPlanTaskCheckpointEntity checkpoint) {
        String taskId = checkpoint.taskId();
        String traceId = traceId(taskId);
        JsonNode stored = read(checkpoint.checkpointJson());
        List<JsonNode> taskEvents = events.findByTaskIdOrderBySequenceNumberAsc(taskId).stream()
                .map(value -> read(value.eventJson())).toList();
        List<AgentEngineObservationReader.ModelFact> models = engineFacts.models(taskId);
        List<Map<String, Object>> spans = new ArrayList<>();
        Instant started = checkpoint.createdAt().toInstant(ZoneOffset.UTC);
        Instant finished = terminal(checkpoint.state())
                ? checkpoint.updatedAt().toInstant(ZoneOffset.UTC) : null;
        spans.add(span(traceId + ".task", null, "TASK", "react-agent", started, finished,
                checkpoint.state(), errorCode(stored), 0, 0, 0, 0, 0, false, null, null));
        for (AgentEngineObservationReader.ModelFact model : models) {
            spans.add(span(model.callId(), traceId + ".task", "MODEL", "model.complete",
                    Instant.parse(model.startedAt()), Instant.parse(model.finishedAt()),
                    model.state(), model.errorCode(), model.requestBytes(), model.responseBytes(),
                    model.promptTokens(), model.completionTokens(), model.replayCount(),
                    model.replayCount() > 0, model.provider(), model.model()));
        }
        appendToolSpans(spans, taskEvents, traceId);
        spans.sort(Comparator.comparing(value -> String.valueOf(value.get("startedAt"))));

        long firstResultMillis = firstObservableMillis(taskEvents, started);
        long modelDuration = spans.stream().filter(value -> "MODEL".equals(value.get("kind")))
                .mapToLong(value -> ((Number) value.get("durationMillis")).longValue()).sum();
        long toolDuration = spans.stream().filter(value -> "TOOL".equals(value.get("kind")))
                .mapToLong(value -> ((Number) value.get("durationMillis")).longValue()).sum();
        long sandboxDuration = spans.stream().filter(value -> "SANDBOX".equals(value.get("kind")))
                .mapToLong(value -> ((Number) value.get("durationMillis")).longValue()).sum();
        int promptTokens = models.stream().mapToInt(AgentEngineObservationReader.ModelFact::promptTokens).sum();
        int completionTokens = models.stream().mapToInt(AgentEngineObservationReader.ModelFact::completionTokens).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("state", checkpoint.state());
        summary.put("startedAt", started.toString());
        summary.put("finishedAt", finished == null ? null : finished.toString());
        summary.put("totalDurationMillis", duration(started, finished == null ? Instant.now() : finished));
        summary.put("firstObservableMillis", firstResultMillis);
        summary.put("modelCalls", models.size());
        summary.put("toolCalls", countKind(spans, "TOOL") + countKind(spans, "SANDBOX"));
        summary.put("sandboxCalls", countKind(spans, "SANDBOX"));
        summary.put("modelDurationMillis", modelDuration);
        summary.put("toolDurationMillis", toolDuration);
        summary.put("sandboxDurationMillis", sandboxDuration);
        summary.put("promptTokens", promptTokens);
        summary.put("completionTokens", completionTokens);
        summary.put("totalTokens", promptTokens + completionTokens);
        summary.put("failureCount", spans.stream().filter(ReactPlanObservabilityService::failed).count());
        summary.put("replayCount", models.stream().mapToInt(AgentEngineObservationReader.ModelFact::replayCount).sum());
        summary.put("terminalErrorCode", errorCode(stored));
        log.info("reactplan_trace taskId={} traceId={} phase=summary state={} modelCalls={} toolCalls={} totalDurationMillis={}",
                taskId, traceId, checkpoint.state(), summary.get("modelCalls"), summary.get("toolCalls"),
                summary.get("totalDurationMillis"));
        return Map.of("contractVersion", "1.0", "taskId", taskId,
                "traceId", traceId, "summary", summary, "spans", spans);
    }

    private void appendToolSpans(List<Map<String, Object>> spans, List<JsonNode> values, String traceId) {
        Map<String, JsonNode> starts = new LinkedHashMap<>();
        for (JsonNode event : values) {
            if (!"tool".equals(event.path("type").asText())) continue;
            String callId = event.path("callId").asText();
            if ("requested".equals(event.path("state").asText())) {
                starts.putIfAbsent(callId, event);
                continue;
            }
            if (!List.of("succeeded", "failed", "cancelled").contains(event.path("state").asText())) continue;
            JsonNode start = starts.remove(callId);
            if (start == null) start = event;
            String name = event.path("registeredToolName").asText(event.path("name").asText());
            String kind = "sandbox.execute".equals(event.path("name").asText()) ? "SANDBOX" : "TOOL";
            spans.add(span(callId, traceId + ".task", kind, name,
                    Instant.parse(start.path("occurredAt").asText()),
                    Instant.parse(event.path("occurredAt").asText()), event.path("state").asText(),
                    null, bytes(start.path("inputSummary").asText()),
                    bytes(event.path("outputSummary").asText()), 0, 0, 0, false, null, null));
        }
        for (JsonNode start : starts.values()) {
            String name = start.path("registeredToolName").asText(start.path("name").asText());
            String kind = "sandbox.execute".equals(start.path("name").asText()) ? "SANDBOX" : "TOOL";
            spans.add(span(start.path("callId").asText(), traceId + ".task", kind, name,
                    Instant.parse(start.path("occurredAt").asText()), null, "RUNNING", null,
                    bytes(start.path("inputSummary").asText()), 0, 0, 0, 0, false, null, null));
        }
    }

    private static Map<String, Object> span(String spanId, String parentSpanId, String kind, String name,
                                             Instant start, Instant finish, String outcome, String errorCode,
                                             long requestBytes, long resultBytes, int promptTokens,
                                             int completionTokens, int retryCount, boolean replayed,
                                             String provider, String model) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("spanId", spanId); value.put("parentSpanId", parentSpanId);
        value.put("kind", kind); value.put("name", name);
        value.put("startedAt", start.toString()); value.put("finishedAt", finish == null ? null : finish.toString());
        value.put("durationMillis", duration(start, finish == null ? Instant.now() : finish));
        value.put("outcome", outcome); value.put("errorCode", errorCode);
        value.put("requestBytes", requestBytes); value.put("resultBytes", resultBytes);
        value.put("promptTokens", promptTokens); value.put("completionTokens", completionTokens);
        value.put("retryCount", retryCount); value.put("replayed", replayed);
        value.put("provider", provider); value.put("model", model);
        return value;
    }

    private long firstObservableMillis(List<JsonNode> values, Instant started) {
        return values.stream().filter(event -> switch (event.path("type").asText()) {
                    case "delivery", "question", "message" -> true;
                    case "tool" -> List.of("succeeded", "failed", "cancelled")
                            .contains(event.path("state").asText());
                    default -> false;
                }).map(event -> Instant.parse(event.path("occurredAt").asText()))
                .min(Comparator.naturalOrder()).map(value -> duration(started, value)).orElse(-1L);
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (Exception corrupt) { throw new IllegalStateException("Persisted ReAct observation is corrupt", corrupt); }
    }
    private static String errorCode(JsonNode checkpoint) {
        String value = checkpoint.path("view").path("error").path("code").asText("");
        return value.isBlank() ? null : value;
    }
    private static boolean terminal(String state) { return List.of("succeeded", "failed", "cancelled").contains(state); }
    private static boolean failed(Map<String, Object> span) {
        return List.of("FAILED", "failed", "cancelled", "CANCELLED", "SYSTEM_ERROR", "TIMED_OUT")
                .contains(String.valueOf(span.get("outcome")));
    }
    private static long countKind(List<Map<String, Object>> spans, String kind) {
        return spans.stream().filter(value -> kind.equals(value.get("kind"))).count();
    }
    private static long bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static long duration(Instant start, Instant finish) { return Math.max(0, Duration.between(start, finish).toMillis()); }
    static String traceId(String taskId) {
        return ReactPlanTraceIds.forTask(taskId);
    }

    private static final class MutableAggregate {
        long tasks; long failures; long duration; long modelCalls; long toolCalls; long tokens;
        void add(Map<String, Object> summary, String state) {
            tasks++; if ("failed".equals(state)) failures++;
            duration += number(summary, "totalDurationMillis"); modelCalls += number(summary, "modelCalls");
            toolCalls += number(summary, "toolCalls"); tokens += number(summary, "totalTokens");
        }
        Map<String, Object> view(long userId) {
            return Map.of("userId", userId, "taskCount", tasks, "failedTaskCount", failures,
                    "failureRate", tasks == 0 ? 0d : (double) failures / tasks,
                    "averageDurationMillis", tasks == 0 ? 0L : duration / tasks,
                    "modelCalls", modelCalls, "toolCalls", toolCalls, "totalTokens", tokens);
        }
        private static long number(Map<String, Object> value, String key) { return ((Number) value.get(key)).longValue(); }
    }
}
