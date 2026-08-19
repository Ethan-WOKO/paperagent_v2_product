package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects the user-visible ReAct conversation without exposing internal event payloads. */
@Service
public class ReactPlanAdminConversationReader {

    private static final java.util.Set<String> TERMINAL = java.util.Set.of(
            "succeeded", "failed", "cancelled");

    private final ObjectMapper json;
    private final AgentMessageRepository messages;
    private final ReactPlanTurnIntakeRepository intakes;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ReactPlanTaskEventRepository events;

    ReactPlanAdminConversationReader(
            ObjectMapper json,
            AgentMessageRepository messages,
            ReactPlanTurnIntakeRepository intakes,
            ReactPlanTaskCheckpointRepository checkpoints,
            ReactPlanTaskEventRepository events) {
        this.json = json;
        this.messages = messages;
        this.intakes = intakes;
        this.checkpoints = checkpoints;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> read(long userId, long sessionId) {
        List<ReactPlanTurnIntakeEntity> tasks = intakes
                .findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId);
        if (tasks.isEmpty()) return List.of();

        Map<Long, AgentMessage> instructions = messages.findAllById(tasks.stream()
                        .map(ReactPlanTurnIntakeEntity::userMessageId).toList()).stream()
                .collect(Collectors.toMap(AgentMessage::getId, Function.identity()));
        List<String> taskIds = tasks.stream().map(ReactPlanTurnIntakeEntity::taskId).toList();
        Map<String, ReactPlanTaskCheckpointEntity> taskStates = checkpoints.findByTaskIdIn(taskIds).stream()
                .collect(Collectors.toMap(ReactPlanTaskCheckpointEntity::taskId, Function.identity()));
        Map<String, Delivery> deliveries = publicDeliveries(taskIds);

        List<ConversationMessage> result = new ArrayList<>();
        for (ReactPlanTurnIntakeEntity task : tasks) {
            AgentMessage instruction = instructions.get(task.userMessageId());
            ReactPlanTaskCheckpointEntity checkpoint = taskStates.get(task.taskId());
            if (!isBound(userId, sessionId, task, instruction, checkpoint)) continue;

            Instant instructionAt = instruction.getCreatedAt() == null
                    ? instant(task.createdAt()) : instruction.getCreatedAt();
            result.add(new ConversationMessage(
                    instruction.getId(), "user", instruction.getContent(), instructionAt));

            Delivery delivery = deliveries.get(task.taskId());
            if (delivery != null) {
                result.add(new ConversationMessage(
                        -task.id(), "assistant", delivery.conclusion(), instant(delivery.occurredAt())));
            } else if (TERMINAL.contains(checkpoint.state())) {
                result.add(new ConversationMessage(
                        -task.id(), "system", terminalMessage(checkpoint.state()), instant(checkpoint.updatedAt())));
            }
        }
        result.sort(Comparator.comparing(ConversationMessage::createdAt)
                .thenComparingLong(ConversationMessage::id));
        return List.copyOf(result);
    }

    private Map<String, Delivery> publicDeliveries(List<String> taskIds) {
        Map<String, Delivery> result = new LinkedHashMap<>();
        for (ReactPlanTaskEventEntity event
                : events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(taskIds)) {
            JsonNode value = parse(event.eventJson());
            if (!"delivery".equals(value.path("type").asText())) continue;
            String conclusion = value.path("conclusion").asText("").strip();
            if (!conclusion.isEmpty()) {
                result.put(event.taskId(), new Delivery(conclusion, event.occurredAt()));
            }
        }
        return Map.copyOf(result);
    }

    private boolean isBound(
            long userId,
            long sessionId,
            ReactPlanTurnIntakeEntity task,
            AgentMessage instruction,
            ReactPlanTaskCheckpointEntity checkpoint) {
        return task.id() > 0 && task.userId() == userId && task.sessionId() == sessionId
                && instruction != null && instruction.getId().equals(task.userMessageId())
                && instruction.getUserId().equals(userId) && instruction.getSessionId().equals(sessionId)
                && "user".equals(instruction.getRole()) && instruction.getContent() != null
                && checkpoint != null && checkpoint.userId() == userId
                && checkpoint.sessionId() == sessionId && checkpoint.turnId() == task.turnId()
                && checkpoint.taskId().equals(task.taskId());
    }

    private String terminalMessage(String state) {
        return switch (state) {
            case "cancelled" -> "任务已取消，没有生成最终回答。";
            case "failed" -> "任务执行失败，没有生成最终回答。";
            default -> "任务已完成，但没有保存最终回答。";
        };
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException corrupt) {
            return json.createObjectNode();
        }
    }

    private Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    public record ConversationMessage(long id, String role, String content, Instant createdAt) { }

    private record Delivery(String conclusion, LocalDateTime occurredAt) { }
}
