package com.yanban.api.agent;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.domain.KbProcessingOutboxRepository;
import com.yanban.knowledge.service.KnowledgeOutboxDispatcher;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import okhttp3.Headers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_chunk_upload_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class ChunkUploadIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KbDocumentRepository documents;

    @Autowired
    KbProcessingOutboxRepository outbox;

    @Autowired
    KnowledgeOutboxDispatcher dispatcher;

    @MockBean
    MinioClient minioClient;

    @MockBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() throws Exception {
        when(minioClient.putObject(any())).thenReturn(null);
        doNothing().when(minioClient).removeObject(any());
        when(kafkaTemplate.send(any(String.class), any(String.class), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void uploadChunksThenMergeCreatesProcessingDocument() throws Exception {
        String token = registerAndGetToken("chunk_user_a");

        mockMvc.perform(multipart("/api/v1/upload/chunk")
                        .file(new MockMultipartFile("file", "part-0", "application/octet-stream", "hello ".getBytes()))
                        .param("uploadId", "upload-a")
                        .param("filename", "paper.pdf")
                        .param("chunkNumber", "0")
                        .param("totalChunks", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        mockMvc.perform(multipart("/api/v1/upload/chunk")
                        .file(new MockMultipartFile("file", "part-1", "application/octet-stream", "world".getBytes()))
                        .param("uploadId", "upload-a")
                        .param("filename", "paper.pdf")
                        .param("chunkNumber", "1")
                        .param("totalChunks", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"));

        when(minioClient.getObject(any()))
                .thenReturn(mockResponse("hello ".getBytes()))
                .thenReturn(mockResponse("world".getBytes()));

        MvcResult result = mockMvc.perform(post("/api/v1/upload/merge")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{" +
                                "\"uploadId\":\"upload-a\"," +
                                "\"filename\":\"paper.pdf\"," +
                                "\"totalChunks\":2," +
                                "\"isPublic\":false," +
                                "\"mimeType\":\"application/pdf\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();

        Long documentId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        var document = documents.findById(documentId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(document.getObjectKey()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(document.getMimeType()).isEqualTo("application/pdf");
        org.assertj.core.api.Assertions.assertThat(document.getFileSize()).isEqualTo(11L);
        org.assertj.core.api.Assertions.assertThat(document.getUploadId()).isEqualTo("upload-a");
        org.assertj.core.api.Assertions.assertThat(document.getFileDigest()).hasSize(64);
        org.assertj.core.api.Assertions.assertThat(outbox.count()).isEqualTo(1);

        dispatcher.dispatchDue();
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("file-processing"),
                org.mockito.ArgumentMatchers.eq(documentId.toString()), any(String.class));

        mockMvc.perform(post("/api/v1/upload/merge")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{" +
                                "\"uploadId\":\"upload-a\"," +
                                "\"filename\":\"paper.pdf\"," +
                                "\"totalChunks\":2," +
                                "\"isPublic\":false," +
                                "\"mimeType\":\"application/pdf\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(documentId));
        org.assertj.core.api.Assertions.assertThat(documents.findAll()).hasSize(1);
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private GetObjectResponse mockResponse(byte[] bytes) {
        return new GetObjectResponse(
                Headers.of(Map.of("Content-Type", "application/octet-stream")),
                "yanban-agent",
                "us-east-1",
                "test-object",
                new ByteArrayInputStream(bytes)
        );
    }
}
