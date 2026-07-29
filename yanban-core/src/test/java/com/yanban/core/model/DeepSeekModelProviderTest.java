package com.yanban.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeepSeekModelProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chatParsesAssistantTextAndSendsOpenAiCompatiblePayload() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> traceId = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            traceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "你好，我是研伴。"
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 5,
                        "completion_tokens": 7,
                        "total_tokens": 12
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties());
        ChatResponse response = provider.chat(new ChatRequest(
                "deepseek",
                null,
                List.of(ChatMessage.system("你是研伴 Agent。"), ChatMessage.user("你好")),
                0.2,
                128,
                null,
                null,
                null,
                null,
                "trace-deepseek-1"
        ));

        assertThat(response.assistantText()).isEqualTo("你好，我是研伴。");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.usage().totalTokens()).isEqualTo(12);
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(traceId.get()).isEqualTo("trace-deepseek-1");
        assertThat(requestBody.get()).contains("\"model\":\"deepseek-chat\"");
        assertThat(requestBody.get()).contains("\"role\":\"user\"");
        assertThat(requestBody.get()).contains("\"max_tokens\":128");
        assertThat(requestBody.get()).contains("\"stream\":false");
    }

    @Test
    void chatTransmitsStructuredJsonResponseFormat() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties());
        provider.chat(new ChatRequest(
                "deepseek", "deepseek-chat", List.of(ChatMessage.user("plan")),
                0.2, 2048, null, "test-key", null,
                ChatRequest.ResponseFormat.jsonObject(), ChatRequest.Thinking.disabled(), null));

        assertThat(requestBody.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(requestBody.get()).contains("\"thinking\":{\"type\":\"disabled\"}");
    }

    @Test
    void streamChatParsesMultipleSseChunks() throws Exception {
        startServer(exchange -> {
            byte[] bytes = """
                    data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}

                    data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}

                    data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties());

        List<ChatChunk> chunks = provider.streamChat(ChatRequest.simple(null, List.of(ChatMessage.user("打招呼"))))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull();
        String text = chunks.stream()
                .filter(chunk -> !chunk.done())
                .map(ChatChunk::content)
                .collect(Collectors.joining());
        assertThat(text).isEqualTo("你好");
        assertThat(chunks).anyMatch(ChatChunk::done);
    }

    @Test
    void chatMapsHttpErrorToDomainException() throws Exception {
        startServer(exchange -> {
            byte[] bytes = "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties());

        assertThatThrownBy(() -> provider.chat(ChatRequest.simple(null, List.of(ChatMessage.user("你好")))))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("DeepSeek API error");
    }

    @Test
    void chatRejectsMissingApiKeyBeforeSendingRequest() {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiUrl("http://localhost:1/v1/chat/completions");
        properties.setApiKey("");
        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties);

        assertThatThrownBy(() -> provider.chat(ChatRequest.simple(null, List.of(ChatMessage.user("你好")))))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void chatUsesRequestApiUrlOverride() throws Exception {
        startServer(exchange -> {
            byte[] bytes = """
                    {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
        DeepSeekProperties properties = properties();
        properties.setApiUrl("http://localhost:1/not-used");
        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties);
        String override = "http://localhost:"
                + server.getAddress().getPort()
                + "/v1/chat/completions";

        ChatResponse response = provider.chat(new ChatRequest(
                "deepseek", "deepseek-chat",
                List.of(ChatMessage.user("test")),
                0.2, 128, null, "test-key", override,
                null, ChatRequest.Thinking.disabled(), "trace-test"));

        assertThat(response.assistantText()).isEqualTo("ok");
    }

    @Test
    void streamChatUsesRequestApiUrlOverride() throws Exception {
        startServer(exchange -> {
            byte[] bytes = """
                    data: {"choices":[{"delta":{"content":"ok"}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
        DeepSeekProperties properties = properties();
        properties.setApiUrl("http://localhost:1/not-used");
        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties);
        String override = "http://localhost:"
                + server.getAddress().getPort()
                + "/v1/chat/completions";
        ChatRequest request = new ChatRequest(
                "deepseek", "deepseek-chat",
                List.of(ChatMessage.user("test")),
                0.2, 128, null, "test-key", override,
                null, ChatRequest.Thinking.disabled(), "trace-test");

        List<ChatChunk> chunks = provider.streamChat(request)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull();
        assertThat(chunks).anyMatch(
                chunk -> "ok".equals(chunk.content()));
        assertThat(chunks).anyMatch(ChatChunk::done);
    }

    private DeepSeekProperties properties() {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiUrl("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
        properties.setApiKey("test-key");
        properties.setModel("deepseek-chat");
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
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
