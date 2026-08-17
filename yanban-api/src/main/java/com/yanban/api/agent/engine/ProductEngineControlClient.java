package com.yanban.api.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
final class ProductEngineControlClient {
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,95}$");
    private static final Set<String> STATES = Set.of(
            "queued", "running", "waiting_user", "succeeded", "failed", "cancelled");
    private final ObjectMapper json;
    private final ObjectMapper strictJson;
    private final ProductEngineProperties properties;
    private final HttpClient http;

    ProductEngineControlClient(ObjectMapper json, ProductEngineProperties properties) {
        this.json = json;
        this.strictJson = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    ProductEngineDtos.Accepted submit(ProductEngineMode mode, ProductEngineDtos.Submission request) {
        return sendJson(mode, "POST", "/v1/tasks", request, ProductEngineDtos.Accepted.class, 202);
    }

    ProductEngineDtos.TaskView get(ProductEngineMode mode, String taskId) {
        return sendJson(mode, "GET", "/v1/tasks/" + taskId, null, ProductEngineDtos.TaskView.class, 200);
    }

    ProductEngineDtos.TaskView cancel(ProductEngineMode mode, String taskId, ProductEngineDtos.Cancel request) {
        return sendJson(mode, "POST", "/v1/tasks/" + taskId + "/cancel", request,
                ProductEngineDtos.TaskView.class, 202);
    }

    ProductEngineDtos.TaskView answer(ProductEngineMode mode, String taskId, ProductEngineDtos.Answer request) {
        return sendJson(mode, "POST", "/v1/tasks/" + taskId + "/answer", request,
                ProductEngineDtos.TaskView.class, 202);
    }

    List<ProductEngineDtos.Event> events(ProductEngineMode mode, String taskId, long afterSequence, long throughSequence) {
        if (throughSequence <= afterSequence) return List.of();
        HttpRequest request = baseRequest(mode, "/v1/tasks/" + taskId + "/events")
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", String.valueOf(afterSequence))
                .GET().build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                byte[] body = response.body().readNBytes(8_192);
                throw problem(response.statusCode(), body);
            }
            List<ProductEngineDtos.Event> result = new ArrayList<>();
            long previous = afterSequence;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.body(), StandardCharsets.UTF_8))) {
                String line;
                Long eventId = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("id:")) {
                        try {
                            eventId = Long.parseLong(line.substring(3).trim());
                        } catch (NumberFormatException invalid) {
                            throw new ProductEngineControlException(502, "ENGINE_EVENT_ID_INVALID");
                        }
                        continue;
                    }
                    if (!line.startsWith("data:")) continue;
                    ProductEngineDtos.Event event = strictJson.readValue(
                            line.substring(5).stripLeading(), ProductEngineDtos.Event.class);
                    if (eventId == null || eventId.longValue() != event.sequence()) {
                        throw new ProductEngineControlException(502, "ENGINE_EVENT_ID_MISMATCH");
                    }
                    validateEvent(taskId, previous, event);
                    result.add(event);
                    previous = event.sequence();
                    eventId = null;
                    if (previous >= throughSequence) break;
                }
            }
            if (previous < throughSequence) {
                throw new ProductEngineControlException(502, "ENGINE_EVENT_STREAM_INCOMPLETE");
            }
            return List.copyOf(result);
        } catch (ProductEngineControlException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProductEngineControlException(502, "ENGINE_UNAVAILABLE");
        }
    }

    private <T> T sendJson(ProductEngineMode mode, String method, String path, Object body, Class<T> type, int expected) {
        try {
            HttpRequest.Builder builder = baseRequest(mode, path).header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != expected) throw problem(response.statusCode(), response.body());
            T decoded = strictJson.readValue(response.body(), type);
            if (decoded instanceof ProductEngineDtos.Accepted accepted) {
                if (!"1.0".equals(accepted.contractVersion()) || accepted.task() == null) {
                    throw new ProductEngineControlException(502, "ENGINE_RESPONSE_INVALID");
                }
                validateViewShape(accepted.task());
            } else if (decoded instanceof ProductEngineDtos.TaskView view) {
                validateViewShape(view);
            }
            return decoded;
        } catch (ProductEngineControlException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ProductEngineControlException(502, "ENGINE_UNAVAILABLE");
        }
    }

    private HttpRequest.Builder baseRequest(ProductEngineMode mode, String path) {
        String base = properties.baseUrl(mode).replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + properties.getServiceToken());
    }

    private ProductEngineControlException problem(int status, byte[] body) {
        try {
            ProductEngineDtos.Problem problem = strictJson.readValue(body, ProductEngineDtos.Problem.class);
            if ("1.0".equals(problem.contractVersion()) && problem.code() != null
                    && CODE.matcher(problem.code()).matches()
                    && problem.category() != null && problem.message() != null && !problem.message().isBlank()) {
                return new ProductEngineControlException(status, problem.code());
            }
        } catch (Exception ignored) {
            // Fail closed without propagating an upstream body.
        }
        return new ProductEngineControlException(status, "ENGINE_UPSTREAM_ERROR");
    }

    private void validateEvent(String taskId, long previous, ProductEngineDtos.Event event) {
        if (event == null || !"1.0".equals(event.contractVersion())
                || !taskId.equals(event.taskId()) || event.sequence() != previous + 1
                || event.type() == null || event.occurredAt() == null) {
            throw new ProductEngineControlException(502, "ENGINE_EVENT_BINDING_MISMATCH");
        }
        boolean valid = switch (event.type()) {
            case "status" -> STATES.contains(event.state())
                    && (!("failed".equals(event.state())) || validProblem(event.error()))
                    && (!("succeeded".equals(event.state())) || event.error() == null);
            case "message" -> bounded(event.content(), 1, 16_000);
            case "question" -> bounded(event.questionId(), 1, 128)
                    && bounded(event.text(), 1, 4_000);
            case "tool" -> bounded(event.callId(), 1, 128)
                    && Set.of("project.list", "project.read", "sandbox.execute").contains(event.name())
                    && Set.of("requested", "running", "succeeded", "failed", "cancelled").contains(event.state())
                    && bounded(event.inputSummary(), 0, 1_000)
                    && (event.outputSummary() == null || event.outputSummary().length() <= 2_000)
                    && (event.receiptRef() == null || event.receiptRef().length() <= 256);
            case "delivery" -> bounded(event.conclusion(), 1, 16_000)
                    && event.receiptRefs() != null && event.receiptRefs().size() <= 64
                    && event.receiptRefs().stream().distinct().count() == event.receiptRefs().size()
                    && event.receiptRefs().stream().allMatch(value -> bounded(value, 1, 256));
            default -> false;
        };
        if (!valid) throw new ProductEngineControlException(502, "ENGINE_EVENT_INVALID");
    }

    private void validateViewShape(ProductEngineDtos.TaskView view) {
        if (view == null || !"1.0".equals(view.contractVersion())
                || view.taskId() == null || !view.taskId().matches("^task\\.[a-f0-9]{64}$")
                || view.requestDigest() == null || !view.requestDigest().matches("^[a-f0-9]{64}$")
                || !STATES.contains(view.state())
                || view.createdAt() == null || view.updatedAt() == null || view.lastSequence() < 0) {
            throw new ProductEngineControlException(502, "ENGINE_RESPONSE_INVALID");
        }
        boolean terminal = Set.of("succeeded", "failed", "cancelled").contains(view.state());
        if ("waiting_user".equals(view.state()) && !bounded(view.pendingQuestionId(), 1, 128)
                || terminal && (view.terminalSequence() == null || view.terminalSequence() < 1)
                || "succeeded".equals(view.state())
                    && (view.deliverySequence() == null || view.deliverySequence() < 1 || view.error() != null)
                || "failed".equals(view.state()) && !validProblem(view.error())) {
            throw new ProductEngineControlException(502, "ENGINE_RESPONSE_INVALID");
        }
    }

    private boolean validProblem(ProductEngineDtos.Problem problem) {
        return problem != null && "1.0".equals(problem.contractVersion())
                && problem.code() != null && CODE.matcher(problem.code()).matches()
                && bounded(problem.category(), 1, 32) && bounded(problem.message(), 1, 1_000);
    }

    private boolean bounded(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum;
    }
}
