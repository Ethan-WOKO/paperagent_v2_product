package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(prefix = "yanban.agent.reactplan", name = "enabled", havingValue = "true")
final class ReactPlanEngineClient {
    private final ObjectMapper json;
    private final ReactPlanRuntimeProperties properties;
    private final HttpClient http;

    @Autowired
    ReactPlanEngineClient(ObjectMapper json, ReactPlanRuntimeProperties properties) {
        this(json, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    ReactPlanEngineClient(ObjectMapper json, ReactPlanRuntimeProperties properties, HttpClient http) {
        this.json = json;
        this.properties = properties;
        this.http = http;
    }

    JsonNode submit(JsonNode body) { return json("POST", "/v1/tasks", body); }
    JsonNode task(String taskId) { return json("GET", "/v1/tasks/" + taskId, null); }
    JsonNode cancel(String taskId, String clientRequestId) {
        ObjectNodeBuilder body = new ObjectNodeBuilder(json)
                .put("contractVersion", "1.0").put("clientRequestId", clientRequestId);
        return json("POST", "/v1/tasks/" + taskId + "/cancel", body.node());
    }

    JsonNode answer(String taskId, JsonNode body) {
        return json("POST", "/v1/tasks/" + taskId + "/answer", body);
    }

    InputStream events(String taskId, long afterSequence) {
        HttpRequest request = base("/v1/tasks/" + taskId + "/events")
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", String.valueOf(afterSequence))
                .GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw upstream(response.statusCode());
            }
            return response.body();
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ReAct Engine is unavailable");
        }
    }

    private JsonNode json(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = base(path).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Content-Type", "application/json");
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstream(response.statusCode());
            }
            return json.readTree(response.body());
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ReAct Engine is unavailable");
        }
    }

    private HttpRequest.Builder base(String path) {
        URI origin = properties.getEngineOrigin();
        return HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + properties.getEngineServiceToken());
    }

    private static ResponseStatusException upstream(int status) {
        HttpStatus mapped = status == 404 ? HttpStatus.NOT_FOUND
                : status == 409 ? HttpStatus.CONFLICT
                : status == 400 ? HttpStatus.BAD_REQUEST
                : status == 401 ? HttpStatus.BAD_GATEWAY
                : HttpStatus.BAD_GATEWAY;
        return new ResponseStatusException(mapped, "ReAct Engine request failed");
    }

    private static final class ObjectNodeBuilder {
        private final com.fasterxml.jackson.databind.node.ObjectNode node;
        ObjectNodeBuilder(ObjectMapper json) { node = json.createObjectNode(); }
        ObjectNodeBuilder put(String name, String value) { node.put(name, value); return this; }
        JsonNode node() { return node; }
    }
}
