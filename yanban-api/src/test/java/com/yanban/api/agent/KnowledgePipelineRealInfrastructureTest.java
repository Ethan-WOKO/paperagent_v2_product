package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.KnowledgeOutboxDispatcher;
import com.yanban.knowledge.service.OcrProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@EnabledIfSystemProperty(named = "yanban.real-infra-tests", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_real_pipeline_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:9092",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890",
        "yanban.knowledge.minio.endpoint=http://localhost:9000",
        "yanban.knowledge.minio.access-key=yanbanminio",
        "yanban.knowledge.minio.secret-key=yanban_minio_password",
        "yanban.knowledge.minio.bucket=yanban-issue185-e2e",
        "yanban.knowledge.elasticsearch.endpoint=http://localhost:9200",
        "yanban.knowledge.elasticsearch.index-name=yanban-kb-issue185-e2e",
        "yanban.knowledge.elasticsearch.legacy-index-name=yanban-kb-issue185-e2e-legacy",
        "yanban.knowledge.elasticsearch.migrate-legacy-index=false",
        "yanban.knowledge.upload.processing-topic=file-processing-issue185-e2e-v3",
        "yanban.knowledge.upload.processing-partitions=3",
        "yanban.knowledge.upload.consumer-concurrency=2",
        "yanban.knowledge.upload.retry-backoff-millis=100",
        "yanban.knowledge.upload.max-processing-attempts=4",
        "yanban.knowledge.upload.outbox-delay-millis=600000",
        "yanban.knowledge.upload.reconciliation-delay-millis=600000"
})
class KnowledgePipelineRealInfrastructureTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired KbDocumentRepository documents;
    @Autowired KnowledgeOutboxDispatcher dispatcher;
    @MockBean EmbeddingClient embedding;
    @MockBean OcrProvider ocr;

    @Test
    void uploadOutboxKafkaRetryMinioAndElasticsearchCompleteAsOnePipeline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(embedding.embedAll(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) throw new IllegalStateException("temporary provider outage");
            java.util.List<?> inputs = invocation.getArgument(0);
            return inputs.stream().map(ignored -> Collections.nCopies(1024, 0.01d)).toList();
        });
        String token = registerAndGetToken("issue185_e2e_" + System.currentTimeMillis());
        String uploadId = "real-" + System.currentTimeMillis();

        mockMvc.perform(multipart("/api/v1/upload/chunk")
                        .file(new MockMultipartFile("file", "part-0", "text/plain",
                                "真实 Kafka、MinIO 和 Elasticsearch 链路测试。".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("uploadId", uploadId)
                        .param("filename", "pipeline.txt")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        MvcResult merge = mockMvc.perform(post("/api/v1/upload/merge")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"uploadId\":\"" + uploadId
                                + "\",\"filename\":\"pipeline.txt\",\"totalChunks\":1,"
                                + "\"isPublic\":false,\"mimeType\":\"text/plain\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long documentId = json.readTree(merge.getResponse().getContentAsString()).get("id").asLong();

        dispatcher.dispatchDue();
        awaitStatus(documentId, "READY", Duration.ofSeconds(20));
        assertThat(calls).hasValue(3);

        mockMvc.perform(post("/api/v1/upload/merge")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"uploadId\":\"" + uploadId
                                + "\",\"filename\":\"pipeline.txt\",\"totalChunks\":1,"
                                + "\"isPublic\":false,\"mimeType\":\"text/plain\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(documentId));
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void awaitStatus(long documentId, String expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        String last = null;
        String error = null;
        while (Instant.now().isBefore(deadline)) {
            var document = documents.findById(documentId).orElseThrow();
            last = document.getStatus();
            error = document.getErrorMessage();
            if (expected.equals(last)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("document status was " + last + ", expected " + expected + ", error=" + error);
    }
}
