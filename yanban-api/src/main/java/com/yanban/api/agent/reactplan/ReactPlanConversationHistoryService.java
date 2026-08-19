package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReactPlanConversationHistoryService {
    static final int MAX_SEARCH_LIMIT = 10;
    static final int MAX_TRACE_LIMIT = 50;
    private static final int MAX_SEARCH_SCAN = 100;
    private static final int SEARCH_INSTRUCTION_CHARS = 600;
    private static final int SEARCH_OUTCOME_CHARS = 800;
    private static final int DETAIL_INSTRUCTION_CHARS = 4_000;
    private static final int DETAIL_OUTCOME_CHARS = 8_000;
    private static final int TRACE_DELIVERY_CHARS = 1_000;
    private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "cancelled");
    private static final Pattern SUMMARY_FIELD = Pattern.compile(
            "(?:^|;\\s*)([A-Za-z][A-Za-z0-9]*)=([^;]{1,240})");

    private final ObjectMapper json;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final ReactPlanTurnIntakeRepository intakes;
    private final ReactPlanTaskCheckpointRepository checkpoints;
    private final ReactPlanTaskEventRepository events;

    ReactPlanConversationHistoryService(
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
    ObjectNode search(Authority authority, SearchRequest request) {
        Set<Long> allowedSessions = requireRequester(authority);
        Long sessionFilter = "current_session".equals(request.scope())
                ? authority.currentSessionId() : null;
        Long beforeId = parseIntakeCursor(request.cursor());
        List<ReactPlanTurnIntakeEntity> fetched = intakes.findTerminalHistoryCandidates(
                authority.userId(), authority.projectId(), AgentSessionScope.PROJECT,
                sessionFilter, beforeId, request.status(), authority.currentTaskId(),
                PageRequest.of(0, MAX_SEARCH_SCAN + 1));
        List<ReactPlanTurnIntakeEntity> candidates = fetched.subList(
                0, Math.min(MAX_SEARCH_SCAN, fetched.size()));
        Map<String, ReactPlanTaskCheckpointEntity> byTask = checkpoints
                .findByTaskIdIn(candidates.stream().map(ReactPlanTurnIntakeEntity::taskId).toList())
                .stream().collect(Collectors.toMap(
                        ReactPlanTaskCheckpointEntity::taskId, Function.identity()));
        Map<Long, AgentMessage> byMessage = messages.findAllById(candidates.stream()
                        .map(ReactPlanTurnIntakeEntity::userMessageId).toList()).stream()
                .collect(Collectors.toMap(AgentMessage::getId, Function.identity()));

        String needle = request.query() == null ? ""
                : request.query().strip().toLowerCase(Locale.ROOT);
        List<AuthorizedTask> selected = new ArrayList<>();
        long lastScannedId = 0L;
        int scanned = 0;
        boolean stoppedAtLimit = false;
        for (ReactPlanTurnIntakeEntity intake : candidates) {
            scanned += 1;
            lastScannedId = intake.id();
            ReactPlanTaskCheckpointEntity checkpoint = byTask.get(intake.taskId());
            AgentMessage instruction = byMessage.get(intake.userMessageId());
            AuthorizedTask task = boundTask(authority, allowedSessions, intake,
                    checkpoint, instruction, false);
            if (!needle.isEmpty()
                    && !task.instruction().getContent().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            selected.add(task);
            if (selected.size() == request.limit()) {
                stoppedAtLimit = scanned < candidates.size() || fetched.size() > candidates.size();
                break;
            }
        }
        boolean hasMore = stoppedAtLimit
                || (selected.size() < request.limit() && fetched.size() > candidates.size());
        Map<String, String> conclusions = deliveryConclusions(
                selected.stream().map(task -> task.intake().taskId()).toList());

        ObjectNode output = json.createObjectNode();
        output.put("schemaVersion", "1.0");
        output.put("scope", request.scope());
        output.put("queryApplied", !needle.isEmpty());
        output.put("resultCount", selected.size());
        output.put("scannedCount", scanned);
        output.put("hasMore", hasMore);
        if (hasMore && lastScannedId > 0) output.put("nextCursor", intakeCursor(lastScannedId));
        else output.putNull("nextCursor");
        ArrayNode items = output.putArray("items");
        for (AuthorizedTask task : selected) {
            ObjectNode item = items.addObject();
            projectTask(item, task, conclusions.get(task.intake().taskId()),
                    SEARCH_INSTRUCTION_CHARS, SEARCH_OUTCOME_CHARS);
        }
        return output;
    }

    @Transactional(readOnly = true)
    ObjectNode task(Authority authority, String taskId) {
        Set<Long> allowedSessions = requireRequester(authority);
        AuthorizedTask task = requireOwnedTask(authority, allowedSessions, taskId);
        String conclusion = deliveryConclusions(List.of(taskId)).get(taskId);
        ObjectNode output = json.createObjectNode();
        output.put("schemaVersion", "1.0");
        projectTask(output.putObject("task"), task, conclusion,
                DETAIL_INSTRUCTION_CHARS, DETAIL_OUTCOME_CHARS);
        output.put("resultCount", 1);
        return output;
    }

    @Transactional(readOnly = true)
    ObjectNode trace(Authority authority, String taskId, String cursor, int limit) {
        Set<Long> allowedSessions = requireRequester(authority);
        AuthorizedTask task = requireOwnedTask(authority, allowedSessions, taskId);
        long afterSequence = parseEventCursor(cursor);
        List<ObjectNode> visible = new ArrayList<>();
        boolean hasMore = false;
        for (ReactPlanTaskEventEntity stored : events.findByTaskIdOrderBySequenceNumberAsc(taskId)) {
            if (stored.sequenceNumber() <= afterSequence) continue;
            ObjectNode projected = publicEvent(parse(stored.eventJson()));
            if (projected == null) continue;
            if (visible.size() == limit) {
                hasMore = true;
                break;
            }
            visible.add(projected);
        }
        ObjectNode output = json.createObjectNode();
        output.put("schemaVersion", "1.0");
        output.put("taskId", taskId);
        output.put("status", task.checkpoint().state());
        output.put("resultCount", visible.size());
        output.put("hasMore", hasMore);
        if (hasMore && !visible.isEmpty()) {
            output.put("nextCursor", eventCursor(visible.get(visible.size() - 1).path("sequence").asLong()));
        } else output.putNull("nextCursor");
        ArrayNode projected = output.putArray("events");
        visible.forEach(projected::add);
        return output;
    }

    private Set<Long> requireRequester(Authority authority) {
        ReactPlanTurnIntakeEntity requester = intakes.findByTaskId(authority.currentTaskId())
                .orElseThrow(HistoryUnavailableException::new);
        if (requester.userId() != authority.userId()
                || requester.sessionId() != authority.currentSessionId()) {
            throw new HistoryUnavailableException();
        }
        AgentSession current = sessions.findByIdAndUserIdAndScopeAndProjectId(
                        authority.currentSessionId(), authority.userId(),
                        AgentSessionScope.PROJECT, authority.projectId())
                .orElseThrow(HistoryUnavailableException::new);
        if (!current.getId().equals(authority.currentSessionId())) {
            throw new HistoryUnavailableException();
        }
        return sessions.findByUserIdAndScopeAndProjectIdOrderByUpdatedAtDesc(
                        authority.userId(), AgentSessionScope.PROJECT, authority.projectId())
                .stream().map(AgentSession::getId).collect(Collectors.toUnmodifiableSet());
    }

    private AuthorizedTask requireOwnedTask(
            Authority authority, Set<Long> allowedSessions, String taskId) {
        if (authority.currentTaskId().equals(taskId)) throw new HistoryUnavailableException();
        ReactPlanTurnIntakeEntity intake = intakes.findByTaskId(taskId)
                .orElseThrow(HistoryUnavailableException::new);
        ReactPlanTaskCheckpointEntity checkpoint = checkpoints.findById(taskId)
                .orElseThrow(HistoryUnavailableException::new);
        AgentMessage instruction = messages.findById(intake.userMessageId())
                .orElseThrow(HistoryUnavailableException::new);
        return boundTask(authority, allowedSessions, intake, checkpoint, instruction, true);
    }

    private AuthorizedTask boundTask(
            Authority authority, Set<Long> allowedSessions,
            ReactPlanTurnIntakeEntity intake,
            ReactPlanTaskCheckpointEntity checkpoint,
            AgentMessage instruction,
            boolean unavailableOnMismatch) {
        boolean valid = checkpoint != null && instruction != null
                && intake.id() > 0 && intake.userId() == authority.userId()
                && allowedSessions.contains(intake.sessionId())
                && checkpoint.userId() == authority.userId()
                && checkpoint.sessionId() == intake.sessionId()
                && checkpoint.turnId() == intake.turnId()
                && checkpoint.taskId().equals(intake.taskId())
                && TERMINAL.contains(checkpoint.state())
                && instruction.getId().equals(intake.userMessageId())
                && instruction.getUserId().equals(authority.userId())
                && instruction.getSessionId().equals(intake.sessionId())
                && "user".equals(instruction.getRole())
                && instruction.getContent() != null && !instruction.getContent().isBlank();
        if (!valid) {
            if (unavailableOnMismatch) throw new HistoryUnavailableException();
            throw new IllegalStateException("Persisted ReAct history index is corrupt");
        }
        return new AuthorizedTask(intake, checkpoint, instruction);
    }

    private void projectTask(ObjectNode output, AuthorizedTask task, String conclusion,
                             int instructionLimit, int outcomeLimit) {
        output.put("taskId", task.intake().taskId());
        output.put("sessionRef", "session." + task.intake().sessionId());
        output.put("status", task.checkpoint().state());
        putTextProjection(output.putObject("instruction"),
                task.instruction().getContent(), instructionLimit);
        putTextProjection(output.putObject("finalOutcome"),
                conclusion == null || conclusion.isBlank()
                        ? terminalOutcome(task.checkpoint()) : conclusion,
                outcomeLimit);
        Instant startedAt = task.intake().createdAt().toInstant(ZoneOffset.UTC);
        Instant finishedAt = task.checkpoint().updatedAt().toInstant(ZoneOffset.UTC);
        output.put("startedAt", startedAt.toString());
        output.put("finishedAt", finishedAt.toString());
        output.put("durationMillis", Math.max(0L,
                Duration.between(startedAt, finishedAt).toMillis()));
    }

    private Map<String, String> deliveryConclusions(List<String> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (ReactPlanTaskEventEntity stored
                : events.findByTaskIdInOrderByTaskIdAscSequenceNumberAsc(taskIds)) {
            JsonNode event = parse(stored.eventJson());
            if ("delivery".equals(event.path("type").asText())
                    && !event.path("conclusion").asText("").isBlank()) {
                result.put(stored.taskId(), event.path("conclusion").asText());
            }
        }
        return Map.copyOf(result);
    }

    private ObjectNode publicEvent(JsonNode event) {
        String type = event.path("type").asText("");
        if (!Set.of("status", "message", "question", "tool", "delivery").contains(type)) {
            return null;
        }
        ObjectNode output = json.createObjectNode();
        output.put("sequence", event.path("sequence").asLong());
        output.put("occurredAt", event.path("occurredAt").asText(""));
        output.put("type", type);
        switch (type) {
            case "status" -> {
                String state = event.path("state").asText("unknown");
                output.put("state", state);
                output.put("title", "Task status");
                String code = event.path("error").path("code").asText("");
                output.put("summary", code.isBlank()
                        ? "Task state changed to " + state + "."
                        : "Task state changed to " + state + " (code=" + safeToken(code) + ").");
            }
            case "message" -> {
                output.put("title", "Agent message");
                output.put("summary", "A user-visible Agent message was recorded.");
            }
            case "question" -> {
                output.put("title", "User question");
                output.put("summary", "The Agent requested user input.");
            }
            case "tool" -> projectToolEvent(output, event);
            case "delivery" -> {
                output.put("title", "Final delivery");
                putTextProjection(output.putObject("summary"),
                        event.path("conclusion").asText("Task delivery was recorded."),
                        TRACE_DELIVERY_CHARS);
            }
            default -> throw new IllegalStateException("unreachable event type");
        }
        return output;
    }

    private void projectToolEvent(ObjectNode output, JsonNode event) {
        String registered = event.path("registeredToolName").asText("");
        String name = registered.isBlank() ? event.path("name").asText("tool") : registered;
        String state = event.path("state").asText("unknown");
        output.put("title", "Tool call");
        output.put("toolName", safeToken(name));
        output.put("state", safeToken(state));
        ObjectNode summary = output.putObject("resultSummary");
        summary.put("state", safeToken(state));
        Set<String> allowed = registered.isBlank()
                ? builtinSummaryFields(name)
                : Set.of("success", "provider", "resultCount", "degraded",
                        "evidenceCount", "retryable");
        Map<String, String> fields = summaryFields(event.path("outputSummary").asText(""));
        for (String key : allowed) {
            String value = fields.get(key);
            if (value != null) summary.put(key, safeToken(value));
        }
    }

    private Set<String> builtinSummaryFields(String name) {
        return switch (name) {
            case "sandbox.execute" -> Set.of(
                    "status", "exitCode", "stdoutBytes", "stderrBytes");
            case "workspace.write" -> Set.of("operation", "sizeBytes", "replayed");
            default -> Set.of();
        };
    }

    private Map<String, String> summaryFields(String summary) {
        if (summary == null || summary.isBlank()) return Map.of();
        Map<String, String> result = new HashMap<>();
        Matcher matcher = SUMMARY_FIELD.matcher(summary);
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2));
        return result;
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

    private void putTextProjection(ObjectNode output, String value, int limit) {
        String safe = value == null ? "" : value;
        boolean truncated = safe.length() > limit;
        output.put("text", truncated ? safe.substring(0, limit) : safe);
        output.put("truncated", truncated);
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Persisted ReAct history is corrupt", corrupt);
        }
    }

    private Long parseIntakeCursor(String cursor) {
        if (cursor == null) return null;
        if (!cursor.matches("intake\\.[1-9][0-9]*")) throw new IllegalArgumentException("cursor");
        try { return Long.parseLong(cursor.substring("intake.".length())); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("cursor"); }
    }

    private long parseEventCursor(String cursor) {
        if (cursor == null) return 0L;
        if (!cursor.matches("event\\.[1-9][0-9]*")) throw new IllegalArgumentException("cursor");
        try { return Long.parseLong(cursor.substring("event.".length())); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("cursor"); }
    }

    private String intakeCursor(long id) { return "intake." + id; }
    private String eventCursor(long sequence) { return "event." + sequence; }

    private String safeToken(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[^A-Za-z0-9._:/ -]", "?");
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }

    record Authority(long userId, long projectId, long currentSessionId,
                     String currentTaskId) { }

    record SearchRequest(String scope, String query, String status,
                         String cursor, int limit) { }

    private record AuthorizedTask(
            ReactPlanTurnIntakeEntity intake,
            ReactPlanTaskCheckpointEntity checkpoint,
            AgentMessage instruction) { }

    static final class HistoryUnavailableException extends RuntimeException {
        HistoryUnavailableException() { super("history unavailable"); }
    }
}
