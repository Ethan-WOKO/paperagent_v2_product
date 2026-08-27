package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanban.knowledge.config.KnowledgeEmbeddingProperties;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class DashScopeEmbeddingClientTest {

    @Test
    void embedParsesVectorFromDashScopeResponse() {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        properties.setApiUrl("https://dashscope.test/v1/embeddings");
        properties.setApiKey("test-key");
        properties.setModel("text-embedding-v4");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl(properties.getApiUrl()).build();
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(restClient, properties);

        server.expect(requestTo("https://dashscope.test/v1/embeddings"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}", MediaType.APPLICATION_JSON));

        List<Double> vector = client.embed("hello");

        assertThat(vector).containsExactly(0.1d, 0.2d, 0.3d);
        server.verify();
    }

    @Test
    void embedExplainsDnsFailureAsRetryableDependencyOutage() {
        KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties();
        properties.setApiUrl("https://dashscope.test/v1/embeddings");
        properties.setApiKey("test-key");

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DashScopeEmbeddingClient client = new DashScopeEmbeddingClient(
                builder.baseUrl(properties.getApiUrl()).build(), properties);
        server.expect(requestTo("https://dashscope.test/v1/embeddings"))
                .andRespond(request -> {
                    throw new ResourceAccessException("network unavailable",
                            new UnknownHostException("dashscope.test"));
                });

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("向量服务域名解析失败，请检查运行环境 DNS；系统将自动重试")
                .hasCauseInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
