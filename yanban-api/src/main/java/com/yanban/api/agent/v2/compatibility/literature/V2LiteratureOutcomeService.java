package com.yanban.api.agent.v2.compatibility.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import com.yanban.paper.literature.LiteratureSearchTaskService;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class V2LiteratureOutcomeService {
    private static final int MAX_RESULT_JSON = 1_000_000;
    private static final int MAX_FAILURES = 8;
    private static final int MAX_AUTHORS = 20;
    private static final int MAX_MESSAGE = 32_000;

    private final AgentSessionRepository sessions;
    private final LiteratureDeliveryJpaRepository deliveries;
    private final LiteratureSearchTaskRepository tasks;
    private final LiteratureSearchTaskService taskService;
    private final AgentMessageRepository messages;
    private final ObjectMapper json;

    public V2LiteratureOutcomeService(
            AgentSessionRepository sessions,
            LiteratureDeliveryJpaRepository deliveries,
            LiteratureSearchTaskRepository tasks,
            LiteratureSearchTaskService taskService,
            AgentMessageRepository messages,
            ObjectMapper json) {
        this.sessions = sessions;
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.taskService = taskService;
        this.messages = messages;
        this.json = json;
    }

    @Transactional
    public V2LiteratureOutcomeResponse get(
            Long userId, Long sessionId, String clientRequestId) {
        LiteratureDeliveryEntity delivery = requireDelivery(
                userId, sessionId, clientRequestId);
        LiteratureSearchTask task = requireTask(userId, delivery);
        ParsedResult result = parseResult(task, delivery);
        materializeResultMessage(userId, sessionId, delivery, result);
        return response(delivery, task, result);
    }

    @Transactional
    public V2LiteratureOutcomeResponse cancel(
            Long userId, Long sessionId, String clientRequestId) {
        LiteratureDeliveryEntity delivery = requireDelivery(
                userId, sessionId, clientRequestId);
        LiteratureSearchTask task = requireTask(userId, delivery);
        LiteratureSearchTask cancelled = taskService.requestCancel(
                userId, task.getId(), "Cancelled from V2 literature turn");
        ParsedResult result = parseResult(cancelled, delivery);
        materializeResultMessage(userId, sessionId, delivery, result);
        return response(delivery, cancelled, result);
    }

    private void materializeResultMessage(
            Long userId, Long sessionId,
            LiteratureDeliveryEntity delivery, ParsedResult result) {
        if (!result.available()
                || delivery.resultAssistantMessageId() != null) {
            return;
        }
        AgentMessage message = messages.saveAndFlush(new AgentMessage(
                sessionId, userId, "assistant",
                resultMessage(result), null, null));
        delivery.bindResultAssistantMessage(message.getId());
        deliveries.saveAndFlush(delivery);
    }

    private LiteratureDeliveryEntity requireDelivery(
            Long userId, Long sessionId, String clientRequestId) {
        if (userId == null || sessionId == null
                || clientRequestId == null
                || clientRequestId.isBlank()
                || clientRequestId.length() > 128) {
            throw notFound();
        }
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(V2LiteratureOutcomeService::notFound);
        if (session.getScope() != AgentSessionScope.WORKSPACE
                || session.getProjectId() != null) {
            throw notFound();
        }
        return deliveries.findLocked(new LiteratureDeliveryKey(
                        userId, sessionId, clientRequestId.strip()))
                .orElseThrow(V2LiteratureOutcomeService::notFound);
    }

    private LiteratureSearchTask requireTask(
            Long userId, LiteratureDeliveryEntity delivery) {
        Long taskId = delivery.literatureTaskId();
        if (taskId == null) {
            throw notFound();
        }
        LiteratureSearchTask task = tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(V2LiteratureOutcomeService::notFound);
        if (task.getProjectId() != null
                || !Objects.equals(task.getUserId(), userId)
                || !normalize(task.getQuery())
                        .equals(normalize(delivery.query()))
                || !Objects.equals(task.getTopK(), delivery.topK())
                || !Objects.equals(task.getYearFrom(), delivery.yearFrom())
                || !Objects.equals(
                        task.getIncludeBibtex(), delivery.includeBibtex())) {
            throw notFound();
        }
        return task;
    }

    private ParsedResult parseResult(
            LiteratureSearchTask task, LiteratureDeliveryEntity delivery) {
        String taskStatus = normalizeStatus(task.getStatus());
        if (!LiteratureSearchTaskService.STATUS_COMPLETED.equals(taskStatus)) {
            return new ParsedResult(false, taskStatus,
                    safeStage(task.getCurrentStage()),
                    List.of(), List.of());
        }
        String raw = task.getResultJson();
        if (raw == null || raw.isBlank() || raw.length() > MAX_RESULT_JSON) {
            throw resultUnavailable();
        }
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()
                    || !root.path("items").isArray()) {
                throw resultUnavailable();
            }
            List<V2LiteraturePaperItem> items = new ArrayList<>();
            int limit = Math.min(delivery.topK(), 20);
            for (JsonNode item : root.path("items")) {
                if (items.size() >= limit) {
                    break;
                }
                V2LiteraturePaperItem projected =
                        paper(item, delivery.includeBibtex());
                if (projected != null) {
                    items.add(projected);
                }
            }
            List<String> failures = sourceFailures(
                    task.getSourceFailuresJson(), root.path("sourceFailures"));
            String status = failures.isEmpty() ? "COMPLETED" : "PARTIAL";
            return new ParsedResult(
                    true, status, safeStage(task.getCurrentStage()),
                    List.copyOf(failures), List.copyOf(items));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw resultUnavailable();
        }
    }

    private V2LiteraturePaperItem paper(
            JsonNode value, boolean includeBibtex) {
        if (value == null || !value.isObject()) {
            return null;
        }
        String title = text(value, "title", 500);
        if (title == null) {
            return null;
        }
        List<String> authors = new ArrayList<>();
        JsonNode rawAuthors = value.path("authors");
        if (rawAuthors.isArray()) {
            for (JsonNode author : rawAuthors) {
                if (authors.size() >= MAX_AUTHORS) {
                    break;
                }
                String text = bounded(author, 160);
                if (text != null) {
                    authors.add(text);
                }
            }
        }
        Integer year = value.path("year").canConvertToInt()
                ? value.path("year").intValue() : null;
        Long cardId = value.path("cardId").canConvertToLong()
                && value.path("cardId").longValue() > 0
                ? value.path("cardId").longValue() : null;
        Double score = value.path("score").isNumber()
                && Double.isFinite(value.path("score").doubleValue())
                ? value.path("score").doubleValue() : null;
        return new V2LiteraturePaperItem(
                cardId, title, authors, year,
                text(value, "venue", 300),
                text(value, "doi", 256),
                text(value, "arxivId", 128),
                text(value, "openAlexId", 256),
                safeUrl(text(value, "url", 2_048)),
                text(value, "source", 128),
                score,
                includeBibtex ? text(value, "bibtex", 8_192) : null);
    }

    private List<String> sourceFailures(
            String stored, JsonNode fallback) {
        JsonNode root = null;
        try {
            if (stored != null && !stored.isBlank()
                    && stored.length() <= 32_000) {
                root = json.readTree(stored);
            }
        } catch (Exception ignored) {
            root = null;
        }
        if (root == null || !root.isArray()) {
            root = fallback;
        }
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        for (JsonNode value : root) {
            if (failures.size() >= MAX_FAILURES) {
                break;
            }
            String failure = bounded(value, 240);
            if (failure != null) {
                failures.add(failure);
            }
        }
        return failures;
    }

    private V2LiteratureOutcomeResponse response(
            LiteratureDeliveryEntity delivery,
            LiteratureSearchTask task,
            ParsedResult result) {
        boolean terminal = result.available()
                || LiteratureSearchTaskService.STATUS_FAILED.equals(
                        result.status())
                || LiteratureSearchTaskService.STATUS_CANCELLED.equals(
                        result.status());
        boolean cancellable = LiteratureSearchTaskService.STATUS_PENDING
                .equals(result.status())
                || LiteratureSearchTaskService.STATUS_RUNNING
                .equals(result.status());
        return new V2LiteratureOutcomeResponse(
                delivery.id().sessionId(), delivery.turnId(),
                delivery.id().clientRequestId(), task.getId(),
                result.status(), result.stage(), terminal, cancellable,
                delivery.topK(), delivery.includeBibtex(),
                delivery.resultAssistantMessageId(),
                result.items().size(),
                task.getUniqueCandidateCount() == null
                        ? result.items().size()
                        : Math.max(0, task.getUniqueCandidateCount()),
                result.failures(), result.items());
    }

    private static String resultMessage(ParsedResult result) {
        StringBuilder value = new StringBuilder();
        value.append(result.status().equals("PARTIAL")
                ? "Literature search completed with source warnings."
                : "Literature search completed.")
                .append("\n\n");
        if (result.items().isEmpty()) {
            value.append("No matching papers were returned.");
        } else {
            for (int index = 0; index < result.items().size(); index++) {
                V2LiteraturePaperItem item = result.items().get(index);
                value.append(index + 1).append(". ");
                value.append(markdown(item.title()));
                if (!item.authors().isEmpty()) {
                    value.append(" - ")
                            .append(markdown(String.join(", ", item.authors())));
                }
                if (item.year() != null) {
                    value.append(" (").append(item.year()).append(')');
                }
                value.append('\n');
            }
        }
        String message = value.toString();
        return message.length() <= MAX_MESSAGE
                ? message : message.substring(0, MAX_MESSAGE);
    }

    private static String markdown(String value) {
        return value.replace("\\", "\\\\")
                .replace("[", "\\[").replace("]", "\\]");
    }

    private static String text(JsonNode object, String field, int max) {
        return bounded(object.get(field), max);
    }

    private static String bounded(JsonNode value, int max) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.textValue().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String safeUrl(String value) {
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme))
                    ? value : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? ""
                : value.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PENDING", "RUNNING", "CANCEL_REQUESTED", "CANCELLING",
                    "COMPLETED", "FAILED", "CANCELLED" -> status;
            case "STOPPED" -> "CANCELLED";
            default -> "FAILED";
        };
    }

    private static String safeStage(String value) {
        String stage = normalize(value);
        return stage.isEmpty() ? "UNKNOWN"
                : stage.substring(0, Math.min(stage.length(), 64));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "V2 literature turn was not found");
    }

    private static ResponseStatusException resultUnavailable() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "V2 literature result is unavailable");
    }

    private record ParsedResult(
            boolean available,
            String status,
            String stage,
            List<String> failures,
            List<V2LiteraturePaperItem> items) {
    }
}
