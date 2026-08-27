package com.yanban.api.demo;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.admin.AdminService;
import com.yanban.api.user.SysUserRepository;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.KnowledgeIndexService;
import com.yanban.knowledge.service.KnowledgeSearchIndexClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_demo_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890",
        "yanban.demo.enabled=true",
        "yanban.demo.seed-on-startup=false",
        "yanban.knowledge.elasticsearch.vector-dimensions=2"
})
class DemoControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SysUserRepository users;

    @Autowired
    AgentSessionRepository sessions;

    @Autowired
    AgentMessageRepository messages;

    @Autowired
    AdminService adminService;

    @MockBean
    EmbeddingClient embeddingClient;

    @MockBean
    KnowledgeSearchIndexClient indexClient;

    @MockBean
    KnowledgeIndexService knowledgeIndexService;

    @BeforeEach
    void setUp() {
        when(embeddingClient.embed(any())).thenReturn(List.of(0.1d, 0.2d));
        when(embeddingClient.embedAll(any())).thenAnswer(invocation -> {
            List<?> values = invocation.getArgument(0);
            return values.stream().map(ignored -> List.of(0.1d, 0.2d)).toList();
        });
        when(indexClient.searchLexical(any(), any(), anyInt())).thenReturn(List.of());
        when(indexClient.searchVector(any(), any(), anyInt())).thenReturn(List.of());
        when(knowledgeIndexService.indexChunk(any())).thenReturn("es-demo");
        when(knowledgeIndexService.indexChunks(any())).thenAnswer(invocation -> {
            List<?> values = invocation.getArgument(0);
            return java.util.stream.IntStream.range(0, values.size())
                    .mapToObj(index -> "es-demo-" + index)
                    .toList();
        });
        doNothing().when(knowledgeIndexService).deleteByDocumentId(any());
    }

    @Test
    void demoLoginSeedsDocumentsAndMarksCurrentUserAsDemo() throws Exception {
        String token = demoLogin();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("demo"))
                .andExpect(jsonPath("$.accountType").value("DEMO"))
                .andExpect(jsonPath("$.demo").value(true));

        mockMvc.perform(get("/api/v1/kb/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].sourceType").value("DEMO_SEED"));

        mockMvc.perform(post("/api/v1/search")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Agent\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    void demoSeedDocumentsAndSettingsAreProtected() throws Exception {
        String token = demoLogin();
        MvcResult list = mockMvc.perform(get("/api/v1/kb/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode first = objectMapper.readTree(list.getResponse().getContentAsString()).get(0);

        mockMvc.perform(delete("/api/v1/kb/documents/{documentId}", first.get("id").asLong())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSteps\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicDemoConfigCanBeReadWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/v1/demo/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.exampleQuestions.length()").value(4));
    }

    @Test
    void nextDemoLoginHidesPriorConversationFromGuestButRetainsAdminArchive() throws Exception {
        demoLogin();
        Long demoUserId = users.findByUsername("demo").orElseThrow().getId();
        AgentSession prior = sessions.saveAndFlush(new AgentSession(
                demoUserId, "往期游客会话", "deepseek", "deepseek-chat", 20, false));
        messages.saveAndFlush(new AgentMessage(
                prior.getId(), demoUserId, "user", "请分析这个问题", null, null));
        messages.saveAndFlush(new AgentMessage(
                prior.getId(), demoUserId, "assistant", "这是往期回答", null, null));

        String nextVisitorToken = demoLogin();

        mockMvc.perform(get("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + nextVisitorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        var archived = adminService.userDetail(demoUserId).chats().stream()
                .filter(chat -> "往期游客会话".equals(chat.title()))
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(archived.archived()).isTrue();
        org.assertj.core.api.Assertions.assertThat(archived.messages())
                .extracting(message -> message.role() + ":" + message.content())
                .containsExactly("user:请分析这个问题", "assistant:这是往期回答");
        org.assertj.core.api.Assertions.assertThat(adminService.userDetail(demoUserId)
                .user().chatSessionCount()).isGreaterThanOrEqualTo(1);
    }

    private String demoLogin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/demo-login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
