package com.yanban.api.agent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.KnowledgeIndexService;
import com.yanban.knowledge.service.KnowledgeSearchIndexClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_kb_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class KnowledgeControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    EmbeddingClient embeddingClient;

    @MockBean
    KnowledgeSearchIndexClient indexClient;

    @MockBean
    KnowledgeIndexService knowledgeIndexService;

    @BeforeEach
    void setUp() {
        when(embeddingClient.embed(any())).thenReturn(List.of(0.1d, 0.2d));
        when(indexClient.searchLexical(any(), any(), any(Integer.class))).thenReturn(List.of());
        when(indexClient.searchVector(any(), any(), any(Integer.class))).thenReturn(List.of());
        doNothing().when(knowledgeIndexService).deleteByDocumentId(any());
    }

    @Test
    void simpleUploadThenSearchFindsOwnPrivateDocument() throws Exception {
        String token = registerAndGetToken("kb_user_private_a");
        upload(token, false, "notes.md", "alpha keyword only for owner");

        mockMvc.perform(post("/api/v1/search")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"alpha\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].filename").value("notes.md"))
                .andExpect(jsonPath("$[0].chunkText").value(org.hamcrest.Matchers.containsString("alpha")))
                .andExpect(jsonPath("$[0].citationId").exists())
                .andExpect(jsonPath("$[0].rerankScore").exists())
                .andExpect(jsonPath("$[0].rerankReason").exists());
    }

    @Test
    void privateDocumentIsInvisibleToAnotherUser() throws Exception {
        String tokenA = registerAndGetToken("kb_user_private_owner");
        String tokenB = registerAndGetToken("kb_user_private_other");
        upload(tokenA, false, "secret.txt", "beta secret keyword");

        mockMvc.perform(post("/api/v1/search")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"beta\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void publicDocumentCanBeFoundByAnotherUser() throws Exception {
        String tokenA = registerAndGetToken("kb_user_public_owner");
        String tokenB = registerAndGetToken("kb_user_public_other");
        upload(tokenA, true, "public.txt", "gamma visible keyword");

        mockMvc.perform(post("/api/v1/search")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"gamma\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].filename").value("public.txt"))
                .andExpect(jsonPath("$[0].isPublic").value(true));
    }

    @Test
    void listDocumentsReturnsOnlyCurrentUsersDocuments() throws Exception {
        String tokenA = registerAndGetToken("kb_list_owner");
        String tokenB = registerAndGetToken("kb_list_other");
        upload(tokenA, false, "mine.md", "owner content");
        upload(tokenB, false, "other.md", "other content");

        mockMvc.perform(get("/api/v1/kb/documents")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].filename").value("mine.md"))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void previewDocumentReturnsParsedTextForOwner() throws Exception {
        String token = registerAndGetToken("kb_preview_owner");
        Long documentId = uploadAndReturnId(token, false, "preview.md", "preview alpha content\nsecond line");

        mockMvc.perform(get("/api/v1/kb/documents/{documentId}/preview", documentId)
                        .queryParam("maxChars", "2000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.filename").value("preview.md"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.totalChunks").value(1))
                .andExpect(jsonPath("$.previewChunks").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("preview alpha content")));
    }

    @Test
    void privateDocumentPreviewIsForbiddenForAnotherUser() throws Exception {
        String tokenA = registerAndGetToken("kb_preview_private_owner");
        String tokenB = registerAndGetToken("kb_preview_private_other");
        Long documentId = uploadAndReturnId(tokenA, false, "private-preview.md", "private preview content");

        mockMvc.perform(get("/api/v1/kb/documents/{documentId}/preview", documentId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteDocumentRemovesItFromList() throws Exception {
        String token = registerAndGetToken("kb_delete_owner");
        Long documentId = uploadAndReturnId(token, false, "delete-me.md", "delete me content");

        mockMvc.perform(delete("/api/v1/kb/documents/{documentId}", documentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/kb/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private void upload(String token, boolean isPublic, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "text/plain", content.getBytes());
        mockMvc.perform(multipart("/api/v1/kb/documents/simple-upload")
                        .file(file)
                        .param("isPublic", String.valueOf(isPublic))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    private Long uploadAndReturnId(String token, boolean isPublic, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, "text/plain", content.getBytes());
        MvcResult result = mockMvc.perform(multipart("/api/v1/kb/documents/simple-upload")
                        .file(file)
                        .param("isPublic", String.valueOf(isPublic))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
