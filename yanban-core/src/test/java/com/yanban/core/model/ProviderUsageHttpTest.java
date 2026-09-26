package com.yanban.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

class ProviderUsageHttpTest {
    @ParameterizedTest
    @CsvSource({"deepseek,false", "deepseek,true", "glm,false", "glm,true", "custom,false", "custom,true"})
    void retainsCacheUsageAcrossHttpAndSse(String name, boolean streaming) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String usage = name.equals("deepseek")
                    ? "\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20"
                    : "\"prompt_tokens_details\":{\"cached_tokens\":80}";
            String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"total_tokens\":105," + usage + "}}";
            if (streaming) body = "data: " + body + "\n\ndata: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
            DeepSeekProperties deepseek = new DeepSeekProperties();
            deepseek.setApiUrl(url);
            GlmProperties glm = new GlmProperties();
            glm.setApiUrl(url);
            ChatModelProvider provider = switch (name) {
                case "deepseek" -> new DeepSeekModelProvider(deepseek);
                case "glm" -> new GlmModelProvider(glm, WebClient.builder(), new ObjectMapper());
                default -> new OpenAiCompatibleModelProvider(new ObjectMapper());
            };
            ChatRequest request = new ChatRequest(name, "fixture", List.of(ChatMessage.user("hello")),
                    null, 20, null, "fixture-key", url, null, null, "fixture-trace", Duration.ofSeconds(5));
            ChatResponse.Usage usage = streaming
                    ? provider.streamChat(request).filter(chunk -> chunk.usage() != null).blockFirst(Duration.ofSeconds(5)).usage()
                    : provider.chat(request).usage();
            assertThat(usage.cacheHitTokens()).isEqualTo(80);
            assertThat(usage.cacheMissTokens()).isEqualTo(20);
            assertThat(usage.promptTokens()).isEqualTo(100);
        } finally { server.stop(0); }
    }
}
