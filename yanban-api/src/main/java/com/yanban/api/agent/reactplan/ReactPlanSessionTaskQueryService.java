package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
class ReactPlanSessionTaskQueryService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_EVENTS_PER_TASK = 200;
    private static final java.util.Set<String> TERMINAL = java.util.Set.of(
            "succeeded", "failed", "cancelled");

    private final ObjectMapper json;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final ReactPlanTurnIntakeRepository intakes;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ReactPlanTaskEventRepository events;

    ReactPlanSessionTaskQueryService(
            ObjectMapper json,
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            ReactPlanTurnIntakeRepository intakes,
            ReactPlanTaskCheckpointRepository checkpoints,
            ReactPlanTaskEventRepository events) {
        this.json = json;
        this.sessions = sessions;
        this.messages = messages;
        this.intakes = intakes;
        this.checkpoints = checkpoints;
        this.events = events;
    }

    @Transactional(readOnly = true)
    SessionTaskPage list(
            long userId, long sessionId, boolean includeEvents, String cursor, int limit) {
        requireProjectSession(userId, sessionId);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ReAct session task page limit must be between 1 and 50");
        }
        Long beforeId = parseCursor(cursor);
        PageRequest page = PageRequest.of(0, limit + 1);
        List<ReactPlanTurnIntakeEntity> newest = beforeId == null
                ? intakes.findByUserIdAndSessionIdOrderByIdDesc(userId, sessionId, page)
                : intakes.findByUserIdAndSessionIdAndIdLessThanOrderByIdDesc(
                        userId, sessionId, beforeId, page);
        boolean hasMore = newest.size() > limit;
        List<ReactPlanTurnIntakeEntity> selected = new ArrayList<>(
                newest.subList(0, Math.min(limit, newest.size())));
        String nextCursor = hasMore && !selected.isEmpty()
                ? cursor(selected.get(selected.size() - 1).id()) : null;
        List<ReactPlanTurnIntakeEntity> ordered = new ArrayList<>(selected);
        Collections.reverse(ordered);
        if (ordered.isEmpty()) return new SessionTaskPage("1.0", List.of(), null, false);
        List<String> taskIds = ordered.stream().map(ReactPlanTurnIntakeEntity::taskId).toList();
        Map<String, ReactPlanTaskCheckpointEntity> taskCheckpoints = checkpoints
                .findByTaskIdIn(taskIds).stream().collect(Collectors.toMap(
                        ReactPlanTaskCheckpointEntity::taskId, Function.identity()));
        Map<Long, AgentMessage> taskMessages = messages.findAllById(ordered.stream()
                        .map(ReactPlanTurnIntakeEntity::userMessageId).toList()).stream()
                .collect(Collectors.toMap(AgentMessage::getId, Function.identity()));
        Map<String, List<JsonNode>> taskEvents = includeEvents
                ? readEvents(taskIds) : Map.of();
        List<SessionTask> result = new ArrayList<>();
        for (ReactPlanTurnIntakeEntity intake : ordered) {
            ReactPlanTaskCheckpointEntity checkpoint = taskCheckpoints.get(intake.taskId());
            AgentMessage message = taskMessages.get(intake.userMessageId());
            if (checkpoint == null || message == null) continue;
            requireBoundFacts(userId, sessionId, intake, checkpoint, message);
            JsonNode view = parse(checkpoint.checkpointJson()).path("view");
            if (!view.isObject()) throw corrupt();
            Instant startedAt = intake.createdAt().toInstant(ZoneOffset.UTC);
            Instant finishedAt = TERMINAL.contains(checkpoint.state())
                    ? checkpoint.updatedAt().toInstant(ZoneOffset.UTC) : null;
            result.add(new SessionTask(
                    "1.0", intake.clientRequestId(), message.getContent(), intake.turnId(),
                    intake.taskId(), view.deepCopy(),
                    includeEvents ? taskEvents.getOrDefault(intake.taskId(), List.of()) : null,
                    startedAt, finishedAt));
        }
        return new SessionTaskPage("1.0", List.copyOf(result), nextCursor, hasMore);
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (!cursor.matches("intake\\.[1-9][0-9]*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ReAct session task cursor is invalid");
        }
        try {
            return Long.parseLong(cursor.substring("intake.".length()));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ReAct session task cursor is invalid");
        }
    }

    private String cursor(long intakeId) {
        return "intake." + intakeId;
    }

    private Map<String, List<JsonNode>> readEvents(List<String> taskIds) {
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (ReactPlanTaskEventEntity event
                : events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(taskIds)) {
            grouped.computeIfAbsent(event.taskId(), ignored -> new ArrayList<>())
                    .add(parse(event.eventJson()));
        }
        Map<String, List<JsonNode>> bounded = new HashMap<>();
        grouped.forEach((taskId, values) -> bounded.put(taskId, List.copyOf(
                values.subList(Math.max(0, values.size() - MAX_EVENTS_PER_TASK), values.size()))));
        return bounded;
    }

    private void requireProjectSession(long userId, long sessionId) {
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Agent session not found"));
        if (session.getScope() != AgentSessionScope.PROJECT || session.getProjectId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ReAct tasks require a Project-scoped session");
        }
    }

    private void requireBoundFacts(
            long userId, long sessionId,
            ReactPlanTurnIntakeEntity intake,
            ReactPlanTaskCheckpointEntity checkpoint,
            AgentMessage message) {
        if (intake.userId() != userId || intake.sessionId() != sessionId
                || checkpoint.userId() != userId || checkpoint.sessionId() != sessionId
                || checkpoint.turnId() != intake.turnId()
                || !checkpoint.taskId().equals(intake.taskId())
                || !message.getId().equals(intake.userMessageId())
                || !message.getUserId().equals(userId)
                || !message.getSessionId().equals(sessionId)
                || !"user".equals(message.getRole())
                || message.getContent() == null || message.getContent().isBlank()) {
            throw corrupt();
        }
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException failure) { throw corrupt(); }
    }

    private static IllegalStateException corrupt() {
        return new IllegalStateException("Persisted ReAct session task index is corrupt");
    }

    record SessionTask(
            String contractVersion,
            String clientRequestId,
            String instruction,
            long turnId,
            String taskId,
            JsonNode task,
            List<JsonNode> events,
            Instant startedAt,
            Instant finishedAt) { }

    record SessionTaskPage(
            String contractVersion,
            List<SessionTask> items,
            String nextCursor,
            boolean hasMore) { }
}
