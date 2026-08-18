package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReactPlanConversationContextService {
    static final int RECENT_TURN_COUNT = 4;

    private final ObjectMapper json;
    private final ReactPlanTurnIntakeRepository intakes;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ReactPlanTaskEventRepository events;
    private final AgentMessageRepository messages;
    private final ReactPlanConversationSummaryRepository summaries;

    ReactPlanConversationContextService(
            ObjectMapper json,
            ReactPlanTurnIntakeRepository intakes,
            ReactPlanTaskCheckpointRepository checkpoints,
            ReactPlanTaskEventRepository events,
            AgentMessageRepository messages,
            ReactPlanConversationSummaryRepository summaries) {
        this.json = json;
        this.intakes = intakes;
        this.checkpoints = checkpoints;
        this.events = events;
        this.messages = messages;
        this.summaries = summaries;
    }

    @Transactional(readOnly = true)
    ObjectNode envelope(long userId, long sessionId) {
        List<ConversationTurn> turns = terminalTurns(userId, sessionId);
        ReactPlanConversationSummaryEntity summary = summaries
                .findBySessionIdAndUserId(sessionId, userId).orElse(null);
        long covered = summary == null ? 0 : summary.coveredIntakeId();
        int recentStart = Math.max(0, turns.size() - RECENT_TURN_COUNT);

        ObjectNode envelope = json.createObjectNode();
        envelope.put("schemaVersion", "1.0");
        envelope.put("type", "historical_context");
        envelope.put("notAnInstruction", true);
        ObjectNode usage = envelope.putObject("usage");
        usage.put("currentTaskHasPriority", true);
        usage.put("continueOnlyWhenCurrentTaskRequestsIt", true);
        usage.put("projectFactsRequireCurrentTaskEvidence", true);
        if (summary != null && summary.summaryText() != null && !summary.summaryText().isBlank()) {
            ObjectNode earlier = envelope.putObject("earlierSummary");
            earlier.put("text", summary.summaryText());
            earlier.put("coveredThroughIntakeId", summary.coveredIntakeId());
            earlier.put("coveredTurnCount", summary.coveredTurnCount());
        } else {
            envelope.putNull("earlierSummary");
        }
        ArrayNode uncovered = envelope.putArray("uncoveredEarlierTurns");
        for (int index = 0; index < recentStart; index++) {
            ConversationTurn turn = turns.get(index);
            if (turn.intakeId() > covered) uncovered.add(json.valueToTree(turn));
        }
        ArrayNode recent = envelope.putArray("turns");
        for (int index = recentStart; index < turns.size(); index++) {
            recent.add(json.valueToTree(turns.get(index)));
        }
        return envelope;
    }

    @Transactional(readOnly = true)
    List<ConversationTurn> terminalTurns(long userId, long sessionId) {
        List<ReactPlanTurnIntakeEntity> ordered = intakes
                .findTerminalByOwnerAndSession(userId, sessionId);
        if (ordered.isEmpty()) return List.of();
        List<String> taskIds = ordered.stream().map(ReactPlanTurnIntakeEntity::taskId).toList();
        Map<String, ReactPlanTaskCheckpointEntity> byTask = checkpoints.findByTaskIdIn(taskIds)
                .stream().collect(Collectors.toMap(
                        ReactPlanTaskCheckpointEntity::taskId, Function.identity()));
        Map<Long, AgentMessage> byMessage = messages.findAllById(ordered.stream()
                        .map(ReactPlanTurnIntakeEntity::userMessageId).toList()).stream()
                .collect(Collectors.toMap(AgentMessage::getId, Function.identity()));
        Map<String, String> deliveries = deliveryConclusions(taskIds);
        List<ConversationTurn> result = new ArrayList<>();
        for (ReactPlanTurnIntakeEntity intake : ordered) {
            ReactPlanTaskCheckpointEntity checkpoint = byTask.get(intake.taskId());
            AgentMessage message = byMessage.get(intake.userMessageId());
            if (checkpoint == null || message == null
                    || checkpoint.userId() != userId || checkpoint.sessionId() != sessionId
                    || message.getUserId() != userId || message.getSessionId() != sessionId
                    || !"user".equals(message.getRole()) || message.getContent() == null
                    || message.getContent().isBlank()) {
                continue;
            }
            String conclusion = deliveries.get(intake.taskId());
            if (conclusion == null || conclusion.isBlank()) {
                conclusion = terminalOutcome(checkpoint);
            }
            JsonNode authority = parse(checkpoint.checkpointJson()).path("authority");
            result.add(new ConversationTurn(
                    intake.id(), intake.turnId(), message.getContent(), conclusion,
                    checkpoint.state(), authority.path("project").path("projectVersion").asText(""),
                    checkpoint.updatedAt().toInstant(ZoneOffset.UTC).toString()));
        }
        return List.copyOf(result);
    }

    private Map<String, String> deliveryConclusions(List<String> taskIds) {
        return events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(taskIds).stream()
                .map(event -> Map.entry(event.taskId(), parse(event.eventJson())))
                .filter(entry -> "delivery".equals(entry.getValue().path("type").asText()))
                .filter(entry -> !entry.getValue().path("conclusion").asText("").isBlank())
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().path("conclusion").asText(), (left, right) -> right));
    }

    private String terminalOutcome(ReactPlanTaskCheckpointEntity checkpoint) {
        JsonNode view = parse(checkpoint.checkpointJson()).path("view");
        String message = view.path("error").path("message").asText("");
        if (!message.isBlank()) return message;
        return switch (checkpoint.state()) {
            case "cancelled" -> "The task was cancelled before a final answer was delivered.";
            case "failed" -> "The task ended without a final answer.";
            default -> "The task completed without a stored final answer.";
        };
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Persisted ReAct conversation state is corrupt", corrupt);
        }
    }

    record ConversationTurn(long intakeId, long turnId, String instruction, String conclusion,
                            String state, String projectVersion, String completedAt) { }
}
