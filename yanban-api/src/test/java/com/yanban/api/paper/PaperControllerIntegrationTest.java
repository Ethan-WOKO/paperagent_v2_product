package com.yanban.api.paper;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.literature.LiteratureRecommendationService;
import com.yanban.paper.literature.LiteratureSource;
import com.yanban.paper.service.PaperModelClient;
import com.yanban.paper.service.PaperOrchestrator;
import io.minio.MinioClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_paper_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class PaperControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PaperTaskRepository paperTaskRepository;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    MinioClient minioClient;

    /**
     * The HTTP contract creates a task after persistence, but the real
     * orchestrator starts model and literature work on its own executor. The
     * mock is the test double for that execution boundary; the test verifies
     * its after-persistence delegation without starting background work.
     */
    @MockBean
    PaperOrchestrator paperOrchestrator;

    @MockBean
    PaperModelClient paperModelClient;

    @MockBean
    LiteratureRecommendationService literatureRecommendationService;

    @MockBean(name = "openAlexLiteratureSource")
    LiteratureSource openAlexLiteratureSource;

    @MockBean(name = "arxivLiteratureSource")
    LiteratureSource arxivLiteratureSource;

    @BeforeEach
    void setUp() throws Exception {
        when(minioClient.putObject(any())).thenReturn(null);
    }

    @AfterEach
    void externalProvidersRemainUncalled() {
        verifyNoInteractions(paperModelClient, literatureRecommendationService,
                openAlexLiteratureSource, arxivLiteratureSource);
        verifyNoMoreInteractions(paperOrchestrator);
    }

    @Test
    void uploadLatexCreatesPendingTask() throws Exception {
        String token = registerAndGetToken("paper_user_a");

        MvcResult result = mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "main.tex",
                                "application/x-tex",
                                "\\documentclass{article}\\begin{document}Hi\\end{document}".getBytes()))
                        .file(new MockMultipartFile("bibFile", "refs.bib",
                                "text/x-bibtex",
                                "@article{a,title={A}}".getBytes()))
                        .param("scoreThreshold", "75")
                        .param("maxRounds", "3")
                        .param("innerMaxAttempts", "2")
                        .param("literatureCount", "5")
                        .param("targetLanguage", "zh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceFilename").value("main.tex"))
                .andExpect(jsonPath("$.scoreThreshold").value(75))
                .andExpect(jsonPath("$.maxRounds").value(3))
                .andExpect(jsonPath("$.innerMaxAttempts").value(2))
                .andExpect(jsonPath("$.objectKey", not(blankOrNullString())))
                .andReturn();

        Long taskId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        verify(paperOrchestrator).startTask(taskId);
        var task = paperTaskRepository.findById(taskId).orElseThrow();
        Assertions.assertThat(task.getStatus()).isIn("PENDING", "RUNNING", "COMPLETED");
        Assertions.assertThat(task.getObjectKey()).isNotBlank();
        Assertions.assertThat(task.getTargetLanguage()).isEqualTo("zh");
        Assertions.assertThat(task.getInputFormat()).isEqualTo("LATEX");
        Assertions.assertThat(task.getMode()).isEqualTo("LATEX_BIB");
        Assertions.assertThat(task.getScoreThreshold()).isEqualTo(75);
        Assertions.assertThat(task.getMaxRounds()).isEqualTo(3);
        Assertions.assertThat(task.getInnerMaxAttempts()).isEqualTo(2);
        Assertions.assertThat(task.getCurrentStage()).isEqualTo("UPLOAD_RECEIVED");

        mockMvc.perform(get("/api/v1/paper/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.scoreThreshold").value(75))
                .andExpect(jsonPath("$.maxRounds").value(3))
                .andExpect(jsonPath("$.innerMaxAttempts").value(2));

        if (task.getFinalObjectKey() != null && !task.getFinalObjectKey().isBlank()) {
            mockMvc.perform(get("/api/v1/paper/tasks/{taskId}/download", taskId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        }
    }

    @Test
    void rejectInvalidExtension() throws Exception {
        String token = registerAndGetToken("paper_user_b");

        mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "sample.pdf", "application/pdf", "pdf-content".getBytes()))
                        .param("scoreThreshold", "75")
                        .param("maxRounds", "3")
                        .param("innerMaxAttempts", "2")
                        .param("literatureCount", "5")
                        .param("targetLanguage", "zh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("main document only supports .tex"))
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    void rejectScoreThresholdOutsideZeroToOneHundredScale() throws Exception {
        String token = registerAndGetToken("paper_user_score_threshold");

        mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "main.tex",
                                "application/x-tex",
                                "\\documentclass{article}\\begin{document}Hi\\end{document}".getBytes()))
                        .param("scoreThreshold", "101")
                        .param("maxRounds", "3")
                        .param("innerMaxAttempts", "2")
                        .param("literatureCount", "5")
                        .param("targetLanguage", "zh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameClientRequestIdAndSameInputReusesExistingTask() throws Exception {
        String token = registerAndGetToken("paper_user_c");
        String clientRequestId = "paper-req-83";

        MvcResult first = mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "main.tex",
                                "application/x-tex",
                                "\\documentclass{article}\\begin{document}Hello\\end{document}".getBytes()))
                        .file(new MockMultipartFile("bibFile", "refs.bib",
                                "text/x-bibtex",
                                "@article{a,title={A}}".getBytes()))
                        .param("literatureCount", "5")
                        .param("literatureMinCount", "3")
                        .param("targetLanguage", "zh")
                        .header("X-Client-Request-Id", clientRequestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientRequestId").value(clientRequestId))
                .andExpect(jsonPath("$.idempotent").value(false))
                .andReturn();

        Long firstTaskId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(multipart("/api/v1/paper/process")
                        .file(new MockMultipartFile("mainTex", "main.tex",
                                "application/x-tex",
                                "\\documentclass{article}\\begin{document}Hello\\end{document}".getBytes()))
                        .file(new MockMultipartFile("bibFile", "refs.bib",
                                "text/x-bibtex",
                                "@article{a,title={A}}".getBytes()))
                        .param("literatureCount", "5")
                        .param("literatureMinCount", "3")
                        .param("targetLanguage", "zh")
                        .header("X-Client-Request-Id", clientRequestId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(firstTaskId))
                .andExpect(jsonPath("$.clientRequestId").value(clientRequestId))
                .andExpect(jsonPath("$.idempotent").value(true));

        Assertions.assertThat(paperTaskRepository.countByUserId(resolveUserId("paper_user_c"))).isEqualTo(1L);
        verify(paperOrchestrator, times(1)).startTask(firstTaskId);
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

    private Long resolveUserId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_users WHERE username = ?", Long.class, username);
    }
}
