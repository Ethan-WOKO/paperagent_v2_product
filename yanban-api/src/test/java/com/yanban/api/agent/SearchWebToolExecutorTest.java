package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SearchWebToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executeParsesDuckDuckGoHtmlResults() throws Exception {
        startServer("/html/", exchange -> {
            byte[] bytes = """
                    <html>
                      <body>
                        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Frag&amp;rut=x">RAG overview</a>
                        <a class="result__snippet" href="#">Retrieval augmented generation overview snippet.</a>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        ToolResult result = executor().execute(new ToolCall("call_1", "search_web", arguments("RAG architecture", 3)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("degraded").asBoolean()).isFalse();
        assertThat(result.output().path("source").asText()).isEqualTo("duckduckgo");
        assertThat(result.output().path("items")).hasSize(1);
        JsonNode item = result.output().path("items").get(0);
        assertThat(item.path("title").asText()).isEqualTo("RAG overview");
        assertThat(item.path("url").asText()).isEqualTo("https://example.com/rag");
        assertThat(item.path("snippet").asText()).contains("Retrieval augmented");
        assertThat(item.path("sourceAuthority").asText()).isEqualTo("general_web");
        assertThat(item.path("evidenceRef").asText()).matches("web:[a-f0-9]{64}");
        assertThat(result.evidenceRefs()).containsExactly(item.path("evidenceRef").asText());
    }

    @Test
    void executeReturnsDegradedResultWhenSearchBackendFails() throws Exception {
        startServer("/html/", exchange -> {
            byte[] bytes = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        ToolResult result = executor().execute(new ToolCall("call_1", "search_web", arguments("RAG architecture", 3)));

        assertThat(result.success()).isTrue();
        assertThat(result.output().path("degraded").asBoolean()).isTrue();
        assertThat(result.output().path("items")).isEmpty();
        assertThat(result.evidenceRefs()).isEmpty();
        assertThat(result.output().path("guidance").asText())
                .contains("do not present model-memory claims as latest facts");
    }

    @Test
    void executeCallsTavilyWithCreditSavingDefaultsAndCachesResults() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        JsonNode[] capturedBody = new JsonNode[1];
        startServer("/search", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer tvly-test-key");
            capturedBody[0] = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            byte[] bytes = """
                    {
                      "query": "latest AI model releases",
                      "results": [
                        {
                          "title": "Community model ranking",
                          "url": "https://segmentfault.com/a/1190000047744499",
                          "content": "A community ranking mentions a model release.",
                          "score": 0.93,
                          "published_date": "2026-07-01"
                        },
                        {
                          "title": "OpenAI release notes",
                          "url": "https://openai.com/index/model-release-notes",
                          "content": "Official release notes with current model information.",
                          "score": 0.81,
                          "published_date": "2026-07-01"
                        }
                      ],
                      "response_time": 0.42,
                      "usage": { "credits": 1 },
                      "request_id": "req_123"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        WebSearchProperties properties = tavilyProperties();
        SearchWebToolExecutor executor = executor(properties);
        ToolCall call = new ToolCall("call_1", "search_web", arguments("latest AI model releases", 5));

        ToolResult first = executor.execute(call);
        ToolResult second = executor.execute(call);

        assertThat(requests).hasValue(1);
        assertThat(capturedBody[0].path("search_depth").asText()).isEqualTo("basic");
        assertThat(capturedBody[0].path("auto_parameters").asBoolean()).isFalse();
        assertThat(capturedBody[0].path("max_results").asInt()).isEqualTo(5);
        assertThat(capturedBody[0].path("include_answer").asBoolean()).isFalse();
        assertThat(capturedBody[0].path("include_raw_content").asBoolean()).isFalse();
        assertThat(capturedBody[0].path("include_images").asBoolean()).isFalse();
        assertThat(capturedBody[0].path("include_usage").asBoolean()).isTrue();

        assertThat(first.output().path("source").asText()).isEqualTo("tavily");
        assertThat(first.output().path("cacheHit").asBoolean()).isFalse();
        assertThat(first.output().path("usageCredits").asInt()).isEqualTo(1);
        assertThat(first.output().path("requestId").asText()).isEqualTo("req_123");
        assertThat(first.output().path("items").get(0).path("publishedAt").asText()).isEqualTo("2026-07-01");
        assertThat(first.output().path("items").get(0).path("url").asText()).contains("openai.com");
        assertThat(first.output().path("items").get(0).path("sourceAuthority").asText()).isEqualTo("official");
        assertThat(first.output().path("items").get(1).path("sourceAuthority").asText()).isEqualTo("secondary");
        assertThat(first.output().path("sourcePolicy").asText()).contains("official/high");
        assertThat(first.evidenceRefs()).hasSize(2).allMatch(ref -> ref.matches("web:[a-f0-9]{64}"));
        assertThat(first.output().path("items").get(0).path("evidenceRef").asText())
                .isEqualTo(first.evidenceRefs().get(0));
        assertThat(second.output().path("cacheHit").asBoolean()).isTrue();
        assertThat(second.output().path("estimatedCreditsCharged").asInt()).isEqualTo(0);
        assertThat(second.evidenceRefs()).containsExactlyElementsOf(first.evidenceRefs());
    }

    private SearchWebToolExecutor executor() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/html/");
        properties.setTimeout(Duration.ofSeconds(2));
        return executor(properties);
    }

    private SearchWebToolExecutor executor(WebSearchProperties properties) {
        return new SearchWebToolExecutor(
                objectMapper,
                properties,
                List.of(
                        new DuckDuckGoWebSearchClient(RestClient.builder(), properties),
                        new TavilyWebSearchClient(RestClient.builder(), objectMapper, properties)
                )
        );
    }

    private WebSearchProperties tavilyProperties() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setProvider("tavily");
        properties.setTimeout(Duration.ofSeconds(2));
        properties.setCacheTtl(Duration.ofMinutes(30));
        properties.getTavily().setEndpoint("http://localhost:" + server.getAddress().getPort() + "/search");
        properties.getTavily().setApiKey("tvly-test-key");
        return properties;
    }

    private JsonNode arguments(String query, int topK) {
        return objectMapper.createObjectNode()
                .put("query", query)
                .put("topK", topK);
    }

    private void startServer(String path, ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
