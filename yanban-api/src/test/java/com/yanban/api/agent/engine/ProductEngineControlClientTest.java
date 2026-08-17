package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductEngineControlClientTest {
    private static final String TASK = "task." + "a".repeat(64);
    private HttpServer server;
    private ProductEngineControlClient client;
    private final AtomicBoolean mismatchedId = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tasks/" + TASK + "/events", this::events);
        server.createContext("/v1/tasks/" + TASK, this::task);
        server.start();
        ProductEngineProperties properties = new ProductEngineProperties();
        properties.setMode("dsh");
        properties.setDshBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setServiceToken("service-token-that-never-enters-json");
        properties.setRequestTimeout(Duration.ofSeconds(3));
        client = new ProductEngineControlClient(new ObjectMapper().findAndRegisterModules(), properties);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void validatesSseIdSequenceAndBinding() {
        List<ProductEngineDtos.Event> events = client.events(ProductEngineMode.DSH, TASK, 0, 1);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.taskId()).isEqualTo(TASK);
            assertThat(event.sequence()).isEqualTo(1);
            assertThat(event.state()).isEqualTo("running");
        });
    }

    @Test
    void rejectsSseIdThatDoesNotEqualPayloadSequence() {
        mismatchedId.set(true);
        assertThatThrownBy(() -> client.events(ProductEngineMode.DSH, TASK, 0, 1))
                .isInstanceOf(ProductEngineControlException.class)
                .hasMessage("ENGINE_EVENT_ID_MISMATCH");
    }

    @Test
    void propagatesOnlyValidatedProblemCode() {
        ProductEngineControlException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> client.get(ProductEngineMode.DSH, TASK), ProductEngineControlException.class);
        assertThat(failure.code()).isEqualTo("MODEL_LOOP_FAILED");
        assertThat(failure.getMessage()).doesNotContain("secret-upstream-detail");
    }

    private void events(HttpExchange exchange) throws java.io.IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer service-token-that-never-enters-json");
        assertThat(exchange.getRequestHeaders().getFirst("Last-Event-ID")).isEqualTo("0");
        String data = """
                {"contractVersion":"1.0","taskId":"%s","sequence":1,"occurredAt":"2026-08-17T00:00:00Z","type":"status","state":"running","error":null}
                """.formatted(TASK).trim();
        byte[] body = ("id: " + (mismatchedId.get() ? 2 : 1) + "\n" + "data: " + data + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void task(HttpExchange exchange) throws java.io.IOException {
        byte[] body = """
                {"contractVersion":"1.0","code":"MODEL_LOOP_FAILED","category":"model","message":"secret-upstream-detail","retryable":false}
                """.trim().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(500, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
