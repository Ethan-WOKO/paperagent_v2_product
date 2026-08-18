package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineObservationReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReactPlanObservabilityServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReactPlanTaskCheckpointRepository checkpoints = mock(ReactPlanTaskCheckpointRepository.class);
    private final ReactPlanTaskEventRepository events = mock(ReactPlanTaskEventRepository.class);
    private final AgentEngineObservationReader engine = mock(AgentEngineObservationReader.class);
    private final ReactPlanObservabilityService service = new ReactPlanObservabilityService(
            json, checkpoints, events, engine);

    @Test
    void rebuildsBoundedTraceWithoutRawPayloadsAndWithoutDuplicateSpans() {
        String taskId = "task." + "a".repeat(64);
        LocalDateTime started = LocalDateTime.parse("2026-08-18T01:00:00");
        ReactPlanTaskCheckpointEntity checkpoint = new ReactPlanTaskCheckpointEntity(
                taskId, "b".repeat(64), 7, 8, 9, "succeeded", 4,
                "{\"view\":{\"error\":null}}", started);
        checkpoint.update("succeeded", 4, "{\"view\":{\"error\":null}}", started.plusSeconds(5));
        when(checkpoints.findById(taskId)).thenReturn(Optional.of(checkpoint));
        when(events.findByTaskIdOrderBySequenceNumberAsc(taskId)).thenReturn(List.of(
                event(taskId, 1, "2026-08-18T01:00:01Z", "requested"),
                event(taskId, 2, "2026-08-18T01:00:03Z", "succeeded")));
        when(engine.models(taskId)).thenReturn(List.of(new AgentEngineObservationReader.ModelFact(
                "model.x", "glm", "glm-5.2", "SUCCEEDED",
                "2026-08-18T01:00:00Z", "2026-08-18T01:00:01Z",
                120, 40, 10, 5, 1, null)));

        Map<String, Object> trace = service.trace(taskId);
        @SuppressWarnings("unchecked") List<Map<String, Object>> spans =
                (List<Map<String, Object>>) trace.get("spans");
        @SuppressWarnings("unchecked") Map<String, Object> summary =
                (Map<String, Object>) trace.get("summary");
        assertThat(spans).hasSize(3);
        assertThat(spans).extracting(value -> value.get("kind"))
                .containsExactlyInAnyOrder("TASK", "MODEL", "TOOL");
        assertThat(summary).containsEntry("totalDurationMillis", 5000L)
                .containsEntry("firstObservableMillis", 3000L)
                .containsEntry("totalTokens", 15)
                .containsEntry("replayCount", 1);
        assertThat(json.valueToTree(trace).toString()).doesNotContain("secret-file-body");
        assertThat(trace.get("traceId")).isEqualTo(ReactPlanObservabilityService.traceId(taskId));
    }

    @Test
    void adminAggregationReportsUsageButNeverInventsCost() {
        String taskId = "task." + "c".repeat(64);
        LocalDateTime started = LocalDateTime.parse("2026-08-18T01:00:00");
        ReactPlanTaskCheckpointEntity checkpoint = new ReactPlanTaskCheckpointEntity(
                taskId, "d".repeat(64), 42, 8, 9, "failed", 0,
                "{\"view\":{\"error\":{\"code\":\"MODEL_PROVIDER_FAILED\"}}}", started);
        checkpoint.update("failed", 0,
                "{\"view\":{\"error\":{\"code\":\"MODEL_PROVIDER_FAILED\"}}}", started.plusSeconds(2));
        when(checkpoints.findAllByOrderByUpdatedAtAsc()).thenReturn(List.of(checkpoint));
        when(events.findByTaskIdOrderBySequenceNumberAsc(taskId)).thenReturn(List.of());
        when(engine.models(taskId)).thenReturn(List.of());

        Map<String, Object> result = service.adminSummary();
        assertThat(json.valueToTree(result).path("users").get(0).path("userId").asLong()).isEqualTo(42);
        assertThat(json.valueToTree(result).path("users").get(0).path("failureRate").asDouble()).isEqualTo(1d);
        assertThat(json.valueToTree(result).path("cost").path("available").asBoolean()).isFalse();
    }

    private ReactPlanTaskEventEntity event(String taskId, long sequence, String occurredAt, String state) {
        String value = "{\"contractVersion\":\"1.0\",\"taskId\":\"" + taskId
                + "\",\"sequence\":" + sequence + ",\"occurredAt\":\"" + occurredAt
                + "\",\"type\":\"tool\",\"callId\":\"call.x\",\"name\":\"project.read\","
                + "\"state\":\"" + state + "\",\"inputSummary\":\"path=Sort.java\","
                + "\"outputSummary\":" + ("requested".equals(state) ? "null" : "\"read ok\"")
                + ",\"receiptRef\":null}";
        return new ReactPlanTaskEventEntity(taskId, sequence, value,
                java.time.Instant.parse(occurredAt).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());
    }
}
