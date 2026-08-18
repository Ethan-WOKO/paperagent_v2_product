package com.yanban.api.agent.reactplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReactPlanConversationContextServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReactPlanTurnIntakeRepository intakes = mock(ReactPlanTurnIntakeRepository.class);
    private final ReactPlanTaskCheckpointRepository checkpoints = mock(ReactPlanTaskCheckpointRepository.class);
    private final ReactPlanTaskEventRepository events = mock(ReactPlanTaskEventRepository.class);
    private final AgentMessageRepository messages = mock(AgentMessageRepository.class);
    private final ReactPlanConversationSummaryRepository summaries = mock(ReactPlanConversationSummaryRepository.class);
    private final ReactPlanConversationContextService service = new ReactPlanConversationContextService(
            json, intakes, checkpoints, events, messages, summaries);

    @Test
    void keepsFourCompleteRecentTurnsAndExposesOnlyUncoveredOlderTurns() {
        List<ReactPlanTurnIntakeEntity> intakeValues = new ArrayList<>();
        List<ReactPlanTaskCheckpointEntity> checkpointValues = new ArrayList<>();
        List<ReactPlanTaskEventEntity> eventValues = new ArrayList<>();
        List<AgentMessage> messageValues = new ArrayList<>();
        LocalDateTime now = LocalDateTime.parse("2026-08-18T00:00:00");
        for (int index = 1; index <= 6; index++) {
            String taskId = "task." + Integer.toHexString(index).repeat(64).substring(0, 64);
            AgentMessage message = new AgentMessage(11L, 7L, "user",
                    "instruction " + index, null, null);
            ReflectionTestUtils.setField(message, "id", (long) index);
            messageValues.add(message);
            ReactPlanTurnIntakeEntity intake = new ReactPlanTurnIntakeEntity(
                    7L, 11L, "request." + index, "a".repeat(64), index,
                    index, taskId, now.plusSeconds(index));
            ReflectionTestUtils.setField(intake, "id", (long) index);
            intakeValues.add(intake);
            String checkpointJson = "{\"authority\":{\"project\":{\"projectVersion\":\""
                    + "b".repeat(64) + "\"}},\"view\":{}}";
            ReactPlanTaskCheckpointEntity checkpoint = new ReactPlanTaskCheckpointEntity(
                    taskId, "a".repeat(64), 7L, 11L, index, "queued", 0,
                    checkpointJson, now.plusSeconds(index));
            checkpoint.update("succeeded", 1, checkpointJson, now.plusSeconds(index));
            checkpointValues.add(checkpoint);
            eventValues.add(new ReactPlanTaskEventEntity(taskId, 1,
                    "{\"type\":\"delivery\",\"conclusion\":\"conclusion "
                            + index + "\"}", now.plusSeconds(index)));
        }
        ReactPlanConversationSummaryEntity summary = new ReactPlanConversationSummaryEntity(
                11L, 7L, 6L, now);
        summary.succeed("summary of turn 1", 1L, 1, "test", "model", false, now);
        when(intakes.findTerminalByOwnerAndSession(7L, 11L)).thenReturn(intakeValues);
        when(checkpoints.findByTaskIdIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(checkpointValues);
        when(messages.findAllById(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(messageValues);
        when(events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(
                org.mockito.ArgumentMatchers.anyCollection())).thenReturn(eventValues);
        when(summaries.findBySessionIdAndUserId(11L, 7L)).thenReturn(Optional.of(summary));

        JsonNode envelope = service.envelope(7L, 11L);

        assertThat(envelope.path("earlierSummary").path("text").asText())
                .isEqualTo("summary of turn 1");
        assertThat(envelope.path("uncoveredEarlierTurns")).hasSize(1);
        assertThat(envelope.path("uncoveredEarlierTurns").get(0).path("instruction").asText())
                .isEqualTo("instruction 2");
        assertThat(envelope.path("turns")).hasSize(4);
        assertThat(envelope.path("turns").get(0).path("instruction").asText())
                .isEqualTo("instruction 3");
        assertThat(envelope.path("turns").get(3).path("conclusion").asText())
                .isEqualTo("conclusion 6");
    }
}
