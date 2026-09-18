package com.yanban.core.model;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

class MultimodalProviderTest {
    @ParameterizedTest
    @CsvSource({"custom,false","custom,true","glm,false","glm,true","deepseek,false","deepseek,true"})
    void requestContainsPixelsAndTextOnBothTransports(String kind, boolean stream) throws Exception {
        var mapper=new ObjectMapper();var body=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String response=stream ? "data: {\"choices\":[{\"delta\":{\"content\":\"seen\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"seen\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",stream?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/chat";
            ChatModelProvider provider=switch(kind) {
                case "glm" -> {var properties=new GlmProperties();properties.setApiUrl(url);yield new GlmModelProvider(properties,WebClient.builder(),mapper);}
                case "deepseek" -> {var properties=new DeepSeekProperties();properties.setApiUrl(url);yield new DeepSeekModelProvider(properties,WebClient.builder(),mapper);}
                default -> new OpenAiCompatibleModelProvider(WebClient.builder(),mapper,Duration.ofSeconds(5));
            };
            var request=new ChatRequest(kind,"vision",List.of(new ChatMessage("user","这张图是什么",null,null,List.of(new ChatImage("image/png","AQID")))),null,null,null,"test-key",url,null,null,"test",Duration.ofSeconds(5));
            if(stream) assertThat(provider.streamChat(request).collectList().block()).isNotEmpty();
            else assertThat(provider.chat(request).assistantText()).isEqualTo("seen");
            var content=mapper.readTree(body.get()).path("messages").get(0).path("content");
            assertThat(content.isArray()).isTrue();
            assertThat(content.get(0).path("text").asText()).isEqualTo("这张图是什么");
            assertThat(content.get(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,AQID");
        } finally {server.stop(0);}
    }
}
