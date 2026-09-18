package com.yanban.api.settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.DeepSeekProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

class ModelDiscoveryServiceTest {
    @Test void discoversRealHttpListAndSanitizesFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var auth=new AtomicReference<String>();
        server.createContext("/models",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] data="{\"data\":[{\"id\":\"brand-new-model\"},{\"id\":\"brand-new-model\"},{\"id\":\"vision-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });
        server.createContext("/failure",exchange->{byte[] data="secret-key".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(401,data.length);exchange.getResponseBody().write(data);exchange.close();});
        server.start();
        try {
            var service=new ModelDiscoveryService(new DeepSeekProperties(),WebClient.builder(),new ObjectMapper());
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThat(service.discoverModels(base+"/models","secret-key")).containsExactly("brand-new-model","vision-model");
            assertThat(auth.get()).isEqualTo("Bearer secret-key");
            assertThatThrownBy(()->service.discoverModels(base+"/failure","secret-key")).hasMessageContaining("HTTP 401").hasMessageNotContaining("secret-key");
        } finally { server.stop(0); }
    }
}
